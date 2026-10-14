import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/* Reads Linux procfs/sysfs data and derives the structured system snapshot. */
final class SystemInfoCollector {

    /* Vendor/product labels used to make PCI and USB IDs human-readable. */
    record Vendor(String name, Map<Integer, String> devices) {}

    /* CPU identity and per-logical-CPU physical topology mapping. */
    private record CpuInfo(
        String model,
        int logicalCpus,
        Map<Integer, String> coreByCpu,
        boolean topologyAvailable
    ) {}

    private final Map<Integer, Vendor> pciNames;
    private final Map<Integer, Vendor> usbNames;

    SystemInfoCollector(
        Map<Integer, Vendor> pciNames,
        Map<Integer, Vendor> usbNames
    ) {
        this.pciNames = pciNames;
        this.usbNames = usbNames;
    }

    /* Loads an ID database from known local locations or downloads and caches it. */
    static Map<Integer, Vendor> loadIds(String filename, String url)
        throws IOException, InterruptedException {
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
                !response.body().matches(
                    "(?s).*?\\n?[0-9a-fA-F]{4}\\s+\\S.*"
                )
            ) {
                // Ignore error pages and malformed downloads; raw IDs remain usable.
                System.out.println(
                    "Could not download " + filename + "; displaying raw IDs."
                );
                return Map.of();
            }
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

    SystemSnapshot.CollectionResult collect(
        Map<Integer, SystemSnapshot.CpuTicks> previousCpuTimes
    )
        throws IOException {
        // Read counters once per refresh; the prior sample is passed in by the TUI.
        CpuInfo info = readCpuInfo();
        Map<Integer, SystemSnapshot.CpuTicks> currentCpuTimes = cpuTimes();
        SystemSnapshot.Processor processor = processorSnapshot(
            info,
            previousCpuTimes,
            currentCpuTimes
        );
        SystemSnapshot.Memory memory = memorySnapshot();
        List<SystemSnapshot.PciDevice> pci = readPci(pciNames);
        List<SystemSnapshot.UsbDevice> usb = readUsb(usbNames);
        return new SystemSnapshot.CollectionResult(
            new SystemSnapshot(processor, memory, pci, usb),
            currentCpuTimes
        );
    }

    /* Parses vendor records and their indented device/product entries. */
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
                vendors.get(vendorId).devices().putIfAbsent(id, parts[1]);
            }
        }
        return vendors;
    }

    /* Converts counter deltas to per-logical-CPU and per-physical-core percentages. */
    private static SystemSnapshot.Processor processorSnapshot(
        CpuInfo info,
        Map<Integer, SystemSnapshot.CpuTicks> before,
        Map<Integer, SystemSnapshot.CpuTicks> after
    ) {
        List<SystemSnapshot.CpuLoad> logicalLoads = new ArrayList<>();
        Map<String, SystemSnapshot.CpuTicks> physicalCoreTimes = new TreeMap<>();
        for (int id : after.keySet().stream().sorted().toList()) {
            SystemSnapshot.CpuTicks start = before.get(id), end = after.get(id);
            // Skip new CPUs and counter resets until a valid interval is available.
            if (start == null) continue;
            long busy = end.busy() - start.busy();
            long elapsed = busy + end.idle() - start.idle();
            if (elapsed <= 0 || busy < 0) continue;
            logicalLoads.add(
                new SystemSnapshot.CpuLoad(id, busy * 100.0 / elapsed)
            );
            String core = info.coreByCpu().get(id);
            // Summed SMT sibling counters represent utilization of the physical core.
            if (core != null) {
                physicalCoreTimes.merge(
                    core,
                    new SystemSnapshot.CpuTicks(busy, elapsed - busy),
                    (first, second) ->
                        new SystemSnapshot.CpuTicks(
                            first.busy() + second.busy(),
                            first.idle() + second.idle()
                        )
                );
            }
        }
        List<SystemSnapshot.CoreLoad> physicalLoads = physicalCoreTimes.entrySet()
            .stream()
            .map(entry -> {
                SystemSnapshot.CpuTicks times = entry.getValue();
                long elapsed = times.busy() + times.idle();
                return elapsed <= 0
                    ? null
                    : new SystemSnapshot.CoreLoad(
                        entry.getKey(),
                        times.busy() * 100.0 / elapsed
                    );
            })
            .filter(java.util.Objects::nonNull)
            .toList();
        // Null distinguishes unavailable topology from a known zero-core machine.
        Integer physicalCores = info.topologyAvailable()
            ? (int) info.coreByCpu().values().stream().distinct().count()
            : null;
        return new SystemSnapshot.Processor(
            info.model(),
            info.logicalCpus(),
            physicalCores,
            info.topologyAvailable(),
            info.topologyAvailable() &&
                info.coreByCpu()
                    .values()
                    .stream()
                    .noneMatch(name -> name.startsWith("CPU ")),
            List.copyOf(logicalLoads),
            averageLoad(logicalLoads.stream()
                .map(SystemSnapshot.CpuLoad::utilization)
                .toList()),
            physicalLoads,
            averageLoad(physicalLoads.stream()
                .map(SystemSnapshot.CoreLoad::utilization)
                .toList())
        );
    }

    /* Returns null until any comparable load sample exists. */
    private static Double averageLoad(List<Double> loads) {
        return loads.isEmpty()
            ? null
            : loads.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    /* Reads processor blocks and builds socket/core mappings from /proc/cpuinfo. */
    private static CpuInfo readCpuInfo() throws IOException {
        String model = "Unknown";
        int logicalCpus = 0;
        Map<Integer, String> coreByCpu = new HashMap<>();
        Set<Integer> logicalCpuIds = new HashSet<>();
        Integer processor = null, physicalId = null, coreId = null;

        for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
            if (line.isBlank()) {
                // Some architectures omit a final blank separator after the last CPU block.
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
            for (int id = 0; id < logicalCpus; id++) logicalCpuIds.add(id);
        }
        // Do not invent physical topology for logical CPUs lacking socket/core IDs.
        boolean topologyAvailable = !coreByCpu.isEmpty();
        for (int id : logicalCpuIds) {
            if (!coreByCpu.containsKey(id)) coreByCpu.put(id, "CPU " + id);
        }
        return new CpuInfo(model, logicalCpus, coreByCpu, topologyAvailable);
    }

    /* Adds a mapping only when all three topology fields are present. */
    private static void addCoreMapping(
        Map<Integer, String> coreByCpu,
        Integer processor,
        Integer physicalId,
        Integer coreId
    ) {
        if (processor == null || physicalId == null || coreId == null) return;
        coreByCpu.put(processor, "Socket " + physicalId + ", core " + coreId);
    }

    /* Reads per-logical-CPU cumulative counters, excluding the aggregate cpu row. */
    private static Map<Integer, SystemSnapshot.CpuTicks> cpuTimes()
        throws IOException {
        Map<Integer, SystemSnapshot.CpuTicks> result = new HashMap<>();
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
            // Guest ticks are included in user/nice, so avoid double-counting them.
            result.put(id, new SystemSnapshot.CpuTicks(busy, idle));
        }
        return result;
    }

    /* Uses MemAvailable as the estimate of memory currently available to workloads. */
    private static SystemSnapshot.Memory memorySnapshot() throws IOException {
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
        return new SystemSnapshot.Memory(total, used, (used * 100.0) / total);
    }

    /* Resolves PCI addresses and IDs from Linux's PCI sysfs directory. */
    private static List<SystemSnapshot.PciDevice> readPci(
        Map<Integer, Vendor> names
    )
        throws IOException {
        List<SystemSnapshot.PciDevice> devices = new ArrayList<>();
        Path root = Path.of("/sys/bus/pci/devices");
        if (!Files.isDirectory(root)) return devices;
        try (Stream<Path> paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                String[] address = path.getFileName().toString().split("[:.]");
                // Sysfs names may include a domain before bus:device.function.
                int offset = address.length == 4 ? 1 : 0;
                int vendor = readHex(path.resolve("vendor"));
                int device = readHex(path.resolve("device"));
                devices.add(
                    new SystemSnapshot.PciDevice(
                        Integer.parseInt(address[offset], 16),
                        Integer.parseInt(address[offset + 1], 16),
                        Integer.parseInt(address[offset + 2], 16),
                        vendor,
                        device,
                        readHex(path.resolve("class")),
                        vendorName(names, vendor),
                        deviceName(names, vendor, device)
                    )
                );
            }
        }
        return devices;
    }

    /* Collects connected USB devices exposing vendor and product IDs. */
    private static List<SystemSnapshot.UsbDevice> readUsb(
        Map<Integer, Vendor> names
    )
        throws IOException {
        List<SystemSnapshot.UsbDevice> devices = new ArrayList<>();
        Path root = Path.of("/sys/bus/usb/devices");
        if (!Files.isDirectory(root)) return devices;
        try (Stream<Path> paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                Path vendorFile = path.resolve("idVendor");
                Path productFile = path.resolve("idProduct");
                // USB interface entries without device IDs are not USB devices.
                if (
                    !Files.isRegularFile(vendorFile) ||
                    !Files.isRegularFile(productFile)
                ) continue;
                int vendor = readHex(vendorFile);
                int product = readHex(productFile);
                devices.add(
                    new SystemSnapshot.UsbDevice(
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

    /* Reads sysfs hexadecimal attributes such as vendor and product IDs. */
    private static int readHex(Path path) throws IOException {
        return Integer.parseInt(
            Files.readString(path).trim().replace("0x", ""),
            16
        );
    }

    /* Looks up a vendor label or falls back to its raw hexadecimal ID. */
    private static String vendorName(Map<Integer, Vendor> names, int id) {
        return names.containsKey(id) ? names.get(id).name() : hex(id);
    }

    /* Looks up a product label under its vendor, with a raw-ID fallback. */
    private static String deviceName(
        Map<Integer, Vendor> names,
        int vendor,
        int device
    ) {
        return names.containsKey(vendor)
            ? names.get(vendor).devices().getOrDefault(device, hex(device))
            : hex(device);
    }

    /* Standardizes IDs for terminal labels and lookup fallbacks. */
    private static String hex(int id) {
        return String.format(Locale.ROOT, "0x%04x", id);
    }
}
