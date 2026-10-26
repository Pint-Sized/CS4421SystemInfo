import java.util.List;
import java.util.Map;

/* Immutable values shared by the collector, terminal view, and JSON writer. */
record SystemSnapshot(
    SystemSnapshot.Processor processor,
    SystemSnapshot.Memory memory,
    List<SystemSnapshot.PciDevice> pci,
    List<SystemSnapshot.UsbDevice> usb,
    List<SystemSnapshot.Disk> disks
) {

    /* Cumulative Linux CPU counters used to calculate utilization deltas. */
    record CpuTicks(long busy, long idle) {}

    /* Utilization for one logical CPU during the latest sampling interval. */
    record CpuLoad(int id, double utilization) {}

    /* Utilization for one physical core, aggregating its logical CPUs. */
    record CoreLoad(String name, double utilization) {}

    /* Processor identity, topology availability, and measured CPU loads. */
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

    /* Total and used memory in KiB, with the used percentage. */
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

    /* One mounted filesystem */
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

    /* Snapshot plus CPU counters retained as the baseline for the next refresh. */
    record CollectionResult(
        SystemSnapshot snapshot,
        Map<Integer, CpuTicks> cpuTimes
    ) {}
}
