import com.williamcallahan.tui4j.compat.bubbletea.Command; /* Imports com.williamcallahan.tui4j.compat.bubbletea.Command for use in this file. */
import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage; /* Imports com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage for use in this file. */
import com.williamcallahan.tui4j.compat.bubbletea.Message; /* Imports com.williamcallahan.tui4j.compat.bubbletea.Message for use in this file. */
import com.williamcallahan.tui4j.compat.bubbletea.Model; /* Imports com.williamcallahan.tui4j.compat.bubbletea.Model for use in this file. */
import com.williamcallahan.tui4j.compat.bubbletea.Program; /* Imports com.williamcallahan.tui4j.compat.bubbletea.Program for use in this file. */
import com.williamcallahan.tui4j.compat.bubbletea.ProgramException; /* Imports com.williamcallahan.tui4j.compat.bubbletea.ProgramException for use in this file. */
import com.williamcallahan.tui4j.compat.bubbletea.QuitMessage; /* Imports com.williamcallahan.tui4j.compat.bubbletea.QuitMessage for use in this file. */
import com.williamcallahan.tui4j.compat.bubbletea.UpdateResult; /* Imports com.williamcallahan.tui4j.compat.bubbletea.UpdateResult for use in this file. */
import com.williamcallahan.tui4j.compat.bubbletea.WindowSizeMessage; /* Imports com.williamcallahan.tui4j.compat.bubbletea.WindowSizeMessage for use in this file. */
import com.williamcallahan.tui4j.compat.lipgloss.Style; /* Imports com.williamcallahan.tui4j.compat.lipgloss.Style for use in this file. */
import com.williamcallahan.tui4j.compat.lipgloss.color.Color; /* Imports com.williamcallahan.tui4j.compat.lipgloss.color.Color for use in this file. */
import java.io.IOException; /* Imports java.io.IOException for use in this file. */
import java.nio.file.Path; /* Imports java.nio.file.Path for use in this file. */
import java.time.Duration; /* Imports java.time.Duration for use in this file. */
import java.time.LocalTime; /* Imports java.time.LocalTime for use in this file. */
import java.util.ArrayList; /* Imports java.util.ArrayList for use in this file. */
import java.util.Comparator; /* Imports java.util.Comparator for use in this file. */
import java.util.List; /* Imports java.util.List for use in this file. */
import java.util.Locale; /* Imports java.util.Locale for use in this file. */
import java.util.Map; /* Imports java.util.Map for use in this file. */
import java.util.TreeMap; /* Imports java.util.TreeMap for use in this file. */

/*
 * Interactive processor, memory, PCI, and USB system information for Linux.
 */
public class SystemFetch implements Model { /* Declares the SystemFetch type. */

    /* Report sections displayed as tabs in the interactive interface. */
    private static final String[] TABS = { /* Stores the calculated value in TABS. */
        "Processor", /* Provides the next part of the current expression. */
        "Memory", /* Provides the next part of the current expression. */
        "PCI", /* Provides the next part of the current expression. */
        "USB", /* Provides the next part of the current expression. */
        "Disk", /* Provides the next part of the current expression. */
    }; /* Provides the next part of the current expression. */
    /* Below these dimensions the tab rail and report cannot be read reliably. */
    private static final int MIN_TERMINAL_WIDTH = 40; /* Stores the calculated value in MIN_TERMINAL_WIDTH. */
    private static final int MIN_TERMINAL_HEIGHT = 7; /* Stores the calculated value in MIN_TERMINAL_HEIGHT. */
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(2); /* Stores the calculated value in REFRESH_INTERVAL. */

    /* 256-color Lipgloss styles distinguish the active tab, headings, and labels. */
    private static final Style ACTIVE_TAB = Style.newStyle() /* Stores the calculated value in ACTIVE_TAB. */
        .foreground(Color.color("15")) /* Provides the next part of the current expression. */
        .background(Color.color("99")) /* Provides the next part of the current expression. */
        .bold(true) /* Provides the next part of the current expression. */
        .padding(0, 1); /* Provides the next part of the current expression. */
    private static final Style INACTIVE_TAB = Style.newStyle() /* Stores the calculated value in INACTIVE_TAB. */
        .foreground(Color.color("245")) /* Provides the next part of the current expression. */
        .bold(true) /* Provides the next part of the current expression. */
        .padding(0, 1); /* Provides the next part of the current expression. */
    private static final Style TITLE = Style.newStyle() /* Stores the calculated value in TITLE. */
        .foreground(Color.color("39")) /* Provides the next part of the current expression. */
        .bold(true); /* Provides the next part of the current expression. */
    private static final Style SECTION_TITLE = Style.newStyle() /* Stores the calculated value in SECTION_TITLE. */
        .foreground(Color.color("205")) /* Provides the next part of the current expression. */
        .bold(true); /* Provides the next part of the current expression. */
    private static final Style LABEL = Style.newStyle() /* Stores the calculated value in LABEL. */
        .foreground(Color.color("43")) /* Provides the next part of the current expression. */
        .bold(true); /* Provides the next part of the current expression. */
    private static final Style ACCENT = Style.newStyle().foreground( /* Stores the calculated value in ACCENT. */
        Color.color("99") /* Calls this operation with the values shown. */
    ); /* Provides the next part of the current expression. */
    private static final Style HELP = Style.newStyle().foreground( /* Stores the calculated value in HELP. */
        Color.color("245") /* Calls this operation with the values shown. */
    ); /* Provides the next part of the current expression. */

    /* Loads hardware IDs and system data, then runs the interactive or text report. */
    public static void main(String[] args) /* Declares the main method. */
        throws IOException, InterruptedException { /* Continues the current operation. */
        Options options = parseOptions(args); /* Stores the calculated value in options. */
        Path jsonFile = options.jsonFile(); /* Stores the calculated value in jsonFile. */
        Map<Integer, SystemInfoCollector.Vendor> pciNames = /* Stores the calculated value in pciNames. */
            SystemInfoCollector.loadIds( /* Calls this operation with the values shown. */
                "pci.ids", /* Provides the next part of the current expression. */
                "https://pci-ids.ucw.cz/v2.2/pci.ids" /* Continues the current operation. */
            ); /* Provides the next part of the current expression. */
        Map<Integer, SystemInfoCollector.Vendor> usbNames = /* Stores the calculated value in usbNames. */
            SystemInfoCollector.loadIds( /* Calls this operation with the values shown. */
                "usb.ids", /* Provides the next part of the current expression. */
                "https://raw.githubusercontent.com/vcrhonek/hwdata/master/usb.ids" /* Continues the current operation. */
            ); /* Provides the next part of the current expression. */
        SystemInfoCollector collector = new SystemInfoCollector( /* Stores the calculated value in collector. */
            pciNames, /* Provides the next part of the current expression. */
            usbNames /* Continues the current operation. */
        ); /* Provides the next part of the current expression. */

        if ( /* Checks this condition before choosing what to do. */
            options.plainText() || /* Provides the next part of the current expression. */
            System.console() == null || /* Uses the named Java API to perform this operation. */
            "dumb".equals(System.getenv("TERM")) /* Calls this operation with the values shown. */
        ) { /* Provides the next part of the current expression. */
            Snapshot snapshot = collectPlainSnapshot(collector); /* Stores the calculated value in snapshot. */
            if (jsonFile != null) { /* Checks this condition before choosing what to do. */
                JsonSnapshotWriter.writeAtomically(jsonFile, snapshot.data()); /* Calls this operation with the values shown. */
            } /* This brace ends a code block. */
            printPlainReport(snapshot.sections()); /* Calls this operation with the values shown. */
            return; /* Continues the current operation. */
        } /* This brace ends a code block. */
        try { /* Starts work that may need error handling or resource cleanup. */
            new Program(new SystemFetch(collector, jsonFile)) /* Creates a new value for the caller or current operation. */
                .withAltScreen() /* Provides the next part of the current expression. */
                .run(); /* Provides the next part of the current expression. */
        } catch (ProgramException e) { /* Provides the next part of the current expression. */
            System.err.println( /* Uses the named Java API to perform this operation. */
                "Interactive terminal unavailable; printing plain-text report." /* Continues the current operation. */
            ); /* Provides the next part of the current expression. */
            Snapshot snapshot = collectPlainSnapshot(collector); /* Stores the calculated value in snapshot. */
            if (jsonFile != null) { /* Checks this condition before choosing what to do. */
                JsonSnapshotWriter.writeAtomically(jsonFile, snapshot.data()); /* Calls this operation with the values shown. */
            } /* This brace ends a code block. */
            printPlainReport(snapshot.sections()); /* Calls this operation with the values shown. */
        } /* This brace ends a code block. */
    } /* This brace ends a code block. */

    /* Samples CPU counters twice for a useful initial plain-text load report. */
    private static Snapshot collectPlainSnapshot(SystemInfoCollector collector) /* Declares the collectPlainSnapshot method. */
        throws IOException, InterruptedException { /* Continues the current operation. */
        SystemSnapshot.CollectionResult baseline = collector.collect(Map.of()); /* Stores the calculated value in baseline. */
        Thread.sleep(1000); /* Uses the named Java API to perform this operation. */
        return createReport(collector.collect(baseline.cpuTimes())); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    private record Options(Path jsonFile, boolean plainText) {} /* Declares the Options method. */

    /* Parses output options and normalizes the optional JSON export path. */
    private static Options parseOptions(String[] args) { /* Declares the parseOptions method. */
        Path jsonFile = null; /* Stores the calculated value in jsonFile. */
        boolean plainText = false; /* Stores the calculated value in plainText. */
        for (int i = 0; i < args.length; i++) { /* Repeats this work for each matching value. */
            switch (args[i]) { /* Selects or handles one of the available cases. */
                case "--plain" -> plainText = true; /* Selects or handles one of the available cases. */
                case "--json-file" -> { /* Selects or handles one of the available cases. */
                    if (jsonFile != null || i + 1 >= args.length) { /* Checks this condition before choosing what to do. */
                        throw new IllegalArgumentException( /* Reports this error to the caller. */
                            "Usage: SystemFetch [--plain] [--json-file <path>]" /* Continues the current operation. */
                        ); /* Provides the next part of the current expression. */
                    } /* This brace ends a code block. */
                    jsonFile = Path.of(args[++i]).toAbsolutePath(); /* Sets or updates jsonFile. */
                } /* This brace ends a code block. */
                default -> throw new IllegalArgumentException( /* Selects or handles one of the available cases. */
                    "Unknown option: " + /* Provides the next part of the current expression. */
                        args[i] + /* Provides the next part of the current expression. */
                        "\nUsage: SystemFetch [--plain] [--json-file <path>]" /* Continues the current operation. */
                ); /* Provides the next part of the current expression. */
            } /* This brace ends a code block. */
        } /* This brace ends a code block. */
        return new Options(jsonFile, plainText); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Prints all sections sequentially when a full-screen terminal is unavailable. */
    /* Prints each report section in tab order for non-interactive use. */
    private static void printPlainReport(List<String> sections) { /* Declares the printPlainReport method. */
        for (int i = 0; i < sections.size(); i++) { /* Repeats this work for each matching value. */
            System.out.println("\n" + TABS[i]); /* Uses the named Java API to perform this operation. */
            System.out.println(sections.get(i)); /* Uses the named Java API to perform this operation. */
        } /* This brace ends a code block. */
    } /* This brace ends a code block. */

    private record Snapshot( /* Declares the Snapshot method. */
        List<String> sections, /* Provides the next part of the current expression. */
        SystemSnapshot.CollectionResult collection /* Continues the current operation. */
    ) { /* Provides the next part of the current expression. */
        /* Exposes the data snapshot separately from its terminal presentation. */
        SystemSnapshot data() { /* Declares the data method. */
            return collection.snapshot(); /* Returns this value to the caller. */
        } /* This brace ends a code block. */
    } /* This brace ends a code block. */

    private record RefreshMessage( /* Declares the RefreshMessage method. */
        Snapshot snapshot, /* Provides the next part of the current expression. */
        String error /* Continues the current operation. */
    ) implements Message {} /* Provides the next part of the current expression. */

    private record RefreshTick() implements Message {} /* Declares the RefreshTick method. */

    /* Current report content and terminal navigation state. */
    private final List<String> sections; /* Continues the current operation. */
    private final SystemInfoCollector collector; /* Continues the current operation. */
    private final Path jsonFile; /* Continues the current operation. */
    private Map<Integer, SystemSnapshot.CpuTicks> previousCpuTimes = Map.of(); /* Stores the calculated value in previousCpuTimes. */
    private String refreshStatus = "Collecting system information..."; /* Stores the calculated value in refreshStatus. */
    private int selectedTab; /* Continues the current operation. */
    private int terminalWidth = 80; /* Stores the calculated value in terminalWidth. */
    private int terminalHeight = 24; /* Stores the calculated value in terminalHeight. */
    private int scroll; /* Continues the current operation. */

    /* Starts with placeholders so the TUI can render before collection completes. */
    private SystemFetch( /* Declares the SystemFetch method. */
        SystemInfoCollector collector, /* Provides the next part of the current expression. */
        Path jsonFile /* Continues the current operation. */
    ) { /* Provides the next part of the current expression. */
        this.collector = collector; /* Sets or updates collector. */
        this.jsonFile = jsonFile; /* Sets or updates jsonFile. */
        this.sections = new ArrayList<>( /* Sets or updates sections. */
            List.of( /* Calls this operation with the values shown. */
                "Collecting processor information...", /* Provides the next part of the current expression. */
                "Collecting memory information...", /* Provides the next part of the current expression. */
                "Collecting PCI devices...", /* Provides the next part of the current expression. */
                "Collecting USB devices...", /* Provides the next part of the current expression. */
                "Collecting Disk information..." /* Continues the current operation. */
            ) /* Provides the next part of the current expression. */
        ); /* Provides the next part of the current expression. */
    } /* This brace ends a code block. */

    /* Prepares separate terminal-formatted sections from a collected snapshot. */
    private static Snapshot createReport( /* Declares the createReport method. */
        SystemSnapshot.CollectionResult collection /* Continues the current operation. */
    ) { /* Provides the next part of the current expression. */
        SystemSnapshot data = collection.snapshot(); /* Stores the calculated value in data. */
        return new Snapshot( /* Returns this value to the caller. */
            List.of( /* Calls this operation with the values shown. */
                processorInfo(data.processor()), /* Provides the next part of the current expression. */
                memoryInfo(data.memory()), /* Provides the next part of the current expression. */
                pciInfo(data.pci()), /* Provides the next part of the current expression. */
                usbInfo(data.usb()), /* Provides the next part of the current expression. */
                diskInfo(data.disks()) /* Calls this operation with the values shown. */
            ), /* Provides the next part of the current expression. */
            collection /* Continues the current operation. */
        ); /* Provides the next part of the current expression. */
    } /* This brace ends a code block. */

    /* Renders processor topology and loads for the terminal report. */
    private static String processorInfo(SystemSnapshot.Processor info) { /* Declares the processorInfo method. */
        StringBuilder output = new StringBuilder(); /* Stores the calculated value in output. */
        output.append("Model: ").append(info.model()).append('\n'); /* Adds this value to the output being built. */
        output.append("Logical CPUs: ").append(info.logicalCpus()).append('\n'); /* Adds this value to the output being built. */
        if (info.topologyAvailable()) { /* Checks this condition before choosing what to do. */
            output.append("Physical CPU cores: ").append(info.physicalCores()); /* Adds this value to the output being built. */
            if (!info.topologyComplete()) { /* Checks this condition before choosing what to do. */
                output.append( /* Adds this value to the output being built. */
                    " (topology incomplete; unmapped logical CPUs counted individually)" /* Calls this operation with the values shown. */
                ); /* Provides the next part of the current expression. */
            } /* This brace ends a code block. */
            output.append('\n'); /* Adds this value to the output being built. */
        } else { /* Provides the next part of the current expression. */
            output.append( /* Adds this value to the output being built. */
                "Physical CPU core topology unavailable; load is grouped per logical CPU.\n" /* Continues the current operation. */
            ); /* Provides the next part of the current expression. */
        } /* This brace ends a code block. */
        output.append("Load per logical CPU:\n"); /* Adds this value to the output being built. */
        if (info.logicalLoads().isEmpty()) output.append("  Sampling...\n"); /* Checks this condition before choosing what to do. */
        for (SystemSnapshot.CpuLoad load : info.logicalLoads()) { /* Repeats this work for each matching value. */
            output.append( /* Adds this value to the output being built. */
                String.format( /* Uses the named Java API to perform this operation. */
                    Locale.ROOT, /* Provides the next part of the current expression. */
                    "  CPU %d: %.1f%%%n", /* Provides the next part of the current expression. */
                    load.id(), /* Provides the next part of the current expression. */
                    load.utilization() /* Calls this operation with the values shown. */
                ) /* Provides the next part of the current expression. */
            ); /* Provides the next part of the current expression. */
        } /* This brace ends a code block. */
        if (info.averageLogicalLoad() != null) { /* Checks this condition before choosing what to do. */
            output.append( /* Adds this value to the output being built. */
                String.format( /* Uses the named Java API to perform this operation. */
                    Locale.ROOT, /* Provides the next part of the current expression. */
                    "Average across all logical CPUs: %.1f%%%n", /* Provides the next part of the current expression. */
                    info.averageLogicalLoad() /* Calls this operation with the values shown. */
                ) /* Provides the next part of the current expression. */
            ); /* Provides the next part of the current expression. */
        } /* This brace ends a code block. */
        if (info.topologyAvailable()) { /* Checks this condition before choosing what to do. */
            output.append("Load per physical core:\n"); /* Adds this value to the output being built. */
            for (SystemSnapshot.CoreLoad load : info.physicalLoads()) { /* Repeats this work for each matching value. */
                output.append( /* Adds this value to the output being built. */
                    String.format( /* Uses the named Java API to perform this operation. */
                        Locale.ROOT, /* Provides the next part of the current expression. */
                        "  %s: %.1f%%%n", /* Provides the next part of the current expression. */
                        load.name(), /* Provides the next part of the current expression. */
                        load.utilization() /* Calls this operation with the values shown. */
                    ) /* Provides the next part of the current expression. */
                ); /* Provides the next part of the current expression. */
            } /* This brace ends a code block. */
            if (info.averagePhysicalLoad() != null) { /* Checks this condition before choosing what to do. */
                output.append( /* Adds this value to the output being built. */
                    String.format( /* Uses the named Java API to perform this operation. */
                        Locale.ROOT, /* Provides the next part of the current expression. */
                        "Average across all physical cores: %.1f%%%n", /* Provides the next part of the current expression. */
                        info.averagePhysicalLoad() /* Calls this operation with the values shown. */
                    ) /* Provides the next part of the current expression. */
                ); /* Provides the next part of the current expression. */
            } /* This brace ends a code block. */
        } /* This brace ends a code block. */
        return output.toString(); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Formats memory values for the terminal report. */
    private static String memoryInfo(SystemSnapshot.Memory memory) { /* Declares the memoryInfo method. */
        return String.format( /* Returns this value to the caller. */
            Locale.ROOT, /* Provides the next part of the current expression. */
            "Total: %.1f GiB%nUsed: %.1f GiB (%.1f%%)", /* Provides the next part of the current expression. */
            memory.totalKiB() / 1048576.0, /* Provides the next part of the current expression. */
            memory.usedKiB() / 1048576.0, /* Provides the next part of the current expression. */
            memory.usedPercent() /* Calls this operation with the values shown. */
        ); /* Provides the next part of the current expression. */
    } /* This brace ends a code block. */

    /* Formats an ID consistently as a four-digit hexadecimal value. */
    private static String hex(int id) { /* Declares the hex method. */
        return String.format(Locale.ROOT, "0x%04x", id); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Builds the PCI device list shown on the PCI tab. */
    private static String pciInfo(List<SystemSnapshot.PciDevice> devices) { /* Declares the pciInfo method. */
        StringBuilder output = new StringBuilder(); /* Stores the calculated value in output. */
        output.append("PCI devices (").append(devices.size()).append(")\n"); /* Adds this value to the output being built. */
        if (devices.isEmpty()) output.append("No PCI devices found.\n"); /* Checks this condition before choosing what to do. */
        for (SystemSnapshot.PciDevice d : devices) { /* Repeats this work for each matching value. */
            output.append( /* Adds this value to the output being built. */
                String.format( /* Uses the named Java API to perform this operation. */
                    Locale.ROOT, /* Provides the next part of the current expression. */
                    "%02x:%02x.%x  %s (%s), %s (%s), class %06x%n", /* Provides the next part of the current expression. */
                    d.bus(), /* Provides the next part of the current expression. */
                    d.device(), /* Provides the next part of the current expression. */
                    d.function(), /* Provides the next part of the current expression. */
                    d.vendorName(), /* Provides the next part of the current expression. */
                    hex(d.vendor()), /* Provides the next part of the current expression. */
                    d.name(), /* Provides the next part of the current expression. */
                    hex(d.id()), /* Provides the next part of the current expression. */
                    d.deviceClass() /* Calls this operation with the values shown. */
                ) /* Provides the next part of the current expression. */
            ); /* Provides the next part of the current expression. */
        } /* This brace ends a code block. */
        appendPciAnalysis(output, devices); /* Calls this operation with the values shown. */
        return output.toString(); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Builds the USB device list and handles systems without USB devices. */
    private static String usbInfo(List<SystemSnapshot.UsbDevice> devices) { /* Declares the usbInfo method. */
        StringBuilder output = new StringBuilder(); /* Stores the calculated value in output. */
        output.append("USB devices (").append(devices.size()).append(")\n"); /* Adds this value to the output being built. */
        if (devices.isEmpty()) output.append("No USB devices found.\n"); /* Checks this condition before choosing what to do. */
        for (SystemSnapshot.UsbDevice d : devices) { /* Repeats this work for each matching value. */
            output.append( /* Adds this value to the output being built. */
                String.format( /* Uses the named Java API to perform this operation. */
                    "%s (%s), %s (%s)%n", /* Provides the next part of the current expression. */
                    d.vendorName(), /* Provides the next part of the current expression. */
                    hex(d.vendor()), /* Provides the next part of the current expression. */
                    d.name(), /* Provides the next part of the current expression. */
                    hex(d.id()) /* Calls this operation with the values shown. */
                ) /* Provides the next part of the current expression. */
            ); /* Provides the next part of the current expression. */
        } /* This brace ends a code block. */
        return output.toString(); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* builds the disk tab with one block per mounted filesystem */
    private static String diskInfo(List<SystemSnapshot.Disk> disks) { /* Declares the diskInfo method. */
        StringBuilder output = new StringBuilder(); /* Stores the calculated value in output. */
        output.append("Mounted filesystems: ").append(disks.size()).append('\n'); /* Adds this value to the output being built. */
        
        if (disks.isEmpty()) { /* Checks this condition before choosing what to do. */
            output.append("No filesystems to display.\n"); /* Adds this value to the output being built. */
        } /* This brace ends a code block. */

        for (SystemSnapshot.Disk d : disks) { /* Repeats this work for each matching value. */
            output.append('\n'); /* Adds this value to the output being built. */
            output.append("Mount: ").append(cleanText(d.mountPoint())).append('\n'); /* Adds this value to the output being built. */
            output.append(" Device: ").append(cleanText(d.device())).append('\n'); /* Adds this value to the output being built. */
            output.append(" Type: ").append(d.fsType()).append('\n'); /* Adds this value to the output being built. */

            if (d.totalBytes() == null) { /* Checks this condition before choosing what to do. */
                /* mount exists but size is unknown */
                output.append(" Usage: unavailable (").append(d.note()).append(")\n"); /* Adds this value to the output being built. */
            } /* This brace ends a code block. */
            else { /* Checks this condition before choosing what to do. */
                output.append(" Total: ").append(formatBytes(d.totalBytes())).append('\n'); /* Adds this value to the output being built. */
                output.append(" Used: ").append(formatBytes(d.usedBytes())); /* Adds this value to the output being built. */
                output.append(String.format(Locale.ROOT, " (%.1f%%)", d.usedPercent())); /* Adds this value to the output being built. */
                output.append('\n'); /* Adds this value to the output being built. */
                output.append(" Available: ").append(formatBytes(d.availableBytes())).append('\n'); /* Adds this value to the output being built. */

                } /* This brace ends a code block. */
            } /* This brace ends a code block. */
            return output.toString(); /* Returns this value to the caller. */
        } /* This brace ends a code block. */

        /* replaces control characters with "?". */
        private static String cleanText(String text) { /* Declares the cleanText method. */
            return text.replaceAll("\\p{Cntrl}","?"); /* Returns this value to the caller. */
        } /* This brace ends a code block. */

        /* converts bytecount into more readable units */
        private static String formatBytes(long bytes) { /* Declares the formatBytes method. */
            String[] units = {"B" , "KiB" , "MiB", "GiB" ,"TiB" , "PiB" }; /* Stores the calculated value in units. */
            double value = bytes; /* Stores the calculated value in value. */
            int unit = 0; /* Stores the calculated value in unit. */
            while (value >= 1024 && unit < units.length -1) { /* Repeats this work for each matching value. */
                value = value / 1024; /* Sets or updates value. */
                unit = unit + 1; /* Sets or updates unit. */
            } /* This brace ends a code block. */
            if (unit == 0) { /* Checks this condition before choosing what to do. */
              return bytes + "B"; /* Returns this value to the caller. */
            } /* This brace ends a code block. */
            return String.format(Locale.ROOT, "%.1f %s", value, units[unit]); /* Returns this value to the caller. */
        } /* This brace ends a code block. */


    /* Appends PCI vendor counts and both requested orderings to the PCI tab. */
    private static void appendPciAnalysis( /* Declares the appendPciAnalysis method. */
        StringBuilder output, /* Provides the next part of the current expression. */
        List<SystemSnapshot.PciDevice> devices /* Continues the current operation. */
    ) { /* Provides the next part of the current expression. */
        Comparator<SystemSnapshot.PciDevice> address = /* Stores the calculated value in address. */
            Comparator.comparingInt(SystemSnapshot.PciDevice::bus) /* Calls this operation with the values shown. */
            .thenComparingInt(SystemSnapshot.PciDevice::device) /* Provides the next part of the current expression. */
            .thenComparingInt(SystemSnapshot.PciDevice::function); /* Provides the next part of the current expression. */
        Map<String, Integer> counts = new TreeMap<>( /* Stores the calculated value in counts. */
            String.CASE_INSENSITIVE_ORDER /* Uses the named Java API to perform this operation. */
        ); /* Provides the next part of the current expression. */
        for (SystemSnapshot.PciDevice d : devices) { /* Repeats this work for each matching value. */
            counts.merge(d.vendorName(), 1, Integer::sum); /* Calls this operation with the values shown. */
        } /* This brace ends a code block. */
        output.append("\nDevices per vendor:\n"); /* Adds this value to the output being built. */
        counts.forEach((vendor, count) -> /* Defines the work for this case or callback. */
            output /* Continues the current operation. */
                .append("  ") /* Adds this value to the output being built. */
                .append(vendor) /* Adds this value to the output being built. */
                .append(": ") /* Adds this value to the output being built. */
                .append(count) /* Adds this value to the output being built. */
                .append('\n') /* Adds this value to the output being built. */
        ); /* Provides the next part of the current expression. */

        // Use bus, device, and function as deterministic tie-breakers for vendor ordering.
        output.append("\nSorted alphabetically by vendor:\n"); /* Adds this value to the output being built. */
        devices /* Continues the current operation. */
            .stream() /* Provides the next part of the current expression. */
            .sorted( /* Provides the next part of the current expression. */
                Comparator.comparing( /* Calls this operation with the values shown. */
                    SystemSnapshot.PciDevice::vendorName, /* Provides the next part of the current expression. */
                    String.CASE_INSENSITIVE_ORDER /* Uses the named Java API to perform this operation. */
                ).thenComparing(address) /* Provides the next part of the current expression. */
            ) /* Provides the next part of the current expression. */
            .forEach(d -> appendAddress(output, d)); /* Defines the work for this case or callback. */
        output.append("\nSorted by bus, device, and function:\n"); /* Adds this value to the output being built. */
        devices /* Continues the current operation. */
            .stream() /* Provides the next part of the current expression. */
            .sorted(address) /* Provides the next part of the current expression. */
            .forEach(d -> appendAddress(output, d)); /* Defines the work for this case or callback. */
    } /* This brace ends a code block. */

    /* Appends one PCI device and its address to an analysis section. */
    private static void appendAddress( /* Declares the appendAddress method. */
        StringBuilder output, /* Provides the next part of the current expression. */
        SystemSnapshot.PciDevice d /* Continues the current operation. */
    ) { /* Provides the next part of the current expression. */
        output.append( /* Adds this value to the output being built. */
            String.format( /* Uses the named Java API to perform this operation. */
                Locale.ROOT, /* Provides the next part of the current expression. */
                "  %s %s at %02x:%02x.%x%n", /* Provides the next part of the current expression. */
                d.vendorName(), /* Provides the next part of the current expression. */
                d.name(), /* Provides the next part of the current expression. */
                d.bus(), /* Provides the next part of the current expression. */
                d.device(), /* Provides the next part of the current expression. */
                d.function() /* Calls this operation with the values shown. */
            ) /* Provides the next part of the current expression. */
        ); /* Provides the next part of the current expression. */
    } /* This brace ends a code block. */

    /* Starts first collection in the command executor, never on the UI thread. */
    @Override /* Marks the declaration below for the Java compiler. */
    public Command init() { /* Declares the init method. */
        //logHardwareRecord is set to true so that the initial snapshot is logged
        return refreshCommand(previousCpuTimes, true); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Handles terminal resizing, tab navigation, scrolling, and quit input. */
    @Override /* Marks the declaration below for the Java compiler. */
    public UpdateResult<? extends Model> update(Message message) { /* Declares the update method. */
        if (message instanceof RefreshMessage refresh) { /* Checks this condition before choosing what to do. */
            if (refresh.snapshot() != null) { /* Checks this condition before choosing what to do. */
                sections.clear(); /* Calls this operation with the values shown. */
                sections.addAll(refresh.snapshot().sections()); /* Calls this operation with the values shown. */
                previousCpuTimes = refresh.snapshot().collection().cpuTimes(); /* Sets or updates previousCpuTimes. */
                scroll = Math.min(scroll, maxScroll()); /* Sets or updates scroll. */
                refreshStatus = /* Sets or updates refreshStatus. */
                    refresh.error() == null /* Calls this operation with the values shown. */
                        ? "Updated " + LocalTime.now().withNano(0) /* Calls this operation with the values shown. */
                        : "JSON export failed: " + /* Provides the next part of the current expression. */
                            refresh.error() + /* Provides the next part of the current expression. */
                            " (last file remains unchanged)"; /* Calls this operation with the values shown. */
            } else { /* Provides the next part of the current expression. */
                refreshStatus = /* Sets or updates refreshStatus. */
                    "Refresh failed: " + /* Provides the next part of the current expression. */
                    refresh.error() + /* Provides the next part of the current expression. */
                    " (showing last successful data)"; /* Calls this operation with the values shown. */
            } /* This brace ends a code block. */
            return UpdateResult.from( /* Returns this value to the caller. */
                this, /* Provides the next part of the current expression. */
                Command.tick(REFRESH_INTERVAL, ignored -> new RefreshTick()) /* Defines the work for this case or callback. */
            ); /* Provides the next part of the current expression. */
        } /* This brace ends a code block. */
        if (message instanceof RefreshTick) { /* Checks this condition before choosing what to do. */
            refreshStatus = "Refreshing..."; /* Sets or updates refreshStatus. */
            //logHardwareRecord set to false so that the csv file is not constantly updates upon each refresh
            return UpdateResult.from(this, refreshCommand(previousCpuTimes, false)); /* Returns this value to the caller. */
        } /* This brace ends a code block. */
        if (message instanceof WindowSizeMessage size) { /* Checks this condition before choosing what to do. */
            terminalWidth = Math.max(1, size.width()); /* Sets or updates terminalWidth. */
            terminalHeight = Math.max(1, size.height()); /* Sets or updates terminalHeight. */
            scroll = Math.min(scroll, maxScroll()); /* Sets or updates scroll. */
            return UpdateResult.from(this); /* Returns this value to the caller. */
        } /* This brace ends a code block. */
        if (!(message instanceof KeyPressMessage key)) { /* Checks this condition before choosing what to do. */
            return UpdateResult.from(this); /* Returns this value to the caller. */
        } /* This brace ends a code block. */

        switch (key.key()) { /* Selects or handles one of the available cases. */
            case "q", "Q", "esc", "ctrl+c" -> { /* Selects or handles one of the available cases. */
                return UpdateResult.from(this, QuitMessage::new); /* Returns this value to the caller. */
            } /* This brace ends a code block. */
            case "right", "l", "tab" -> selectTab( /* Selects or handles one of the available cases. */
                (selectedTab + 1) % TABS.length /* Calls this operation with the values shown. */
            ); /* Provides the next part of the current expression. */
            case "left", "h" -> selectTab( /* Selects or handles one of the available cases. */
                (selectedTab + TABS.length - 1) % TABS.length /* Calls this operation with the values shown. */
            ); /* Provides the next part of the current expression. */
            case "down", "j" -> scroll = Math.min(maxScroll(), scroll + 1); /* Selects or handles one of the available cases. */
            case "up", "k" -> scroll = Math.max(0, scroll - 1); /* Selects or handles one of the available cases. */
            case "pagedown", "pgdown", "ctrl+d" -> scroll = Math.min( /* Selects or handles one of the available cases. */
                maxScroll(), /* Provides the next part of the current expression. */
                scroll + pageSize() /* Calls this operation with the values shown. */
            ); /* Provides the next part of the current expression. */
            case "pageup", "pgup", "ctrl+u" -> scroll = Math.max( /* Selects or handles one of the available cases. */
                0, /* Provides the next part of the current expression. */
                scroll - pageSize() /* Calls this operation with the values shown. */
            ); /* Provides the next part of the current expression. */
            case "home" -> scroll = 0; /* Selects or handles one of the available cases. */
            case "end" -> scroll = maxScroll(); /* Selects or handles one of the available cases. */
            default -> { /* Selects or handles one of the available cases. */
                return UpdateResult.from(this); /* Returns this value to the caller. */
            } /* This brace ends a code block. */
        } /* This brace ends a code block. */
        return UpdateResult.from(this); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Renders the active report section within the current terminal dimensions. */
    @Override /* Marks the declaration below for the Java compiler. */
    public String view() { /* Declares the view method. */
        if ( /* Checks this condition before choosing what to do. */
            terminalWidth < MIN_TERMINAL_WIDTH || /* Provides the next part of the current expression. */
            terminalHeight < MIN_TERMINAL_HEIGHT /* Continues the current operation. */
        ) { /* Provides the next part of the current expression. */
            return ( /* Returns this value to the caller. */
                TITLE.render("System Fetch") + /* Provides the next part of the current expression. */
                "\n" + /* Provides the next part of the current expression. */
                "Terminal too small. Resize to at least " + /* Provides the next part of the current expression. */
                MIN_TERMINAL_WIDTH + /* Provides the next part of the current expression. */
                "x" + /* Provides the next part of the current expression. */
                MIN_TERMINAL_HEIGHT + /* Provides the next part of the current expression. */
                ".\n" + /* Provides the next part of the current expression. */
                "Press q to quit." /* Continues the current operation. */
            ); /* Provides the next part of the current expression. */
        } /* This brace ends a code block. */

        StringBuilder output = new StringBuilder(); /* Stores the calculated value in output. */
        output /* Continues the current operation. */
            .append(TITLE.render("SYSTEM FETCH")) /* Adds this value to the output being built. */
            .append("  ") /* Adds this value to the output being built. */
            .append(HELP.render("LINUX SYSTEM OVERVIEW")) /* Adds this value to the output being built. */
            .append('\n'); /* Adds this value to the output being built. */
        output.append(ACCENT.render("─".repeat(terminalWidth))).append('\n'); /* Adds this value to the output being built. */
        // Short labels keep the complete tab row readable in narrower terminals.
        String[] shortTabs = { "CPU", "RAM", "PCI", "USB", "DSK" }; /* Stores the calculated value in shortTabs. */
        for (int i = 0; i < TABS.length; i++) { /* Repeats this work for each matching value. */
            String tab = /* Stores the calculated value in tab. */
                terminalWidth < 64 /* Continues the current operation. */
                    ? shortTabs[i] /* Continues the current operation. */
                    : TABS[i].toUpperCase(Locale.ROOT); /* Calls this operation with the values shown. */
            output.append( /* Adds this value to the output being built. */
                i == selectedTab /* Sets or updates i. */
                    ? ACTIVE_TAB.render(" " + tab + " ") /* Calls this operation with the values shown. */
                    : INACTIVE_TAB.render(" " + tab + " ") /* Calls this operation with the values shown. */
            ); /* Provides the next part of the current expression. */
            output.append(i == TABS.length - 1 ? '\n' : ' '); /* Adds this value to the output being built. */
        } /* This brace ends a code block. */
        output.append(ACCENT.render("─".repeat(terminalWidth))).append('\n'); /* Adds this value to the output being built. */
        output /* Continues the current operation. */
            .append( /* Adds this value to the output being built. */
                SECTION_TITLE.render(TABS[selectedTab].toUpperCase(Locale.ROOT)) /* Calls this operation with the values shown. */
            ) /* Provides the next part of the current expression. */
            .append('\n'); /* Adds this value to the output being built. */

        List<String> lines = wrappedLines(); /* Stores the calculated value in lines. */
        // Reserve rows for the title, tab rail, section heading, dividers, and key help.
        int visibleLines = Math.max(1, terminalHeight - 9); /* Stores the calculated value in visibleLines. */
        int start = Math.min(scroll, Math.max(0, lines.size() - visibleLines)); /* Stores the calculated value in start. */
        int end = Math.min(lines.size(), start + visibleLines); /* Stores the calculated value in end. */
        for (int i = start; i < end; i++) { /* Repeats this work for each matching value. */
            output.append(styleReportLine(lines.get(i))).append('\n'); /* Adds this value to the output being built. */
        } /* This brace ends a code block. */
        output.append(ACCENT.render("─".repeat(terminalWidth))).append('\n'); /* Adds this value to the output being built. */
        output.append( /* Adds this value to the output being built. */
            HELP.render(" ←/→ or Tab switch tabs   ↑/↓ scroll   q quit") /* Calls this operation with the values shown. */
        ).append('\n'); /* Adds this value to the output being built. */
        String status = refreshStatus; /* Stores the calculated value in status. */
        if (status.codePointCount(0, status.length()) > terminalWidth) { /* Checks this condition before choosing what to do. */
            status = status.substring( /* Sets or updates status. */
                0, /* Provides the next part of the current expression. */
                status.offsetByCodePoints(0, terminalWidth) /* Calls this operation with the values shown. */
            ); /* Provides the next part of the current expression. */
        } /* This brace ends a code block. */
        output.append(HELP.render(status)); /* Adds this value to the output being built. */
        return output.toString(); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Collects a snapshot in the command executor and reports failures to the UI. */
    private Command refreshCommand( /* Declares the refreshCommand method. */
        Map<Integer, SystemSnapshot.CpuTicks> previousTimes, /* Provides the next part of the current expression. */
        boolean logHardwareRecord /* Continues the current operation. */
    ) { /* Provides the next part of the current expression. */
        return () -> { /* Returns this value to the caller. */
            Snapshot snapshot; /* Continues the current operation. */
            try { /* Starts work that may need error handling or resource cleanup. */
                snapshot = createReport(collector.collect(previousTimes)); /* Sets or updates snapshot. */
                //Where appendRecord is called, this information takes the snapshot data and appends it to hardware-history.csv. See HardwareLogger file.
                 if (logHardwareRecord) { /* Checks this condition before choosing what to do. */
                    new HardwareLogger().appendRecord(snapshot.data()); /* Creates a new value for the caller or current operation. */
                } /* This brace ends a code block. */
            } catch (IOException | RuntimeException e) { /* Provides the next part of the current expression. */
                String error = e.getMessage(); /* Stores the calculated value in error. */
                return new RefreshMessage( /* Returns this value to the caller. */
                    null, /* Provides the next part of the current expression. */
                    error == null ? e.getClass().getSimpleName() : error /* Sets or updates error. */
                ); /* Provides the next part of the current expression. */
            } /* This brace ends a code block. */
            if (jsonFile != null) { /* Checks this condition before choosing what to do. */
                try { /* Starts work that may need error handling or resource cleanup. */
                    JsonSnapshotWriter.writeAtomically(jsonFile, snapshot.data()); /* Calls this operation with the values shown. */
                } catch (IOException | RuntimeException e) { /* Provides the next part of the current expression. */
                    String error = e.getMessage(); /* Stores the calculated value in error. */
                    return new RefreshMessage( /* Returns this value to the caller. */
                        snapshot, /* Provides the next part of the current expression. */
                        error == null ? e.getClass().getSimpleName() : error /* Sets or updates error. */
                    ); /* Provides the next part of the current expression. */
                } /* This brace ends a code block. */
            } /* This brace ends a code block. */
            
            return new RefreshMessage(snapshot, null); /* Returns this value to the caller. */
        }; /* Provides the next part of the current expression. */
    } /* This brace ends a code block. */

    /* Highlights headings and labels while leaving device descriptions easy to read. */
    private static String styleReportLine(String line) { /* Declares the styleReportLine method. */
        String trimmed = line.trim(); /* Stores the calculated value in trimmed. */
        // PCI analysis and device-list headings share the same section-title style.
        if ( /* Checks this condition before choosing what to do. */
            trimmed.endsWith(":") || /* Provides the next part of the current expression. */
            trimmed.startsWith("PCI devices (") || /* Provides the next part of the current expression. */
            trimmed.startsWith("USB devices (") /* Calls this operation with the values shown. */
        ) { /* Provides the next part of the current expression. */
            return SECTION_TITLE.render(line); /* Returns this value to the caller. */
        } /* This brace ends a code block. */
        if (trimmed.startsWith("CPU ") && trimmed.contains(":")) { /* Checks this condition before choosing what to do. */
            // Highlight the CPU label without changing its measured percentage.
            int separator = line.indexOf(':'); /* Stores the calculated value in separator. */
            return ( /* Returns this value to the caller. */
                LABEL.render(line.substring(0, separator + 1)) + /* Provides the next part of the current expression. */
                line.substring(separator + 1) /* Calls this operation with the values shown. */
            ); /* Provides the next part of the current expression. */
        } /* This brace ends a code block. */
        // Keep short key/value labels distinct from longer device descriptions.
        int separator = line.indexOf(": "); /* Stores the calculated value in separator. */
        if (separator > 0 && separator < 32) { /* Checks this condition before choosing what to do. */
            return ( /* Returns this value to the caller. */
                LABEL.render(line.substring(0, separator + 1)) + /* Provides the next part of the current expression. */
                line.substring(separator + 1) /* Calls this operation with the values shown. */
            ); /* Provides the next part of the current expression. */
        } /* This brace ends a code block. */
        return line; /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Switches tabs and resets the scroll position for the newly selected section. */
    private void selectTab(int tab) { /* Declares the selectTab method. */
        selectedTab = tab; /* Sets or updates selectedTab. */
        scroll = 0; /* Sets or updates scroll. */
    } /* This brace ends a code block. */

    /* Leaves room on screen for the title, tabs, and key-help line. */
    private int pageSize() { /* Declares the pageSize method. */
        return Math.max(1, terminalHeight - 11); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Computes the scroll limit from the active section and terminal size. */
    private int maxScroll() { /* Declares the maxScroll method. */
        // Match the reserved rows used to calculate the visible content area.
        return Math.max( /* Returns this value to the caller. */
            0, /* Provides the next part of the current expression. */
            wrappedLines().size() - Math.max(1, terminalHeight - 9) /* Calls this operation with the values shown. */
        ); /* Provides the next part of the current expression. */
    } /* This brace ends a code block. */

    /* Wraps report lines so narrow terminals can still display every field. */
    private List<String> wrappedLines() { /* Declares the wrappedLines method. */
        int width = Math.max(1, terminalWidth - 1); /* Stores the calculated value in width. */
        List<String> result = new ArrayList<>(); /* Stores the calculated value in result. */
        for (String line : sections.get(selectedTab).split("\\R", -1)) { /* Repeats this work for each matching value. */
            int start = 0; /* Stores the calculated value in start. */
            // Count Unicode code points so wrapping does not split a multi-byte character.
            while (line.codePointCount(start, line.length()) > width) { /* Repeats this work for each matching value. */
                int end = line.offsetByCodePoints(start, width); /* Stores the calculated value in end. */
                result.add(line.substring(start, end)); /* Calls this operation with the values shown. */
                start = end; /* Sets or updates start. */
            } /* This brace ends a code block. */
            result.add(line.substring(start)); /* Calls this operation with the values shown. */
        } /* This brace ends a code block. */
        return result; /* Returns this value to the caller. */
    } /* This brace ends a code block. */
} /* This brace ends a code block. */
