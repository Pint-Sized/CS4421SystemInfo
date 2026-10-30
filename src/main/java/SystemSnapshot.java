import java.util.List; /* Imports java.util.List for use in this file. */
import java.util.Map; /* Imports java.util.Map for use in this file. */

/* Immutable values shared by the collector, terminal view, and JSON writer. */
record SystemSnapshot( /* Declares the SystemSnapshot method. */
    SystemSnapshot.Processor processor, /* Provides the next part of the current expression. */
    SystemSnapshot.Memory memory, /* Provides the next part of the current expression. */
    List<SystemSnapshot.PciDevice> pci, /* Provides the next part of the current expression. */
    List<SystemSnapshot.UsbDevice> usb, /* Provides the next part of the current expression. */
    List<SystemSnapshot.Disk> disks /* Continues the current operation. */
) { /* Provides the next part of the current expression. */

    /* Cumulative Linux CPU counters used to calculate utilization deltas. */
    record CpuTicks(long busy, long idle) {} /* Declares the CpuTicks method. */

    /* Utilization for one logical CPU during the latest sampling interval. */
    record CpuLoad(int id, double utilization) {} /* Declares the CpuLoad method. */

    /* Utilization for one physical core, aggregating its logical CPUs. */
    record CoreLoad(String name, double utilization) {} /* Declares the CoreLoad method. */

    /* Processor identity, topology availability, and measured CPU loads. */
    record Processor( /* Declares the Processor method. */
        String model, /* Provides the next part of the current expression. */
        int logicalCpus, /* Provides the next part of the current expression. */
        Integer physicalCores, /* Provides the next part of the current expression. */
        boolean topologyAvailable, /* Provides the next part of the current expression. */
        boolean topologyComplete, /* Provides the next part of the current expression. */
        List<CpuLoad> logicalLoads, /* Provides the next part of the current expression. */
        Double averageLogicalLoad, /* Provides the next part of the current expression. */
        List<CoreLoad> physicalLoads, /* Provides the next part of the current expression. */
        Double averagePhysicalLoad /* Continues the current operation. */
    ) {} /* Provides the next part of the current expression. */

    /* Total and used memory in KiB, with the used percentage. */
    record Memory(long totalKiB, long usedKiB, double usedPercent) {} /* Declares the Memory method. */

    /* One PCI function and its address, IDs, class, and resolved names. */
    record PciDevice( /* Declares the PciDevice method. */
        int bus, /* Provides the next part of the current expression. */
        int device, /* Provides the next part of the current expression. */
        int function, /* Provides the next part of the current expression. */
        int vendor, /* Provides the next part of the current expression. */
        int id, /* Provides the next part of the current expression. */
        int deviceClass, /* Provides the next part of the current expression. */
        String vendorName, /* Provides the next part of the current expression. */
        String name /* Continues the current operation. */
    ) {} /* Provides the next part of the current expression. */

    /* One USB device and its vendor/product IDs and resolved names. */
    record UsbDevice(int vendor, int id, String vendorName, String name) {} /* Declares the UsbDevice method. */

    /* One mounted filesystem */
    record Disk( /* Declares the Disk method. */
	String device, /* Provides the next part of the current expression. */
	String mountPoint, /* Provides the next part of the current expression. */
	String fsType, /* Provides the next part of the current expression. */
	Long totalBytes, /* Provides the next part of the current expression. */
	Long usedBytes, /* Provides the next part of the current expression. */
	Long availableBytes, /* Provides the next part of the current expression. */
	Double usedPercent, /* Provides the next part of the current expression. */
	String note /* Continues the current operation. */
     ) {} /* Provides the next part of the current expression. */

    /* Snapshot plus CPU counters retained as the baseline for the next refresh. */
    record CollectionResult( /* Declares the CollectionResult method. */
        SystemSnapshot snapshot, /* Provides the next part of the current expression. */
        Map<Integer, CpuTicks> cpuTimes /* Continues the current operation. */
    ) {} /* Provides the next part of the current expression. */
} /* This brace ends a code block. */
