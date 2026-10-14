import com.williamcallahan.tui4j.compat.bubbletea.Command;
import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.Message;
import com.williamcallahan.tui4j.compat.bubbletea.Model;
import com.williamcallahan.tui4j.compat.bubbletea.Program;
import com.williamcallahan.tui4j.compat.bubbletea.ProgramException;
import com.williamcallahan.tui4j.compat.bubbletea.QuitMessage;
import com.williamcallahan.tui4j.compat.bubbletea.UpdateResult;
import com.williamcallahan.tui4j.compat.bubbletea.WindowSizeMessage;
import com.williamcallahan.tui4j.compat.lipgloss.Style;
import com.williamcallahan.tui4j.compat.lipgloss.color.Color;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/*
 * Interactive processor, memory, PCI, and USB system information for Linux.
 */
public class SystemFetch implements Model {

    /* Report sections displayed as tabs in the interactive interface. */
    private static final String[] TABS = {
        "Processor",
        "Memory",
        "PCI",
        "USB",
    };
    /* Below these dimensions the tab rail and report cannot be read reliably. */
    private static final int MIN_TERMINAL_WIDTH = 40;
    private static final int MIN_TERMINAL_HEIGHT = 7;
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(2);

    /* 256-color Lipgloss styles distinguish the active tab, headings, and labels. */
    private static final Style ACTIVE_TAB = Style.newStyle()
        .foreground(Color.color("15"))
        .background(Color.color("99"))
        .bold(true)
        .padding(0, 1);
    private static final Style INACTIVE_TAB = Style.newStyle()
        .foreground(Color.color("245"))
        .bold(true)
        .padding(0, 1);
    private static final Style TITLE = Style.newStyle()
        .foreground(Color.color("39"))
        .bold(true);
    private static final Style SECTION_TITLE = Style.newStyle()
        .foreground(Color.color("205"))
        .bold(true);
    private static final Style LABEL = Style.newStyle()
        .foreground(Color.color("43"))
        .bold(true);
    private static final Style ACCENT = Style.newStyle().foreground(
        Color.color("99")
    );
    private static final Style HELP = Style.newStyle().foreground(
        Color.color("245")
    );

    /* Cumulative busy and idle CPU ticks read from /proc/stat. */
    private record Cpu(long busy, long idle) {}

    /* Processor model, logical CPU count, and physical-core mapping from /proc/cpuinfo. */
    private record CpuInfo(
        String model,
        int logicalCpus,
        Map<Integer, String> coreByCpu,
        boolean topologyAvailable
    ) {}

    /* A PCI function's address, IDs, class, and resolved names. */
    private record Pci(
        int bus,
        int device,
        int function,
        int vendor,
        int id,
        int deviceClass,
        String vendorName,
        String name
    ) {}

    /* A USB device's vendor/product IDs and resolved names. */
    private record Usb(int vendor, int id, String vendorName, String name) {}

    /* A vendor name and the device/product names belonging to that vendor. */
    private record Vendor(String name, Map<Integer, String> devices) {}

    private record CpuLoad(int id, double utilization) {}

    private record CoreLoad(String name, double utilization) {}

    private record ProcessorSnapshot(
        String model,
        int logicalCpus,
        Integer physicalCores,
        boolean topologyAvailable,
        boolean topologyComplete,
        List<CpuLoad> logicalLoads,
        Double averageLogicalLoad,
        List<CoreLoad> physicalLoads,
        Double averagePhysicalLoad
    ) {}

    private record MemorySnapshot(
        long totalKiB,
        long usedKiB,
        double usedPercent
    ) {}

    private record SystemSnapshot(
        ProcessorSnapshot processor,
        MemorySnapshot memory,
        List<Pci> pci,
        List<Usb> usb
    ) {}

    /* Loads hardware IDs and system data, then runs the interactive or text report. */
    public static void main(String[] args)
        throws IOException, InterruptedException {
        Path jsonFile = parseJsonFile(args);
        Map<Integer, Vendor> pciNames = loadIds(
            "pci.ids",
            "https://pci-ids.ucw.cz/v2.2/pci.ids"
        );
        Map<Integer, Vendor> usbNames = loadIds(
            "usb.ids",
            "https://raw.githubusercontent.com/vcrhonek/hwdata/master/usb.ids"
        );

        if (System.console() == null || "dumb".equals(System.getenv("TERM"))) {
            Snapshot snapshot = collectPlainSnapshot(pciNames, usbNames);
            if (jsonFile != null) writeJsonAtomically(jsonFile, snapshot.data());
            printPlainReport(snapshot.sections());
            return;
        }
        try {
            new Program(new SystemFetch(pciNames, usbNames, jsonFile))
                .withAltScreen()
                .run();
        } catch (ProgramException e) {
            System.err.println(
                "Interactive terminal unavailable; printing plain-text report."
            );
            Snapshot snapshot = collectPlainSnapshot(pciNames, usbNames);
            if (jsonFile != null) writeJsonAtomically(jsonFile, snapshot.data());
            printPlainReport(snapshot.sections());
        }
    }

    /* Samples CPU counters twice for a useful initial plain-text load report. */
    private static Snapshot collectPlainSnapshot(
        Map<Integer, Vendor> pciNames,
        Map<Integer, Vendor> usbNames
    ) throws IOException, InterruptedException {
        Snapshot baseline = collectSections(Map.of(), pciNames, usbNames);
        Thread.sleep(1000);
        return collectSections(
            baseline.cpuTimes(),
            pciNames,
            usbNames
        );
    }

    private static Path parseJsonFile(String[] args) {
        Path jsonFile = null;
        for (int i = 0; i < args.length; i++) {
            if (!args[i].equals("--json-file")) {
                throw new IllegalArgumentException(
                    "Unknown option: " + args[i] + "\nUsage: SystemFetch [--json-file <path>]"
                );
            }
            if (jsonFile != null || i + 1 >= args.length) {
                throw new IllegalArgumentException(
                    "Usage: SystemFetch [--json-file <path>]"
                );
            }
            jsonFile = Path.of(args[++i]).toAbsolutePath();
        }
        return jsonFile;
    }

    /* Prints all sections sequentially when a full-screen terminal is unavailable. */
    private static void printPlainReport(List<String> sections) {
        for (int i = 0; i < sections.size(); i++) {
            System.out.println("\n" + TABS[i]);
            System.out.println(sections.get(i));
        }
    }

    private record Snapshot(
        List<String> sections,
        Map<Integer, Cpu> cpuTimes,
        SystemSnapshot data
    ) {}

    private record RefreshMessage(
        Snapshot snapshot,
        String error
    ) implements Message {}

    private record RefreshTick() implements Message {}

    /* Current report content and terminal navigation state. */
    private final List<String> sections;
    private final Map<Integer, Vendor> pciNames;
    private final Map<Integer, Vendor> usbNames;
    private final Path jsonFile;
    private Map<Integer, Cpu> previousCpuTimes = Map.of();
    private String refreshStatus = "Collecting system information...";
    private int selectedTab;
    private int terminalWidth = 80;
    private int terminalHeight = 24;
    private int scroll;

    /* Starts with placeholders so the TUI can render before collection completes. */
    private SystemFetch(
        Map<Integer, Vendor> pciNames,
        Map<Integer, Vendor> usbNames,
        Path jsonFile
    ) {
        this.pciNames = pciNames;
        this.usbNames = usbNames;
        this.jsonFile = jsonFile;
        this.sections = new ArrayList<>(
            List.of(
                "Collecting processor information...",
                "Collecting memory information...",
                "Collecting PCI devices...",
                "Collecting USB devices..."
            )
        );
    }

    /*
     * Loads an ID database from a local path or system directory, downloading it if needed.
     * If a download is unavailable or invalid, the caller can still display raw IDs.
     */
    private static Map<Integer, Vendor> loadIds(String filename, String url)
        throws IOException, InterruptedException {
        // Reuse provided or system-installed databases before trying the network.
        for (Path file : List.of(
            Path.of(filename),
            Path.of("data", filename),
            Path.of("ids", filename),
            Path.of("/usr/share/hwdata", filename),
            Path.of("/usr/share/misc", filename)
        )) {
            if (Files.isRegularFile(file)) return readIds(file);
        }

        try {
            // Bound network waits so an unavailable server does not stall the report.
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
            HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );
            // Reject error pages and empty responses before caching them as ID databases.
            if (
                response.statusCode() != 200 ||
                !response.body().matches("(?s).*?\\n?[0-9a-fA-F]{4}\\s+\\S.*")
            ) {
                System.out.println(
                    "Could not download " + filename + "; displaying raw IDs."
                );
                return Map.of();
            }
            // Cache a successful download so future runs do not need network access.
            Path file = Path.of("data", filename);
            Files.createDirectories(file.getParent());
            Files.writeString(file, response.body());
            System.out.println("Downloaded " + filename + " to " + file);
            return readIds(file);
        } catch (IOException e) {
            System.out.println(
                "Could not obtain " +
                    filename +
                    ": " +
                    e.getMessage() +
                    "; displaying raw IDs."
            );
            return Map.of();
        }
    }

    /*
     * Parses the vendor and indented device entries in pci.ids or usb.ids.
     */
    private static Map<Integer, Vendor> readIds(Path file) throws IOException {
        Map<Integer, Vendor> vendors = new HashMap<>();
        int vendorId = -1;
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+", 2);
            // Vendor and device entries both start with four hexadecimal digits.
            if (
                parts.length < 2 || !parts[0].matches("[0-9a-fA-F]{4}")
            ) continue;
            int id = Integer.parseInt(parts[0], 16);
            if (!Character.isWhitespace(line.charAt(0))) {
                vendorId = id;
                vendors.put(vendorId, new Vendor(parts[1], new HashMap<>()));
            } else if (vendorId >= 0 && line.charAt(0) == '\t') {
                // ID files indent device/product entries beneath their vendor.
                vendors.get(vendorId).devices().putIfAbsent(id, parts[1]);
            }
        }
        return vendors;
    }

    /* Collects a complete system snapshot without waiting between CPU samples. */
    private static Snapshot collectSections(
        Map<Integer, Cpu> previousCpuTimes,
        Map<Integer, Vendor> pciNames,
        Map<Integer, Vendor> usbNames
    ) throws IOException {
        CpuInfo info = readCpuInfo();
        Map<Integer, Cpu> currentCpuTimes = cpuTimes();
        ProcessorSnapshot processor = processorSnapshot(
            info,
            previousCpuTimes,
            currentCpuTimes
        );
        MemorySnapshot memory = memorySnapshot();
        List<Pci> pci = readPci(pciNames);
        List<Usb> usb = readUsb(usbNames);
        List<String> sections = List.of(
            processorInfo(processor),
            memoryInfo(memory),
            pciInfo(pci),
            usbInfo(usb)
        );
        return new Snapshot(
            sections,
            currentCpuTimes,
            new SystemSnapshot(processor, memory, pci, usb)
        );
    }

    /* Writes a complete snapshot to a sibling temporary file before replacing the target. */
    private static void writeJsonAtomically(Path target, SystemSnapshot snapshot)
        throws IOException {
        Path parent = target.getParent();
        if (parent == null) parent = Path.of(".").toAbsolutePath();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".systemfetch-", ".tmp");
        try {
            Files.writeString(temporary, toJson(snapshot));
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException(
                "Atomic replacement is not supported for " + target,
                e
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String toJson(SystemSnapshot snapshot) {
        ProcessorSnapshot processor = snapshot.processor();
        MemorySnapshot memory = snapshot.memory();
        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"schema_version\": 1,\n  \"generated_at\": ")
            .append(jsonString(Instant.now().toString()))
            .append(",\n  \"processor\": {\n");
        json.append("    \"model\": ").append(jsonString(processor.model())).append(",\n");
        json.append("    \"logical_cpus\": ").append(processor.logicalCpus()).append(",\n");
        json.append("    \"physical_cores\": ")
            .append(processor.physicalCores() == null ? "null" : processor.physicalCores())
            .append(",\n    \"physical_topology_available\": ")
            .append(processor.topologyAvailable())
            .append(",\n    \"physical_topology_complete\": ")
            .append(processor.topologyComplete())
            .append(",\n    \"logical_cpu_load_percent\": [");
        for (int i = 0; i < processor.logicalLoads().size(); i++) {
            CpuLoad load = processor.logicalLoads().get(i);
            if (i > 0) json.append(',');
            json.append("\n      {\"id\": ")
                .append(load.id())
                .append(", \"utilization\": ")
                .append(load.utilization())
                .append('}');
        }
        if (!processor.logicalLoads().isEmpty()) json.append('\n').append("    ");
        json.append("],\n    \"average_logical_load_percent\": ")
            .append(jsonNumber(processor.averageLogicalLoad()))
            .append(",\n    \"physical_core_load_percent\": [");
        for (int i = 0; i < processor.physicalLoads().size(); i++) {
            CoreLoad load = processor.physicalLoads().get(i);
            if (i > 0) json.append(',');
            json.append("\n      {\"name\": ")
                .append(jsonString(load.name()))
                .append(", \"utilization\": ")
                .append(load.utilization())
                .append('}');
        }
        if (!processor.physicalLoads().isEmpty()) json.append('\n').append("    ");
        json.append("],\n    \"average_physical_core_load_percent\": ")
            .append(jsonNumber(processor.averagePhysicalLoad()))
            .append("\n  },\n  \"memory\": {\n")
            .append("    \"total_kib\": ")
            .append(memory.totalKiB())
            .append(",\n    \"used_kib\": ")
            .append(memory.usedKiB())
            .append(",\n    \"used_percent\": ")
            .append(memory.usedPercent())
            .append("\n  },\n  \"pci_devices\": [");
        for (int i = 0; i < snapshot.pci().size(); i++) {
            Pci device = snapshot.pci().get(i);
            if (i > 0) json.append(',');
            json.append("\n    {\"bus\": ")
                .append(device.bus())
                .append(", \"device\": ")
                .append(device.device())
                .append(", \"function\": ")
                .append(device.function())
                .append(", \"vendor_id\": ")
                .append(jsonString(hex(device.vendor())))
                .append(", \"device_id\": ")
                .append(jsonString(hex(device.id())))
                .append(", \"class\": ")
                .append(jsonString(String.format(Locale.ROOT, "%06x", device.deviceClass())))
                .append(", \"vendor_name\": ")
                .append(jsonString(device.vendorName()))
                .append(", \"name\": ")
                .append(jsonString(device.name()))
                .append('}');
        }
        if (!snapshot.pci().isEmpty()) json.append('\n').append("  ");
        json.append("],\n  \"usb_devices\": [");
        for (int i = 0; i < snapshot.usb().size(); i++) {
            Usb device = snapshot.usb().get(i);
            if (i > 0) json.append(',');
            json.append("\n    {\"vendor_id\": ")
                .append(jsonString(hex(device.vendor())))
                .append(", \"product_id\": ")
                .append(jsonString(hex(device.id())))
                .append(", \"vendor_name\": ")
                .append(jsonString(device.vendorName()))
                .append(", \"name\": ")
                .append(jsonString(device.name()))
                .append('}');
        }
        if (!snapshot.usb().isEmpty()) json.append('\n').append("  ");
        return json.append("]\n}\n").toString();
    }

    private static String jsonNumber(Double number) {
        return number == null ? "null" : number.toString();
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    /* Calculates per-CPU and per-core loads from successive cumulative tick samples. */
    private static ProcessorSnapshot processorSnapshot(
        CpuInfo info,
        Map<Integer, Cpu> before,
        Map<Integer, Cpu> after
    ) {
        List<CpuLoad> logicalLoads = new ArrayList<>();
        Map<String, Cpu> physicalCoreTimes = new TreeMap<>();
        for (int id : after.keySet().stream().sorted().toList()) {
            Cpu start = before.get(id),
                end = after.get(id);
            if (start == null) continue;
            // Linux reports cumulative ticks; differences give the measured load.
            long busy = end.busy() - start.busy();
            long elapsed = busy + end.idle() - start.idle();
            if (elapsed <= 0 || busy < 0) continue;
            double load = busy * 100.0 / elapsed;
            logicalLoads.add(new CpuLoad(id, load));
            String core = info.coreByCpu().get(id);
            if (core != null) {
                physicalCoreTimes.merge(
                    core,
                    new Cpu(busy, elapsed - busy),
                    (first, second) ->
                        new Cpu(first.busy() + second.busy(), first.idle() + second.idle())
                );
            }
        }
        List<CoreLoad> physicalLoads = physicalCoreTimes.entrySet()
            .stream()
            .map(entry -> {
                Cpu times = entry.getValue();
                long elapsed = times.busy() + times.idle();
                return elapsed <= 0
                    ? null
                    : new CoreLoad(
                        entry.getKey(),
                        times.busy() * 100.0 / elapsed
                    );
            })
            .filter(Objects::nonNull)
            .toList();
        Integer physicalCores = info.topologyAvailable()
            ? (int) info.coreByCpu().values().stream().distinct().count()
            : null;
        return new ProcessorSnapshot(
            info.model(),
            info.logicalCpus(),
            physicalCores,
            info.topologyAvailable(),
            info.topologyAvailable() &&
                info.coreByCpu().values().stream().noneMatch(name -> name.startsWith("CPU ")),
            List.copyOf(logicalLoads),
            averageLoad(logicalLoads.stream().map(CpuLoad::utilization).toList()),
            physicalLoads,
            averageLoad(physicalLoads.stream().map(CoreLoad::utilization).toList())
        );
    }

    private static Double averageLoad(List<Double> loads) {
        return loads.isEmpty()
            ? null
            : loads.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    /* Renders processor topology and loads for the terminal report. */
    private static String processorInfo(ProcessorSnapshot info) {
        StringBuilder output = new StringBuilder();
        output.append("Model: ").append(info.model()).append('\n');
        output.append("Logical CPUs: ").append(info.logicalCpus()).append('\n');
        if (info.topologyAvailable()) {
            output.append("Physical CPU cores: ").append(info.physicalCores());
            if (!info.topologyComplete()) {
                output.append(
                    " (topology incomplete; unmapped logical CPUs counted individually)"
                );
            }
            output.append('\n');
        } else {
            output.append(
                "Physical CPU core topology unavailable; load is grouped per logical CPU.\n"
            );
        }
        output.append("Load per logical CPU:\n");
        if (info.logicalLoads().isEmpty()) output.append("  Sampling...\n");
        for (CpuLoad load : info.logicalLoads()) {
            output.append(
                String.format(
                    Locale.ROOT,
                    "  CPU %d: %.1f%%%n",
                    load.id(),
                    load.utilization()
                )
            );
        }
        if (info.averageLogicalLoad() != null) {
            output.append(
                String.format(
                    Locale.ROOT,
                    "Average across all logical CPUs: %.1f%%%n",
                    info.averageLogicalLoad()
                )
            );
        }
        if (info.topologyAvailable()) {
            output.append("Load per physical core:\n");
            for (CoreLoad load : info.physicalLoads()) {
                output.append(
                    String.format(
                        Locale.ROOT,
                        "  %s: %.1f%%%n",
                        load.name(),
                        load.utilization()
                    )
                );
            }
            if (info.averagePhysicalLoad() != null) {
                output.append(
                    String.format(
                        Locale.ROOT,
                        "Average across all physical cores: %.1f%%%n",
                        info.averagePhysicalLoad()
                    )
                );
            }
        }
        return output.toString();
    }

    /* Reads each /proc/cpuinfo processor block and groups logical CPUs by socket/core IDs. */
    private static CpuInfo readCpuInfo() throws IOException {
        String model = "Unknown";
        int logicalCpus = 0;
        Map<Integer, String> coreByCpu = new HashMap<>();
        Set<Integer> logicalCpuIds = new HashSet<>();
        Integer processor = null, physicalId = null, coreId = null;

        for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
            if (line.isBlank()) {
                addCoreMapping(coreByCpu, processor, physicalId, coreId);
                processor = null;
                physicalId = null;
                coreId = null;
                continue;
            }
            String[] fields = line.split(":", 2);
            if (fields.length != 2) continue;
            String key = fields[0].trim(), value = fields[1].trim();
            if (key.equals("processor")) {
                processor = Integer.parseInt(value);
                logicalCpus++;
                logicalCpuIds.add(processor);
            } else if (key.equals("physical id")) {
                physicalId = Integer.parseInt(value);
            } else if (key.equals("core id")) {
                coreId = Integer.parseInt(value);
            } else if (
                model.equals("Unknown") &&
                (key.equals("model name") || key.equals("Hardware"))
            ) {
                model = value;
            }
        }
        addCoreMapping(coreByCpu, processor, physicalId, coreId);

        if (logicalCpus == 0) {
            logicalCpus = Runtime.getRuntime().availableProcessors();
            for (int id = 0; id < logicalCpus; id++) {
                logicalCpuIds.add(id);
            }
        }
        boolean topologyAvailable = !coreByCpu.isEmpty();
        for (int id : logicalCpuIds) {
            if (!coreByCpu.containsKey(id)) {
                // Do not guess physical topology for CPUs without socket/core metadata.
                coreByCpu.put(id, "CPU " + id);
            }
        }
        return new CpuInfo(model, logicalCpus, coreByCpu, topologyAvailable);
    }

    /* Uses socket plus core ID so identical core IDs on separate sockets stay distinct. */
    private static void addCoreMapping(
        Map<Integer, String> coreByCpu,
        Integer processor,
        Integer physicalId,
        Integer coreId
    ) {
        if (processor == null || physicalId == null || coreId == null) return;
        coreByCpu.put(processor, "Socket " + physicalId + ", core " + coreId);
    }

    /*
     * Reads per-CPU cumulative tick counters; aggregate "cpu" is excluded.
     */
    private static Map<Integer, Cpu> cpuTimes() throws IOException {
        Map<Integer, Cpu> result = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("/proc/stat"))) {
            String[] fields = line.trim().split("\\s+");
            if (!fields[0].matches("cpu\\d+")) continue;
            int id = Integer.parseInt(fields[0].substring(3));
            long idle = Long.parseLong(fields[4]);
            if (fields.length > 5) idle += Long.parseLong(fields[5]);
            long busy = Long.parseLong(fields[1])
                + Long.parseLong(fields[2])
                + Long.parseLong(fields[3])
                + Long.parseLong(fields[6])
                + Long.parseLong(fields[7]);
            if (fields.length > 8) busy += Long.parseLong(fields[8]);
            // Guest ticks are already included in user/nice, so do not count them again.
            result.put(id, new Cpu(busy, idle));
        }
        return result;
    }

    /* Reads total and used memory from Linux's /proc/meminfo values, in KiB. */
    private static MemorySnapshot memorySnapshot() throws IOException {
        Map<String, Long> memory = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
            String[] fields = line.split("\\s+");
            if (fields.length > 1) memory.put(
                fields[0].replace(":", ""),
                Long.parseLong(fields[1])
            );
        }
        long total = memory.get("MemTotal");
        long used =
            total - memory.getOrDefault("MemAvailable", memory.get("MemFree"));
        return new MemorySnapshot(total, used, (used * 100.0) / total);
    }

    /* Formats memory values for the terminal report. */
    private static String memoryInfo(MemorySnapshot memory) {
        return String.format(
            Locale.ROOT,
            "Total: %.1f GiB%nUsed: %.1f GiB (%.1f%%)",
            memory.totalKiB() / 1048576.0,
            memory.usedKiB() / 1048576.0,
            memory.usedPercent()
        );
    }

    /* Collects PCI functions and their IDs from Linux's PCI sysfs directory. */
    private static List<Pci> readPci(Map<Integer, Vendor> names)
        throws IOException {
        List<Pci> devices = new ArrayList<>();
        Path root = Path.of("/sys/bus/pci/devices");
        if (!Files.isDirectory(root)) return devices;
        try (Stream<Path> paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                // PCI sysfs names are domain:bus:device.function (or bus:device.function).
                String[] address = path.getFileName().toString().split("[:.]");
                // Skip the domain component when it is present in the sysfs address.
                int offset = address.length == 4 ? 1 : 0;
                devices.add(
                    new Pci(
                        Integer.parseInt(address[offset], 16),
                        Integer.parseInt(address[offset + 1], 16),
                        Integer.parseInt(address[offset + 2], 16),
                        readHex(path.resolve("vendor")),
                        readHex(path.resolve("device")),
                        // PCI class attributes encode base class, subclass, and programming interface.
                        readHex(path.resolve("class")),
                        vendorName(names, readHex(path.resolve("vendor"))),
                        deviceName(
                            names,
                            readHex(path.resolve("vendor")),
                            readHex(path.resolve("device"))
                        )
                    )
                );
            }
        }
        return devices;
    }

    /* Collects USB devices that expose both vendor and product IDs in sysfs. */
    private static List<Usb> readUsb(Map<Integer, Vendor> names)
        throws IOException {
        List<Usb> devices = new ArrayList<>();
        Path root = Path.of("/sys/bus/usb/devices");
        if (!Files.isDirectory(root)) return devices;
        try (Stream<Path> paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                Path vendorFile = path.resolve("idVendor"),
                    productFile = path.resolve("idProduct");
                if (
                    !Files.isRegularFile(vendorFile) ||
                    !Files.isRegularFile(productFile)
                ) continue;
                int vendor = readHex(vendorFile),
                    product = readHex(productFile);
                devices.add(
                    new Usb(
                        vendor,
                        product,
                        vendorName(names, vendor),
                        deviceName(names, vendor, product)
                    )
                );
            }
        }
        return devices;
    }

    /* Reads a hexadecimal ID from a sysfs attribute file. */
    private static int readHex(Path path) throws IOException {
        return Integer.parseInt(
            Files.readString(path).trim().replace("0x", ""),
            16
        );
    }

    /* Returns a vendor's database name, or its hexadecimal ID if unknown. */
    private static String vendorName(Map<Integer, Vendor> names, int id) {
        return names.containsKey(id) ? names.get(id).name() : hex(id);
    }

    /* Returns a product/device name, or its hexadecimal ID if unknown. */
    private static String deviceName(
        Map<Integer, Vendor> names,
        int vendor,
        int device
    ) {
        return names.containsKey(vendor)
            ? names.get(vendor).devices().getOrDefault(device, hex(device))
            : hex(device);
    }

    /* Formats an ID consistently as a four-digit hexadecimal value. */
    private static String hex(int id) {
        return String.format(Locale.ROOT, "0x%04x", id);
    }

    /* Builds the PCI device list shown on the PCI tab. */
    private static String pciInfo(List<Pci> devices) {
        StringBuilder output = new StringBuilder();
        output.append("PCI devices (").append(devices.size()).append(")\n");
        if (devices.isEmpty()) output.append("No PCI devices found.\n");
        for (Pci d : devices) {
            output.append(
                String.format(
                    Locale.ROOT,
                    "%02x:%02x.%x  %s (%s), %s (%s), class %06x%n",
                    d.bus(),
                    d.device(),
                    d.function(),
                    d.vendorName(),
                    hex(d.vendor()),
                    d.name(),
                    hex(d.id()),
                    d.deviceClass()
                )
            );
        }
        appendPciAnalysis(output, devices);
        return output.toString();
    }

    /* Builds the USB device list and handles systems without USB devices. */
    private static String usbInfo(List<Usb> devices) {
        StringBuilder output = new StringBuilder();
        output.append("USB devices (").append(devices.size()).append(")\n");
        if (devices.isEmpty()) output.append("No USB devices found.\n");
        for (Usb d : devices) {
            output.append(
                String.format(
                    "%s (%s), %s (%s)%n",
                    d.vendorName(),
                    hex(d.vendor()),
                    d.name(),
                    hex(d.id())
                )
            );
        }
        return output.toString();
    }

    /* Appends PCI vendor counts and both requested orderings to the PCI tab. */
    private static void appendPciAnalysis(
        StringBuilder output,
        List<Pci> devices
    ) {
        Comparator<Pci> address = Comparator.comparingInt(Pci::bus)
            .thenComparingInt(Pci::device)
            .thenComparingInt(Pci::function);
        Map<String, Integer> counts = new TreeMap<>(
            String.CASE_INSENSITIVE_ORDER
        );
        for (Pci d : devices) counts.merge(d.vendorName(), 1, Integer::sum);
        output.append("\nDevices per vendor:\n");
        counts.forEach((vendor, count) ->
            output
                .append("  ")
                .append(vendor)
                .append(": ")
                .append(count)
                .append('\n')
        );

        // Use bus, device, and function as deterministic tie-breakers for vendor ordering.
        output.append("\nSorted alphabetically by vendor:\n");
        devices
            .stream()
            .sorted(
                Comparator.comparing(
                    Pci::vendorName,
                    String.CASE_INSENSITIVE_ORDER
                ).thenComparing(address)
            )
            .forEach(d -> appendAddress(output, d));
        output.append("\nSorted by bus, device, and function:\n");
        devices
            .stream()
            .sorted(address)
            .forEach(d -> appendAddress(output, d));
    }

    /* Appends one PCI device and its address to an analysis section. */
    private static void appendAddress(StringBuilder output, Pci d) {
        output.append(
            String.format(
                Locale.ROOT,
                "  %s %s at %02x:%02x.%x%n",
                d.vendorName(),
                d.name(),
                d.bus(),
                d.device(),
                d.function()
            )
        );
    }

    /* Starts first collection in the command executor, never on the UI thread. */
    @Override
    public Command init() {
        return refreshCommand(previousCpuTimes);
    }

    /* Handles terminal resizing, tab navigation, scrolling, and quit input. */
    @Override
    public UpdateResult<? extends Model> update(Message message) {
        if (message instanceof RefreshMessage refresh) {
            if (refresh.snapshot() != null) {
                sections.clear();
                sections.addAll(refresh.snapshot().sections());
                previousCpuTimes = refresh.snapshot().cpuTimes();
                scroll = Math.min(scroll, maxScroll());
                refreshStatus =
                    refresh.error() == null
                        ? "Updated " + LocalTime.now().withNano(0)
                        : "JSON export failed: " +
                            refresh.error() +
                            " (last file remains unchanged)";
            } else {
                refreshStatus =
                    "Refresh failed: " +
                    refresh.error() +
                    " (showing last successful data)";
            }
            return UpdateResult.from(
                this,
                Command.tick(REFRESH_INTERVAL, ignored -> new RefreshTick())
            );
        }
        if (message instanceof RefreshTick) {
            refreshStatus = "Refreshing...";
            return UpdateResult.from(this, refreshCommand(previousCpuTimes));
        }
        if (message instanceof WindowSizeMessage size) {
            terminalWidth = Math.max(1, size.width());
            terminalHeight = Math.max(1, size.height());
            scroll = Math.min(scroll, maxScroll());
            return UpdateResult.from(this);
        }
        if (!(message instanceof KeyPressMessage key)) {
            return UpdateResult.from(this);
        }

        switch (key.key()) {
            case "q", "Q", "esc", "ctrl+c" -> {
                return UpdateResult.from(this, QuitMessage::new);
            }
            case "right", "l", "tab" -> selectTab(
                (selectedTab + 1) % TABS.length
            );
            case "left", "h" -> selectTab(
                (selectedTab + TABS.length - 1) % TABS.length
            );
            case "down", "j" -> scroll = Math.min(maxScroll(), scroll + 1);
            case "up", "k" -> scroll = Math.max(0, scroll - 1);
            case "pagedown", "pgdown", "ctrl+d" -> scroll = Math.min(
                maxScroll(),
                scroll + pageSize()
            );
            case "pageup", "pgup", "ctrl+u" -> scroll = Math.max(
                0,
                scroll - pageSize()
            );
            case "home" -> scroll = 0;
            case "end" -> scroll = maxScroll();
            default -> {
                return UpdateResult.from(this);
            }
        }
        return UpdateResult.from(this);
    }

    /* Renders the active report section within the current terminal dimensions. */
    @Override
    public String view() {
        if (
            terminalWidth < MIN_TERMINAL_WIDTH ||
            terminalHeight < MIN_TERMINAL_HEIGHT
        ) {
            return (
                TITLE.render("System Fetch") +
                "\n" +
                "Terminal too small. Resize to at least " +
                MIN_TERMINAL_WIDTH +
                "x" +
                MIN_TERMINAL_HEIGHT +
                ".\n" +
                "Press q to quit."
            );
        }

        StringBuilder output = new StringBuilder();
        output
            .append(TITLE.render("SYSTEM FETCH"))
            .append("  ")
            .append(HELP.render("LINUX SYSTEM OVERVIEW"))
            .append('\n');
        output.append(ACCENT.render("─".repeat(terminalWidth))).append('\n');
        // Short labels keep the complete tab row readable in narrower terminals.
        String[] shortTabs = { "CPU", "RAM", "PCI", "USB" };
        for (int i = 0; i < TABS.length; i++) {
            String tab =
                terminalWidth < 64
                    ? shortTabs[i]
                    : TABS[i].toUpperCase(Locale.ROOT);
            output.append(
                i == selectedTab
                    ? ACTIVE_TAB.render(" " + tab + " ")
                    : INACTIVE_TAB.render(" " + tab + " ")
            );
            output.append(i == TABS.length - 1 ? '\n' : ' ');
        }
        output.append(ACCENT.render("─".repeat(terminalWidth))).append('\n');
        output
            .append(
                SECTION_TITLE.render(TABS[selectedTab].toUpperCase(Locale.ROOT))
            )
            .append('\n');

        List<String> lines = wrappedLines();
        // Reserve rows for the title, tab rail, section heading, dividers, and key help.
        int visibleLines = Math.max(1, terminalHeight - 9);
        int start = Math.min(scroll, Math.max(0, lines.size() - visibleLines));
        int end = Math.min(lines.size(), start + visibleLines);
        for (int i = start; i < end; i++) {
            output.append(styleReportLine(lines.get(i))).append('\n');
        }
        output.append(ACCENT.render("─".repeat(terminalWidth))).append('\n');
        output.append(
            HELP.render(" ←/→ or Tab switch tabs   ↑/↓ scroll   q quit")
        ).append('\n');
        String status = refreshStatus;
        if (status.codePointCount(0, status.length()) > terminalWidth) {
            status = status.substring(0, status.offsetByCodePoints(0, terminalWidth));
        }
        output.append(HELP.render(status));
        return output.toString();
    }

    /* Collects a snapshot in the command executor and reports failures to the UI. */
    private Command refreshCommand(Map<Integer, Cpu> previousTimes) {
        return () -> {
            Snapshot snapshot;
            try {
                snapshot = collectSections(previousTimes, pciNames, usbNames);
            } catch (IOException | RuntimeException e) {
                String error = e.getMessage();
                return new RefreshMessage(
                    null,
                    error == null ? e.getClass().getSimpleName() : error
                );
            }
            if (jsonFile != null) {
                try {
                    writeJsonAtomically(jsonFile, snapshot.data());
                } catch (IOException | RuntimeException e) {
                    String error = e.getMessage();
                    return new RefreshMessage(
                        snapshot,
                        error == null ? e.getClass().getSimpleName() : error
                    );
                }
            }
            return new RefreshMessage(snapshot, null);
        };
    }

    /* Highlights headings and labels while leaving device descriptions easy to read. */
    private static String styleReportLine(String line) {
        String trimmed = line.trim();
        // PCI analysis and device-list headings share the same section-title style.
        if (
            trimmed.endsWith(":") ||
            trimmed.startsWith("PCI devices (") ||
            trimmed.startsWith("USB devices (")
        ) {
            return SECTION_TITLE.render(line);
        }
        if (trimmed.startsWith("CPU ") && trimmed.contains(":")) {
            // Highlight the CPU label without changing its measured percentage.
            int separator = line.indexOf(':');
            return (
                LABEL.render(line.substring(0, separator + 1)) +
                line.substring(separator + 1)
            );
        }
        // Keep short key/value labels distinct from longer device descriptions.
        int separator = line.indexOf(": ");
        if (separator > 0 && separator < 32) {
            return (
                LABEL.render(line.substring(0, separator + 1)) +
                line.substring(separator + 1)
            );
        }
        return line;
    }

    /* Switches tabs and resets the scroll position for the newly selected section. */
    private void selectTab(int tab) {
        selectedTab = tab;
        scroll = 0;
    }

    /* Leaves room on screen for the title, tabs, and key-help line. */
    private int pageSize() {
        return Math.max(1, terminalHeight - 11);
    }

    /* Computes the scroll limit from the active section and terminal size. */
    private int maxScroll() {
        // Match the reserved rows used to calculate the visible content area.
        return Math.max(
            0,
            wrappedLines().size() - Math.max(1, terminalHeight - 9)
        );
    }

    /* Wraps report lines so narrow terminals can still display every field. */
    private List<String> wrappedLines() {
        int width = Math.max(1, terminalWidth - 1);
        List<String> result = new ArrayList<>();
        for (String line : sections.get(selectedTab).split("\\R", -1)) {
            int start = 0;
            // Count Unicode code points so wrapping does not split a multi-byte character.
            while (line.codePointCount(start, line.length()) > width) {
                int end = line.offsetByCodePoints(start, width);
                result.add(line.substring(start, end));
                start = end;
            }
            result.add(line.substring(start));
        }
        return result;
    }
}
