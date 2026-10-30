import java.io.IOException; /* Imports java.io.IOException for use in this file. */
import java.net.URI; /* Imports java.net.URI for use in this file. */
import java.net.http.HttpClient; /* Imports java.net.http.HttpClient for use in this file. */
import java.net.http.HttpRequest; /* Imports java.net.http.HttpRequest for use in this file. */
import java.net.http.HttpResponse; /* Imports java.net.http.HttpResponse for use in this file. */
import java.nio.file.Files; /* Imports java.nio.file.Files for use in this file. */
import java.nio.file.Path; /* Imports java.nio.file.Path for use in this file. */
import java.nio.file.FileStore; /* Imports java.nio.file.FileStore for use in this file. */
import java.time.Duration; /* Imports java.time.Duration for use in this file. */
import java.util.ArrayList; /* Imports java.util.ArrayList for use in this file. */
import java.util.HashMap; /* Imports java.util.HashMap for use in this file. */
import java.util.HashSet; /* Imports java.util.HashSet for use in this file. */
import java.util.List; /* Imports java.util.List for use in this file. */
import java.util.Locale; /* Imports java.util.Locale for use in this file. */
import java.util.Map; /* Imports java.util.Map for use in this file. */
import java.util.Set; /* Imports java.util.Set for use in this file. */
import java.util.TreeMap; /* Imports java.util.TreeMap for use in this file. */
import java.util.stream.Stream; /* Imports java.util.stream.Stream for use in this file. */
/* Reads Linux procfs/sysfs data and derives the structured system snapshot. */
final class SystemInfoCollector { /* Continues the current operation. */

    /* Vendor/product labels used to make PCI and USB IDs human-readable. */
    record Vendor(String name, Map<Integer, String> devices) {} /* Declares the Vendor method. */

    /* CPU identity and per-logical-CPU physical topology mapping. */
    private record CpuInfo( /* Declares the CpuInfo method. */
        String model, /* Provides the next part of the current expression. */
        int logicalCpus, /* Provides the next part of the current expression. */
        Map<Integer, String> coreByCpu, /* Provides the next part of the current expression. */
        boolean topologyAvailable /* Continues the current operation. */
    ) {} /* Provides the next part of the current expression. */

    private final Map<Integer, Vendor> pciNames; /* Continues the current operation. */
    private final Map<Integer, Vendor> usbNames; /* Continues the current operation. */

    SystemInfoCollector( /* Calls this operation with the values shown. */
        Map<Integer, Vendor> pciNames, /* Provides the next part of the current expression. */
        Map<Integer, Vendor> usbNames /* Continues the current operation. */
    ) { /* Provides the next part of the current expression. */
        this.pciNames = pciNames; /* Sets or updates pciNames. */
        this.usbNames = usbNames; /* Sets or updates usbNames. */
		} /* This brace ends a code block. */

    /* Loads an ID database from known local locations or downloads and caches it. */
    static Map<Integer, Vendor> loadIds(String filename, String url) /* Declares the loadIds method. */
        throws IOException, InterruptedException { /* Continues the current operation. */
        for (Path file : List.of( /* Repeats this work for each matching value. */
            Path.of(filename), /* Uses the named Java API to perform this operation. */
            Path.of("data", filename), /* Uses the named Java API to perform this operation. */
            Path.of("ids", filename), /* Uses the named Java API to perform this operation. */
            Path.of("/usr/share/hwdata", filename), /* Uses the named Java API to perform this operation. */
            Path.of("/usr/share/misc", filename) /* Uses the named Java API to perform this operation. */
        )) { /* Provides the next part of the current expression. */
            if (Files.isRegularFile(file)) return readIds(file); /* Checks this condition before choosing what to do. */
		} /* This brace ends a code block. */

        try { /* Starts work that may need error handling or resource cleanup. */
            HttpClient client = HttpClient.newBuilder() /* Stores the calculated value in client. */
                .connectTimeout(Duration.ofSeconds(5)) /* Provides the next part of the current expression. */
                .build(); /* Provides the next part of the current expression. */
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)) /* Stores the calculated value in request. */
                .timeout(Duration.ofSeconds(15)) /* Provides the next part of the current expression. */
                .GET() /* Provides the next part of the current expression. */
                .build(); /* Provides the next part of the current expression. */
            HttpResponse<String> response = client.send( /* Stores the calculated value in response. */
                request, /* Provides the next part of the current expression. */
                HttpResponse.BodyHandlers.ofString() /* Calls this operation with the values shown. */
            ); /* Provides the next part of the current expression. */
            if ( /* Checks this condition before choosing what to do. */
                response.statusCode() != 200 || /* Provides the next part of the current expression. */
                !response.body().matches( /* Calls this operation with the values shown. */
                    "(?s).*?\\n?[0-9a-fA-F]{4}\\s+\\S.*" /* Calls this operation with the values shown. */
                ) /* Provides the next part of the current expression. */
            ) { /* Provides the next part of the current expression. */
                // Ignore error pages and malformed downloads; raw IDs remain usable.
                System.out.println( /* Uses the named Java API to perform this operation. */
                    "Could not download " + filename + "; displaying raw IDs." /* Continues the current operation. */
                ); /* Provides the next part of the current expression. */
                return Map.of(); /* Returns this value to the caller. */
		} /* This brace ends a code block. */
            Path file = Path.of("data", filename); /* Stores the calculated value in file. */
            Files.createDirectories(file.getParent()); /* Uses the named Java API to perform this operation. */
            Files.writeString(file, response.body()); /* Uses the named Java API to perform this operation. */
            System.out.println("Downloaded " + filename + " to " + file); /* Uses the named Java API to perform this operation. */
            return readIds(file); /* Returns this value to the caller. */
        } catch (IOException e) { /* Provides the next part of the current expression. */
            System.out.println( /* Uses the named Java API to perform this operation. */
                "Could not obtain " + /* Provides the next part of the current expression. */
                    filename + /* Provides the next part of the current expression. */
                    ": " + /* Provides the next part of the current expression. */
                    e.getMessage() + /* Provides the next part of the current expression. */
                    "; displaying raw IDs." /* Continues the current operation. */
            ); /* Provides the next part of the current expression. */
            return Map.of(); /* Returns this value to the caller. */
		} /* This brace ends a code block. */
		} /* This brace ends a code block. */

    SystemSnapshot.CollectionResult collect( /* Declares the collect method. */
        Map<Integer, SystemSnapshot.CpuTicks> previousCpuTimes /* Continues the current operation. */
    ) /* Provides the next part of the current expression. */
        throws IOException { /* Continues the current operation. */
        // Read counters once per refresh; the prior sample is passed in by the TUI.
        CpuInfo info = readCpuInfo(); /* Stores the calculated value in info. */
        Map<Integer, SystemSnapshot.CpuTicks> currentCpuTimes = cpuTimes(); /* Stores the calculated value in currentCpuTimes. */
        SystemSnapshot.Processor processor = processorSnapshot( /* Stores the calculated value in processor. */
            info, /* Provides the next part of the current expression. */
            previousCpuTimes, /* Provides the next part of the current expression. */
            currentCpuTimes /* Continues the current operation. */
        ); /* Provides the next part of the current expression. */
        SystemSnapshot.Memory memory = memorySnapshot(); /* Stores the calculated value in memory. */
        List<SystemSnapshot.PciDevice> pci = readPci(pciNames); /* Stores the calculated value in pci. */
        List<SystemSnapshot.UsbDevice> usb = readUsb(usbNames); /* Stores the calculated value in usb. */
        List<SystemSnapshot.Disk> disks = readDisks(); /* Stores the calculated value in disks. */
	 return new SystemSnapshot.CollectionResult( /* Returns this value to the caller. */
            new SystemSnapshot(processor, memory, pci, usb, disks), /* Creates a new value for the caller or current operation. */
            currentCpuTimes /* Continues the current operation. */
        ); /* Provides the next part of the current expression. */
		} /* This brace ends a code block. */

    /* Parses vendor records and their indented device/product entries. */
    private static Map<Integer, Vendor> readIds(Path file) throws IOException { /* Declares the readIds method. */
        Map<Integer, Vendor> vendors = new HashMap<>(); /* Stores the calculated value in vendors. */
        int vendorId = -1; /* Stores the calculated value in vendorId. */
        for (String line : Files.readAllLines(file)) { /* Repeats this work for each matching value. */
            String trimmed = line.strip(); /* Stores the calculated value in trimmed. */
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue; /* Checks this condition before choosing what to do. */
            String[] parts = trimmed.split("\\s+", 2); /* Stores the calculated value in parts. */
            if ( /* Checks this condition before choosing what to do. */
                parts.length < 2 || !parts[0].matches("[0-9a-fA-F]{4}") /* Calls this operation with the values shown. */
            ) continue; /* Provides the next part of the current expression. */
            int id = Integer.parseInt(parts[0], 16); /* Stores the calculated value in id. */
            if (!Character.isWhitespace(line.charAt(0))) { /* Checks this condition before choosing what to do. */
                vendorId = id; /* Sets or updates vendorId. */
                vendors.put(vendorId, new Vendor(parts[1], new HashMap<>())); /* Calls this operation with the values shown. */
            } else if (vendorId >= 0 && line.charAt(0) == '\t') { /* Provides the next part of the current expression. */
                vendors.get(vendorId).devices().putIfAbsent(id, parts[1]); /* Calls this operation with the values shown. */
		} /* This brace ends a code block. */
		} /* This brace ends a code block. */
        return vendors; /* Returns this value to the caller. */
		} /* This brace ends a code block. */

    /* Converts counter deltas to per-logical-CPU and per-physical-core percentages. */
    private static SystemSnapshot.Processor processorSnapshot( /* Declares the processorSnapshot method. */
        CpuInfo info, /* Provides the next part of the current expression. */
        Map<Integer, SystemSnapshot.CpuTicks> before, /* Provides the next part of the current expression. */
        Map<Integer, SystemSnapshot.CpuTicks> after /* Continues the current operation. */
    ) { /* Provides the next part of the current expression. */
        List<SystemSnapshot.CpuLoad> logicalLoads = new ArrayList<>(); /* Stores the calculated value in logicalLoads. */
        Map<String, SystemSnapshot.CpuTicks> physicalCoreTimes = new TreeMap<>(); /* Stores the calculated value in physicalCoreTimes. */
        for (int id : after.keySet().stream().sorted().toList()) { /* Repeats this work for each matching value. */
            SystemSnapshot.CpuTicks start = before.get(id), end = after.get(id); /* Stores the calculated value in start. */
            // Skip new CPUs and counter resets until a valid interval is available.
            if (start == null) continue; /* Checks this condition before choosing what to do. */
            long busy = end.busy() - start.busy(); /* Stores the calculated value in busy. */
            long elapsed = busy + end.idle() - start.idle(); /* Stores the calculated value in elapsed. */
            if (elapsed <= 0 || busy < 0) continue; /* Checks this condition before choosing what to do. */
            logicalLoads.add( /* Calls this operation with the values shown. */
                new SystemSnapshot.CpuLoad(id, busy * 100.0 / elapsed) /* Creates a new value for the caller or current operation. */
            ); /* Provides the next part of the current expression. */
            String core = info.coreByCpu().get(id); /* Stores the calculated value in core. */
            // Summed SMT sibling counters represent utilization of the physical core.
            if (core != null) { /* Checks this condition before choosing what to do. */
                physicalCoreTimes.merge( /* Calls this operation with the values shown. */
                    core, /* Provides the next part of the current expression. */
                    new SystemSnapshot.CpuTicks(busy, elapsed - busy), /* Creates a new value for the caller or current operation. */
                    (first, second) -> /* Defines the work for this case or callback. */
                        new SystemSnapshot.CpuTicks( /* Creates a new value for the caller or current operation. */
                            first.busy() + second.busy(), /* Provides the next part of the current expression. */
                            first.idle() + second.idle() /* Calls this operation with the values shown. */
                        ) /* Provides the next part of the current expression. */
                ); /* Provides the next part of the current expression. */
		} /* This brace ends a code block. */
		} /* This brace ends a code block. */
        List<SystemSnapshot.CoreLoad> physicalLoads = physicalCoreTimes.entrySet() /* Stores the calculated value in physicalLoads. */
            .stream() /* Provides the next part of the current expression. */
            .map(entry -> { /* Defines the work for this case or callback. */
                SystemSnapshot.CpuTicks times = entry.getValue(); /* Stores the calculated value in times. */
                long elapsed = times.busy() + times.idle(); /* Stores the calculated value in elapsed. */
                return elapsed <= 0 /* Returns this value to the caller. */
                    ? null /* Continues the current operation. */
                    : new SystemSnapshot.CoreLoad( /* Calls this operation with the values shown. */
                        entry.getKey(), /* Provides the next part of the current expression. */
                        times.busy() * 100.0 / elapsed /* Calls this operation with the values shown. */
                    ); /* Provides the next part of the current expression. */
            }) /* Provides the next part of the current expression. */
            .filter(java.util.Objects::nonNull) /* Provides the next part of the current expression. */
            .toList(); /* Provides the next part of the current expression. */
        // Null distinguishes unavailable topology from a known zero-core machine.
        Integer physicalCores = info.topologyAvailable() /* Stores the calculated value in physicalCores. */
            ? (int) info.coreByCpu().values().stream().distinct().count() /* Calls this operation with the values shown. */
            : null; /* Continues the current operation. */
        return new SystemSnapshot.Processor( /* Returns this value to the caller. */
            info.model(), /* Provides the next part of the current expression. */
            info.logicalCpus(), /* Provides the next part of the current expression. */
            physicalCores, /* Provides the next part of the current expression. */
            info.topologyAvailable(), /* Provides the next part of the current expression. */
            info.topologyAvailable() && /* Provides the next part of the current expression. */
                info.coreByCpu() /* Calls this operation with the values shown. */
                    .values() /* Provides the next part of the current expression. */
                    .stream() /* Provides the next part of the current expression. */
                    .noneMatch(name -> name.startsWith("CPU ")), /* Defines the work for this case or callback. */
            List.copyOf(logicalLoads), /* Provides the next part of the current expression. */
            averageLoad(logicalLoads.stream() /* Calls this operation with the values shown. */
                .map(SystemSnapshot.CpuLoad::utilization) /* Provides the next part of the current expression. */
                .toList()), /* Provides the next part of the current expression. */
            physicalLoads, /* Provides the next part of the current expression. */
            averageLoad(physicalLoads.stream() /* Calls this operation with the values shown. */
                .map(SystemSnapshot.CoreLoad::utilization) /* Provides the next part of the current expression. */
                .toList()) /* Provides the next part of the current expression. */
        ); /* Provides the next part of the current expression. */
		} /* This brace ends a code block. */

    /* Returns null until any comparable load sample exists. */
    private static Double averageLoad(List<Double> loads) { /* Declares the averageLoad method. */
        return loads.isEmpty() /* Returns this value to the caller. */
            ? null /* Continues the current operation. */
            : loads.stream().mapToDouble(Double::doubleValue).average().orElseThrow(); /* Calls this operation with the values shown. */
		} /* This brace ends a code block. */

    /* Reads processor blocks and builds socket/core mappings from /proc/cpuinfo. */
    private static CpuInfo readCpuInfo() throws IOException { /* Declares the readCpuInfo method. */
        String model = "Unknown"; /* Stores the calculated value in model. */
        int logicalCpus = 0; /* Stores the calculated value in logicalCpus. */
        Map<Integer, String> coreByCpu = new HashMap<>(); /* Stores the calculated value in coreByCpu. */
        Set<Integer> logicalCpuIds = new HashSet<>(); /* Stores the calculated value in logicalCpuIds. */
        Integer processor = null, physicalId = null, coreId = null; /* Stores the calculated value in processor. */

        for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) { /* Repeats this work for each matching value. */
            if (line.isBlank()) { /* Checks this condition before choosing what to do. */
                // Some architectures omit a final blank separator after the last CPU block.
                addCoreMapping(coreByCpu, processor, physicalId, coreId); /* Calls this operation with the values shown. */
                processor = null; /* Sets or updates processor. */
                physicalId = null; /* Sets or updates physicalId. */
                coreId = null; /* Sets or updates coreId. */
                continue; /* Changes which loop iteration runs next. */
		} /* This brace ends a code block. */
            String[] fields = line.split(":", 2); /* Stores the calculated value in fields. */
            if (fields.length != 2) continue; /* Checks this condition before choosing what to do. */
            String key = fields[0].trim(), value = fields[1].trim(); /* Stores the calculated value in key. */
            if (key.equals("processor")) { /* Checks this condition before choosing what to do. */
                processor = Integer.parseInt(value); /* Sets or updates processor. */
                logicalCpus++; /* Continues the current operation. */
                logicalCpuIds.add(processor); /* Calls this operation with the values shown. */
            } else if (key.equals("physical id")) { /* Provides the next part of the current expression. */
                physicalId = Integer.parseInt(value); /* Sets or updates physicalId. */
            } else if (key.equals("core id")) { /* Provides the next part of the current expression. */
                coreId = Integer.parseInt(value); /* Sets or updates coreId. */
            } else if ( /* Provides the next part of the current expression. */
                model.equals("Unknown") && /* Provides the next part of the current expression. */
                (key.equals("model name") || key.equals("Hardware")) /* Calls this operation with the values shown. */
            ) { /* Provides the next part of the current expression. */
                model = value; /* Sets or updates model. */
		} /* This brace ends a code block. */
		} /* This brace ends a code block. */
        addCoreMapping(coreByCpu, processor, physicalId, coreId); /* Calls this operation with the values shown. */

        if (logicalCpus == 0) { /* Checks this condition before choosing what to do. */
            logicalCpus = Runtime.getRuntime().availableProcessors(); /* Sets or updates logicalCpus. */
            for (int id = 0; id < logicalCpus; id++) logicalCpuIds.add(id); /* Repeats this work for each matching value. */
		} /* This brace ends a code block. */
        // Do not invent physical topology for logical CPUs lacking socket/core IDs.
        boolean topologyAvailable = !coreByCpu.isEmpty(); /* Stores the calculated value in topologyAvailable. */
        for (int id : logicalCpuIds) { /* Repeats this work for each matching value. */
            if (!coreByCpu.containsKey(id)) coreByCpu.put(id, "CPU " + id); /* Checks this condition before choosing what to do. */
		} /* This brace ends a code block. */
        return new CpuInfo(model, logicalCpus, coreByCpu, topologyAvailable); /* Returns this value to the caller. */
		} /* This brace ends a code block. */

    /* Adds a mapping only when all three topology fields are present. */
    private static void addCoreMapping( /* Declares the addCoreMapping method. */
        Map<Integer, String> coreByCpu, /* Provides the next part of the current expression. */
        Integer processor, /* Provides the next part of the current expression. */
        Integer physicalId, /* Provides the next part of the current expression. */
        Integer coreId /* Continues the current operation. */
    ) { /* Provides the next part of the current expression. */
        if (processor == null || physicalId == null || coreId == null) return; /* Checks this condition before choosing what to do. */
        coreByCpu.put(processor, "Socket " + physicalId + ", core " + coreId); /* Calls this operation with the values shown. */
		} /* This brace ends a code block. */

    /* Reads per-logical-CPU cumulative counters, excluding the aggregate cpu row. */
    private static Map<Integer, SystemSnapshot.CpuTicks> cpuTimes() /* Declares the cpuTimes method. */
        throws IOException { /* Continues the current operation. */
        Map<Integer, SystemSnapshot.CpuTicks> result = new HashMap<>(); /* Stores the calculated value in result. */
        for (String line : Files.readAllLines(Path.of("/proc/stat"))) { /* Repeats this work for each matching value. */
            String[] fields = line.trim().split("\\s+"); /* Stores the calculated value in fields. */
            if (!fields[0].matches("cpu\\d+")) continue; /* Checks this condition before choosing what to do. */
            int id = Integer.parseInt(fields[0].substring(3)); /* Stores the calculated value in id. */
            long idle = Long.parseLong(fields[4]); /* Stores the calculated value in idle. */
            if (fields.length > 5) idle += Long.parseLong(fields[5]); /* Checks this condition before choosing what to do. */
            long busy = Long.parseLong(fields[1]) /* Stores the calculated value in busy. */
                + Long.parseLong(fields[2]) /* Calls this operation with the values shown. */
                + Long.parseLong(fields[3]) /* Calls this operation with the values shown. */
                + Long.parseLong(fields[6]) /* Calls this operation with the values shown. */
                + Long.parseLong(fields[7]); /* Calls this operation with the values shown. */
            if (fields.length > 8) busy += Long.parseLong(fields[8]); /* Checks this condition before choosing what to do. */
            // Guest ticks are included in user/nice, so avoid double-counting them.
            result.put(id, new SystemSnapshot.CpuTicks(busy, idle)); /* Calls this operation with the values shown. */
		} /* This brace ends a code block. */
        return result; /* Returns this value to the caller. */
		} /* This brace ends a code block. */

    /* Uses MemAvailable as the estimate of memory currently available to workloads. */
    private static SystemSnapshot.Memory memorySnapshot() throws IOException { /* Declares the memorySnapshot method. */
        Map<String, Long> memory = new HashMap<>(); /* Stores the calculated value in memory. */
        for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) { /* Repeats this work for each matching value. */
            String[] fields = line.split("\\s+"); /* Stores the calculated value in fields. */
            if (fields.length > 1) memory.put( /* Checks this condition before choosing what to do. */
                fields[0].replace(":", ""), /* Provides the next part of the current expression. */
                Long.parseLong(fields[1]) /* Calls this operation with the values shown. */
            ); /* Provides the next part of the current expression. */
		} /* This brace ends a code block. */
        long total = memory.get("MemTotal"); /* Stores the calculated value in total. */
        long used = /* Stores the calculated value in used. */
            total - memory.getOrDefault("MemAvailable", memory.get("MemFree")); /* Calls this operation with the values shown. */
        return new SystemSnapshot.Memory(total, used, (used * 100.0) / total); /* Returns this value to the caller. */
		} /* This brace ends a code block. */

    /* Resolves PCI addresses and IDs from Linux's PCI sysfs directory. */
    private static List<SystemSnapshot.PciDevice> readPci( /* Declares the readPci method. */
        Map<Integer, Vendor> names /* Continues the current operation. */
    ) /* Provides the next part of the current expression. */
        throws IOException { /* Continues the current operation. */
        List<SystemSnapshot.PciDevice> devices = new ArrayList<>(); /* Stores the calculated value in devices. */
        Path root = Path.of("/sys/bus/pci/devices"); /* Stores the calculated value in root. */
        if (!Files.isDirectory(root)) return devices; /* Checks this condition before choosing what to do. */
        try (Stream<Path> paths = Files.list(root)) { /* Starts work that may need error handling or resource cleanup. */
            for (Path path : paths.toList()) { /* Repeats this work for each matching value. */
                String[] address = path.getFileName().toString().split("[:.]"); /* Stores the calculated value in address. */
                // Sysfs names may include a domain before bus:device.function.
                int offset = address.length == 4 ? 1 : 0; /* Stores the calculated value in offset. */
                int vendor = readHex(path.resolve("vendor")); /* Stores the calculated value in vendor. */
                int device = readHex(path.resolve("device")); /* Stores the calculated value in device. */
                devices.add( /* Calls this operation with the values shown. */
                    new SystemSnapshot.PciDevice( /* Creates a new value for the caller or current operation. */
                        Integer.parseInt(address[offset], 16), /* Provides the next part of the current expression. */
                        Integer.parseInt(address[offset + 1], 16), /* Provides the next part of the current expression. */
                        Integer.parseInt(address[offset + 2], 16), /* Provides the next part of the current expression. */
                        vendor, /* Provides the next part of the current expression. */
                        device, /* Provides the next part of the current expression. */
                        readHex(path.resolve("class")), /* Provides the next part of the current expression. */
                        vendorName(names, vendor), /* Provides the next part of the current expression. */
                        deviceName(names, vendor, device) /* Calls this operation with the values shown. */
                    ) /* Provides the next part of the current expression. */
                ); /* Provides the next part of the current expression. */
		} /* This brace ends a code block. */
		} /* This brace ends a code block. */
        return devices; /* Returns this value to the caller. */
		} /* This brace ends a code block. */

    /* Collects connected USB devices exposing vendor and product IDs. */
    private static List<SystemSnapshot.UsbDevice> readUsb( /* Declares the readUsb method. */
        Map<Integer, Vendor> names /* Continues the current operation. */
    ) /* Provides the next part of the current expression. */
        throws IOException { /* Continues the current operation. */
        List<SystemSnapshot.UsbDevice> devices = new ArrayList<>(); /* Stores the calculated value in devices. */
        Path root = Path.of("/sys/bus/usb/devices"); /* Stores the calculated value in root. */
        if (!Files.isDirectory(root)) return devices; /* Checks this condition before choosing what to do. */
        try (Stream<Path> paths = Files.list(root)) { /* Starts work that may need error handling or resource cleanup. */
            for (Path path : paths.toList()) { /* Repeats this work for each matching value. */
                Path vendorFile = path.resolve("idVendor"); /* Stores the calculated value in vendorFile. */
                Path productFile = path.resolve("idProduct"); /* Stores the calculated value in productFile. */
                // USB interface entries without device IDs are not USB devices.
                if ( /* Checks this condition before choosing what to do. */
                    !Files.isRegularFile(vendorFile) || /* Provides the next part of the current expression. */
                    !Files.isRegularFile(productFile) /* Calls this operation with the values shown. */
                ) continue; /* Provides the next part of the current expression. */
                int vendor = readHex(vendorFile); /* Stores the calculated value in vendor. */
                int product = readHex(productFile); /* Stores the calculated value in product. */
                devices.add( /* Calls this operation with the values shown. */
                    new SystemSnapshot.UsbDevice( /* Creates a new value for the caller or current operation. */
                        vendor, /* Provides the next part of the current expression. */
                        product, /* Provides the next part of the current expression. */
                        vendorName(names, vendor), /* Provides the next part of the current expression. */
                        deviceName(names, vendor, product) /* Calls this operation with the values shown. */
                    ) /* Provides the next part of the current expression. */
                ); /* Provides the next part of the current expression. */
		} /* This brace ends a code block. */
		} /* This brace ends a code block. */
        return devices; /* Returns this value to the caller. */
		} /* This brace ends a code block. */

    /* hides filesystems that are not real storage */
    private static final List<String> PSEUDO_TYPES = List.of( /* Stores the calculated value in PSEUDO_TYPES. */
	"proc", "sysfs", "devtmpfs" , "devpts" , "cgroup", "cgroup2", /* Provides the next part of the current expression. */
	 "securityfs", "debugfs", "tracefs", "configfs", "fusectl", /* Provides the next part of the current expression. */
	 "pstore", "bpf", "mqueue", "hugetlbfs", "autofs", "binfmt_misc", /* Provides the next part of the current expression. */
	"rpc_pipefs", "nsfs", "efivarfs", "squashfs" /* Continues the current operation. */
    ); /* Provides the next part of the current expression. */

    /* Reads mounted file systems and their sizes. */
    private static List<SystemSnapshot.Disk> readDisks() { /* Declares the readDisks method. */
	List<String> lines; /* Continues the current operation. */
	try { /* Starts work that may need error handling or resource cleanup. */
	      lines = Files.readAllLines(Path.of("/proc/self/mounts")); /* Sets or updates lines. */
	} /* This brace ends a code block. */
	catch (IOException e) { /* Handles an error from the preceding operation. */
           return new ArrayList<>(); /* Returns this value to the caller. */ // shows nothing if file cant be read
	} /* This brace ends a code block. */

	List<SystemSnapshot.Disk> result = new ArrayList<>(); /* Stores the calculated value in result. */
	Set<String> seenDevices = new HashSet<>(); /* Stores the calculated value in seenDevices. */

	for (String line : lines) { /* Repeats this work for each matching value. */
		/* (from proc/self/mounts) */
		String[] fields = line.split(" "); /* Stores the calculated value in fields. */
		if (fields.length < 3) { /* Checks this condition before choosing what to do. */
			continue; /* Changes which loop iteration runs next. */ // skips lines with invalid formatting
		} /* This brace ends a code block. */
		String device = decodeMountText(fields[0]); /* Stores the calculated value in device. */
		String mountPoint = decodeMountText(fields[1]); /* Stores the calculated value in mountPoint. */
		String type = fields[2]; /* Stores the calculated value in type. */

		if (isPseudo(type, mountPoint)) { /* Checks this condition before choosing what to do. */
			continue; /* Changes which loop iteration runs next. */
		} /* This brace ends a code block. */

		/*show each real device once */
		if (device.startsWith("/")) { /* Checks this condition before choosing what to do. */
			if (seenDevices.contains(device)){ /* Checks this condition before choosing what to do. */
				continue; /* Changes which loop iteration runs next. */
			} /* This brace ends a code block. */
			seenDevices.add(device); /* Calls this operation with the values shown. */
		} /* This brace ends a code block. */

		SystemSnapshot.Disk disk = readDiskUsage(device, mountPoint, type); /* Stores the calculated value in disk. */
		if (disk != null) { /* Checks this condition before choosing what to do. */
			result.add(disk); /* Calls this operation with the values shown. */
		} /* This brace ends a code block. */
	} /* This brace ends a code block. */
	return result; /* Returns this value to the caller. */
		} /* This brace ends a code block. */

   private static boolean isPseudo(String type, String mountPoint) { /* Declares the isPseudo method. */
	if (PSEUDO_TYPES.contains(type)) { /* Checks this condition before choosing what to do. */
		return true; /* Returns this value to the caller. */
		} /* This brace ends a code block. */

	/* tmpfs lives in RAM. Hide it under system folders, keep it elsewhere ( like /tmp) */

	if (type.equals("tmpfs")) { /* Checks this condition before choosing what to do. */
		return mountPoint.startsWith ("/run") /* Returns this value to the caller. */
			|| mountPoint.startsWith("/dev") /* Calls this operation with the values shown. */
			|| mountPoint.startsWith("/sys") /* Calls this operation with the values shown. */
			|| mountPoint.startsWith("/proc"); /* Calls this operation with the values shown. */
	} /* This brace ends a code block. */
	return false; /* Returns this value to the caller. */
		} /* This brace ends a code block. */

   /* get size of one mounted file system */
   private static SystemSnapshot.Disk readDiskUsage( /* Declares the readDiskUsage method. */
	String device, /* Provides the next part of the current expression. */
	String mountPoint, /* Provides the next part of the current expression. */
	String type /* Continues the current operation. */
   ) { /* Provides the next part of the current expression. */
	try { /* Starts work that may need error handling or resource cleanup. */
		FileStore store = Files.getFileStore(Path.of(mountPoint)); /* Stores the calculated value in store. */
		long total = store.getTotalSpace(); /* Stores the calculated value in total. */
		if (total <= 0) { /* Checks this condition before choosing what to do. */
			return null; /* Returns this value to the caller. */ //no size, dont show it
		} /* This brace ends a code block. */
		long used = total - store.getUnallocatedSpace(); /* Stores the calculated value in used. */
		long available =  store.getUsableSpace(); /* Stores the calculated value in available. */
		double percent = used * 100.0 / (used + available) ; /* Stores the calculated value in percent. */
		return new SystemSnapshot.Disk( /* Returns this value to the caller. */
			device, mountPoint, type, total, used, available, percent, null /* Continues the current operation. */
		); /* Provides the next part of the current expression. */
		} /* This brace ends a code block. */
	catch (IOException | RuntimeException e) { /* Handles an error from the preceding operation. */
		/* still list it but mention it couldnt be read */
		return new SystemSnapshot.Disk( /* Returns this value to the caller. */
			device, mountPoint, type, null, null, null, null, "not accessible" /* Continues the current operation. */
		); /* Provides the next part of the current expression. */
	     } /* This brace ends a code block. */
	  } /* This brace ends a code block. */

	/* Linux writes special characters in mount paths as codes. Turn them back. */
	private static String decodeMountText(String text) { /* Declares the decodeMountText method. */
		return text /* Returns this value to the caller. */
			.replace("\\040", " ") /* Provides the next part of the current expression. */ //space
			.replace("\\011", "\t ") /* Provides the next part of the current expression. */ //tab
			.replace("\\012", "\n ") /* Provides the next part of the current expression. */ //newline
			.replace("\\134", "\\"); /* Provides the next part of the current expression. */ //backslash
	} /* This brace ends a code block. */




    /* Reads sysfs hexadecimal attributes such as vendor and product IDs. */
    private static int readHex(Path path) throws IOException { /* Declares the readHex method. */
        return Integer.parseInt( /* Returns this value to the caller. */
            Files.readString(path).trim().replace("0x", ""), /* Uses the named Java API to perform this operation. */
            16 /* Continues the current operation. */
        ); /* Provides the next part of the current expression. */
		} /* This brace ends a code block. */

    /* Looks up a vendor label or falls back to its raw hexadecimal ID. */
    private static String vendorName(Map<Integer, Vendor> names, int id) { /* Declares the vendorName method. */
        return names.containsKey(id) ? names.get(id).name() : hex(id); /* Returns this value to the caller. */
		} /* This brace ends a code block. */

    /* Looks up a product label under its vendor, with a raw-ID fallback. */
    private static String deviceName( /* Declares the deviceName method. */
        Map<Integer, Vendor> names, /* Provides the next part of the current expression. */
        int vendor, /* Provides the next part of the current expression. */
        int device /* Continues the current operation. */
    ) { /* Provides the next part of the current expression. */
        return names.containsKey(vendor) /* Returns this value to the caller. */
            ? names.get(vendor).devices().getOrDefault(device, hex(device)) /* Calls this operation with the values shown. */
            : hex(device); /* Calls this operation with the values shown. */
		} /* This brace ends a code block. */

    /* Standardizes IDs for terminal labels and lookup fallbacks. */
    private static String hex(int id) { /* Declares the hex method. */
        return String.format(Locale.ROOT, "0x%04x", id); /* Returns this value to the caller. */
		} /* This brace ends a code block. */
} /* This brace ends a code block. */
