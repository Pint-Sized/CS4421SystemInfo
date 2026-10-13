import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
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

/*
 * Interactive processor, memory, PCI, and USB system information for Linux.
 */
public class SystemFetch implements Model {

    /* Report sections displayed as tabs in the interactive interface. */
    private static final String[] TABS = {"Processor", "Memory", "PCI", "USB"};
    private static final int MIN_TERMINAL_WIDTH = 40;
    private static final int MIN_TERMINAL_HEIGHT = 7;

    /* Lipgloss styles distinguish the application title and selected tab. */
    private static final Style ACTIVE_TAB = Style.newStyle()
        .foreground(Color.color("205"))
        .bold(true);
    private static final Style TITLE = Style.newStyle()
        .foreground(Color.color("39"))
        .bold(true);

    /* Cumulative busy and idle CPU ticks read from /proc/stat. */
    private record Cpu(long busy, long idle) {}

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

    /* Loads hardware IDs and system data, then runs the interactive or text report. */
    public static void main(String[] args)
        throws IOException, InterruptedException {
        Map<Integer, Vendor> pciNames = loadIds(
            "pci.ids",
            "https://pci-ids.ucw.cz/v2.2/pci.ids"
        );
        Map<Integer, Vendor> usbNames = loadIds(
            "usb.ids",
            "https://raw.githubusercontent.com/vcrhonek/hwdata/master/usb.ids"
        );

        List<Pci> pci = readPci(pciNames);
        List<Usb> usb = readUsb(usbNames);
        List<String> sections = List.of(
            processorInfo(),
            memoryInfo(),
            pciInfo(pci),
            usbInfo(usb)
        );

        if (System.console() == null || "dumb".equals(System.getenv("TERM"))) {
            printPlainReport(sections);
            return;
        }
        try {
            new Program(new SystemFetch(sections)).withAltScreen().run();
        } catch (ProgramException e) {
            System.err.println(
                "Interactive terminal unavailable; printing plain-text report."
            );
            printPlainReport(sections);
        }
    }

    /* Prints all sections sequentially when a full-screen terminal is unavailable. */
    private static void printPlainReport(List<String> sections) {
        for (int i = 0; i < sections.size(); i++) {
            System.out.println("\n" + TABS[i]);
            System.out.println(sections.get(i));
        }
    }

    /* Current report content and terminal navigation state. */
    private final List<String> sections;
    private int selectedTab;
    private int terminalWidth = 80;
    private int terminalHeight = 24;
    private int scroll;

    /* Retains the gathered report for the duration of the TUI session. */
    private SystemFetch(List<String> sections) {
        this.sections = sections;
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

    /* Reads the processor model and measures each logical CPU over a one-second interval. */
    private static String processorInfo()
        throws IOException, InterruptedException {
        String model = "Unknown";
        int cpus = 0;
        for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
            if (line.startsWith("processor")) cpus++;
            if (
                model.equals("Unknown") &&
                (line.startsWith("model name") || line.startsWith("Hardware"))
            ) {
                model = line.split(":", 2)[1].trim();
            }
        }
        StringBuilder output = new StringBuilder();
        output.append("Model: ").append(model).append('\n');
        output.append("Logical CPU cores: ").append(cpus).append('\n');

        Map<Integer, Cpu> before = cpuTimes();
        Thread.sleep(1000);
        Map<Integer, Cpu> after = cpuTimes();
        long totalBusy = 0,
            total = 0;
        output.append("Load per core:\n");
        for (int id : before.keySet().stream().sorted().toList()) {
            Cpu start = before.get(id),
                end = after.get(id);
            if (end == null) continue;
            // Linux reports cumulative ticks; differences give the measured load.
            long busy = end.busy() - start.busy();
            long elapsed = busy + end.idle() - start.idle();
            if (elapsed <= 0) continue;
            output.append(String.format(
                Locale.ROOT,
                "  CPU %d: %.1f%%%n",
                id,
                (busy * 100.0) / elapsed
            ));
            totalBusy += busy;
            total += elapsed;
        }
        if (total > 0) {
            output.append(String.format(
                Locale.ROOT,
                "Average across all logical CPUs: %.1f%%%n",
                (totalBusy * 100.0) / total
            ));
        }
        return output.toString();
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
            long total = 0;
            // Sum user, system, idle, and other counters to get all elapsed CPU ticks.
            for (int i = 1; i < fields.length; i++) total += Long.parseLong(
                fields[i]
            );
            // Include I/O wait in idle time, as Linux does when reporting CPU load.
            long idle = Long.parseLong(fields[4]);
            if (fields.length > 5) idle += Long.parseLong(fields[5]);
            result.put(id, new Cpu(total - idle, idle));
        }
        return result;
    }

    /* Reports total and used memory from Linux's /proc/meminfo values, which are in KiB. */
    private static String memoryInfo() throws IOException {
        Map<String, Long> memory = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
            String[] fields = line.split("\\s+");
            if (fields.length > 1) memory.put(
                fields[0].replace(":", ""),
                Long.parseLong(fields[1])
            );
        }
        long total = memory.get("MemTotal");
        // /proc/meminfo values are in KiB; MemAvailable estimates reclaimable memory.
        long used =
            total - memory.getOrDefault("MemAvailable", memory.get("MemFree"));
        return String.format(
            Locale.ROOT,
            "Total: %.1f GiB%nUsed: %.1f GiB (%.1f%%)",
            total / 1048576.0,
            used / 1048576.0,
            (used * 100.0) / total
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
            output.append(String.format(
                Locale.ROOT,
                "%02x:%02x.%x  %s (%s), %s (%s), class %06x%n",
                d.bus(), d.device(), d.function(), d.vendorName(), hex(d.vendor()),
                d.name(), hex(d.id()), d.deviceClass()
            ));
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
            output.append(String.format(
                "%s (%s), %s (%s)%n",
                d.vendorName(), hex(d.vendor()), d.name(), hex(d.id())
            ));
        }
        return output.toString();
    }

    /* Appends PCI vendor counts and both requested orderings to the PCI tab. */
    private static void appendPciAnalysis(StringBuilder output, List<Pci> devices) {
        Comparator<Pci> address = Comparator.comparingInt(Pci::bus)
            .thenComparingInt(Pci::device)
            .thenComparingInt(Pci::function);
        Map<String, Integer> counts = new TreeMap<>(
            String.CASE_INSENSITIVE_ORDER
        );
        for (Pci d : devices) counts.merge(d.vendorName(), 1, Integer::sum);
        output.append("\nDevices per vendor:\n");
        counts.forEach((vendor, count) ->
            output.append("  ").append(vendor).append(": ").append(count).append('\n')
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
        devices.stream().sorted(address).forEach(d -> appendAddress(output, d));
    }

    /* Appends one PCI device and its address to an analysis section. */
    private static void appendAddress(StringBuilder output, Pci d) {
        output.append(String.format(
            Locale.ROOT,
            "  %s %s at %02x:%02x.%x%n",
            d.vendorName(), d.name(), d.bus(), d.device(), d.function()
        ));
    }

    /* No startup command is needed because the report is gathered before the TUI starts. */
    @Override
    public Command init() {
        return null;
    }

    /* Handles terminal resizing, tab navigation, scrolling, and quit input. */
    @Override
    public UpdateResult<? extends Model> update(Message message) {
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
            case "right", "l", "tab" -> selectTab((selectedTab + 1) % TABS.length);
            case "left", "h" -> selectTab((selectedTab + TABS.length - 1) % TABS.length);
            case "down", "j" -> scroll = Math.min(maxScroll(), scroll + 1);
            case "up", "k" -> scroll = Math.max(0, scroll - 1);
            case "pagedown", "pgdown", "ctrl+d" ->
                scroll = Math.min(maxScroll(), scroll + pageSize());
            case "pageup", "pgup", "ctrl+u" ->
                scroll = Math.max(0, scroll - pageSize());
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
            return TITLE.render("System Fetch") + "\n"
                + "Terminal too small. Resize to at least "
                + MIN_TERMINAL_WIDTH
                + "x"
                + MIN_TERMINAL_HEIGHT
                + ".\n"
                + "Press q to quit.";
        }

        StringBuilder output = new StringBuilder();
        output.append(TITLE.render("System Fetch")).append('\n');
        for (int i = 0; i < TABS.length; i++) {
            String tab = " " + TABS[i] + " ";
            output.append(i == selectedTab ? ACTIVE_TAB.render("[" + tab + "]") : "[" + tab + "]");
            output.append(i == TABS.length - 1 ? '\n' : ' ');
        }

        List<String> lines = wrappedLines();
        int visibleLines = Math.max(1, terminalHeight - 5);
        int start = Math.min(scroll, Math.max(0, lines.size() - visibleLines));
        int end = Math.min(lines.size(), start + visibleLines);
        for (int i = start; i < end; i++) {
            output.append(lines.get(i)).append('\n');
        }
        output.append("\n←/→ or Tab: switch tabs | ↑/↓: scroll | q: quit");
        return output.toString();
    }

    /* Switches tabs and resets the scroll position for the newly selected section. */
    private void selectTab(int tab) {
        selectedTab = tab;
        scroll = 0;
    }

    /* Leaves room on screen for the title, tabs, and key-help line. */
    private int pageSize() {
        return Math.max(1, terminalHeight - 7);
    }

    /* Computes the scroll limit from the active section and terminal size. */
    private int maxScroll() {
        return Math.max(0, wrappedLines().size() - Math.max(1, terminalHeight - 5));
    }

    /* Wraps report lines so narrow terminals can still display every field. */
    private List<String> wrappedLines() {
        int width = Math.max(1, terminalWidth - 1);
        List<String> result = new ArrayList<>();
        for (String line : sections.get(selectedTab).split("\\R", -1)) {
            int start = 0;
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
