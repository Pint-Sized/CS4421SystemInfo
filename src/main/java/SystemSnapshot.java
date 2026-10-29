import java.util.List;
import java.util.Map;

/* Immutable values shared by collection, terminal display, logging, and JSON export. */
record SystemSnapshot(
    SystemSnapshot.Processor processor,
    SystemSnapshot.Memory memory,
    List<SystemSnapshot.PciDevice> pci,
    List<SystemSnapshot.UsbDevice> usb,
    List<SystemSnapshot.Disk> disks,
    List<SystemSnapshot.Battery> batteries,
    List<SystemSnapshot.Sensor> sensors
) {

    /* Cumulative busy and idle CPU ticks used to calculate utilization deltas. */
    record CpuTicks(long busy, long idle) {}

    /* Percentage utilization for one logical CPU during the latest sample interval. */
    record CpuLoad(int id, double utilization) {}

    /* Percentage utilization for one physical core, combining its logical CPUs. */
    record CoreLoad(String name, double utilization) {}

    /* Processor identity, CPU counts, topology status, and measured load averages. */
    record Processor(
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

    /* Total and used memory in KiB, with the percentage of total memory in use. */
    record Memory(long totalKiB, long usedKiB, double usedPercent) {}

    /* One PCI function and its address, IDs, class, and resolved names. */
    record PciDevice(
        int bus,
        int device,
        int function,
        int vendor,
        int id,
        int deviceClass,
        String vendorName,
        String name
    ) {}

    /* One USB device and its vendor/product IDs and resolved names. */
    record UsbDevice(int vendor, int id, String vendorName, String name) {}

    /* One mounted filesystem; size and usage fields are null when unavailable. */
    record Disk(
	String device,
	String mountPoint,
	String fsType,
	Long totalBytes,
	Long usedBytes,
	Long availableBytes,
	Double usedPercent,
	String note
     ) {}

    /* One battery; charge percentage and status may be null when unavailable. */
    record Battery(String name, Integer chargePercent, String status) {}

    /* One temperature or fan reading; value may be null when it could not be read. */
    record Sensor(String chip, String label, String kind, Double value, String unit) {}

    /* Snapshot plus CPU counters retained as the baseline for the next refresh. */
    record CollectionResult(
        SystemSnapshot snapshot,
        Map<Integer, CpuTicks> cpuTimes
    ) {}
}
