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

/*
 * Prints processor, memory, PCI, and USB information from a Linux system.
 */
public class SystemFetch {

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

    /* Loads hardware-ID databases, gathers system information, and prints the report. */
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

        printProcessor();
        printMemory();

        List<Pci> pci = readPci(pciNames);
        List<Usb> usb = readUsb(usbNames);
        printPci(pci);
        printUsb(usb);
        printAnalysis(pci);
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

    /*
      //Reads the processor model and measures each logical CPU over a one-second interval.
     */
    private static void printProcessor()
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
        System.out.println("\nProcessor");
        System.out.println("  Model: " + model);
        System.out.println("  Logical CPU cores: " + cpus);

        Map<Integer, Cpu> before = cpuTimes();
        Thread.sleep(1000);
        Map<Integer, Cpu> after = cpuTimes();
        long totalBusy = 0,
            total = 0;
        System.out.println("  Load per core:");
        for (int id : before.keySet().stream().sorted().toList()) {
            Cpu start = before.get(id),
                end = after.get(id);
            if (end == null) continue;
            // Linux reports cumulative ticks; differences give the measured load.
            long busy = end.busy() - start.busy();
            long elapsed = busy + end.idle() - start.idle();
            if (elapsed <= 0) continue;
            System.out.printf(
                Locale.ROOT,
                "    CPU %d: %.1f%%%n",
                id,
                (busy * 100.0) / elapsed
            );
            totalBusy += busy;
            total += elapsed;
        }
        if (total > 0) {
            System.out.printf(
                Locale.ROOT,
                "  Average: %.1f%%%n",
                (totalBusy * 100.0) / total
            );
        }
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
    private static void printMemory() throws IOException {
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
        System.out.println("\nMemory");
        System.out.printf(
            Locale.ROOT,
            "  Total: %.1f GiB%n",
            total / 1048576.0
        );
        System.out.printf(
            Locale.ROOT,
            "  Used: %.1f GiB (%.1f%%)%n",
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

    /* Prints every PCI device with its address, IDs, resolved names, and class code. */
    private static void printPci(List<Pci> devices) {
        System.out.println("\nPCI devices (" + devices.size() + ")");
        if (devices.isEmpty()) System.out.println("  No PCI devices found.");
        for (Pci d : devices) {
            System.out.printf(
                "  %02x:%02x.%x  %s (%s), %s (%s), class %06x%n",
                d.bus(),
                d.device(),
                d.function(),
                d.vendorName(),
                hex(d.vendor()),
                d.name(),
                hex(d.id()),
                d.deviceClass()
            );
        }
    }

    /* Prints every USB device, explicitly reporting when the system has none. */
    private static void printUsb(List<Usb> devices) {
        System.out.println("\nUSB devices (" + devices.size() + ")");
        if (devices.isEmpty()) System.out.println("  No USB devices found.");
        for (Usb d : devices) {
            System.out.printf(
                "  %s (%s), %s (%s)%n",
                d.vendorName(),
                hex(d.vendor()),
                d.name(),
                hex(d.id())
            );
        }
    }

    /* Summarizes PCI vendors and prints vendor-name and numeric-address orderings. */
    private static void printAnalysis(List<Pci> devices) {
        Comparator<Pci> address = Comparator.comparingInt(Pci::bus)
            .thenComparingInt(Pci::device)
            .thenComparingInt(Pci::function);
        Map<String, Integer> counts = new TreeMap<>(
            String.CASE_INSENSITIVE_ORDER
        );
        for (Pci d : devices) counts.merge(d.vendorName(), 1, Integer::sum);
        System.out.println("\nPCI devices per vendor");
        counts.forEach((vendor, count) ->
            System.out.println("  " + vendor + ": " + count)
        );

        // Use bus, device, and function as deterministic tie-breakers for vendor ordering.
        System.out.println("\nPCI sorted by vendor");
        devices
            .stream()
            .sorted(
                Comparator.comparing(
                    Pci::vendorName,
                    String.CASE_INSENSITIVE_ORDER
                ).thenComparing(address)
            )
            .forEach(SystemFetch::printAddress);
        System.out.println("\nPCI sorted by bus, device, and function");
        devices.stream().sorted(address).forEach(SystemFetch::printAddress);
    }

    /* Prints one PCI device's name and bus:device.function address. */
    private static void printAddress(Pci d) {
        System.out.printf(
            "  %s at %02x:%02x.%x%n",
            d.vendorName() + " " + d.name(),
            d.bus(),
            d.device(),
            d.function()
        );
    }
}
