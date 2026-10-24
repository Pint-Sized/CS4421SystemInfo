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
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

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
        "Disk",
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

    /* Loads hardware IDs and system data, then runs the interactive or text report. */
    public static void main(String[] args)
        throws IOException, InterruptedException {
        Options options = parseOptions(args);
        Path jsonFile = options.jsonFile();
        Map<Integer, SystemInfoCollector.Vendor> pciNames =
            SystemInfoCollector.loadIds(
                "pci.ids",
                "https://pci-ids.ucw.cz/v2.2/pci.ids"
            );
        Map<Integer, SystemInfoCollector.Vendor> usbNames =
            SystemInfoCollector.loadIds(
                "usb.ids",
                "https://raw.githubusercontent.com/vcrhonek/hwdata/master/usb.ids"
            );
        SystemInfoCollector collector = new SystemInfoCollector(
            pciNames,
            usbNames
        );

        if (
            options.plainText() ||
            System.console() == null ||
            "dumb".equals(System.getenv("TERM"))
        ) {
            Snapshot snapshot = collectPlainSnapshot(collector);
            if (jsonFile != null) {
                JsonSnapshotWriter.writeAtomically(jsonFile, snapshot.data());
            }
            printPlainReport(snapshot.sections());
            return;
        }
        try {
            new Program(new SystemFetch(collector, jsonFile))
                .withAltScreen()
                .run();
        } catch (ProgramException e) {
            System.err.println(
                "Interactive terminal unavailable; printing plain-text report."
            );
            Snapshot snapshot = collectPlainSnapshot(collector);
            if (jsonFile != null) {
                JsonSnapshotWriter.writeAtomically(jsonFile, snapshot.data());
            }
            printPlainReport(snapshot.sections());
        }
    }

    /* Samples CPU counters twice for a useful initial plain-text load report. */
    private static Snapshot collectPlainSnapshot(SystemInfoCollector collector)
        throws IOException, InterruptedException {
        SystemSnapshot.CollectionResult baseline = collector.collect(Map.of());
        Thread.sleep(1000);
        return createReport(collector.collect(baseline.cpuTimes()));
    }

    private record Options(Path jsonFile, boolean plainText) {}

    /* Parses output options and normalizes the optional JSON export path. */
    private static Options parseOptions(String[] args) {
        Path jsonFile = null;
        boolean plainText = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--plain" -> plainText = true;
                case "--json-file" -> {
                    if (jsonFile != null || i + 1 >= args.length) {
                        throw new IllegalArgumentException(
                            "Usage: SystemFetch [--plain] [--json-file <path>]"
                        );
                    }
                    jsonFile = Path.of(args[++i]).toAbsolutePath();
                }
                default -> throw new IllegalArgumentException(
                    "Unknown option: " +
                        args[i] +
                        "\nUsage: SystemFetch [--plain] [--json-file <path>]"
                );
            }
        }
        return new Options(jsonFile, plainText);
    }

    /* Prints all sections sequentially when a full-screen terminal is unavailable. */
    /* Prints each report section in tab order for non-interactive use. */
    private static void printPlainReport(List<String> sections) {
        for (int i = 0; i < sections.size(); i++) {
            System.out.println("\n" + TABS[i]);
            System.out.println(sections.get(i));
        }
    }

    private record Snapshot(
        List<String> sections,
        SystemSnapshot.CollectionResult collection
    ) {
        /* Exposes the data snapshot separately from its terminal presentation. */
        SystemSnapshot data() {
            return collection.snapshot();
        }
    }

    private record RefreshMessage(
        Snapshot snapshot,
        String error
    ) implements Message {}

    private record RefreshTick() implements Message {}

    /* Current report content and terminal navigation state. */
    private final List<String> sections;
    private final SystemInfoCollector collector;
    private final Path jsonFile;
    private Map<Integer, SystemSnapshot.CpuTicks> previousCpuTimes = Map.of();
    private String refreshStatus = "Collecting system information...";
    private int selectedTab;
    private int terminalWidth = 80;
    private int terminalHeight = 24;
    private int scroll;

    /* Starts with placeholders so the TUI can render before collection completes. */
    private SystemFetch(
        SystemInfoCollector collector,
        Path jsonFile
    ) {
        this.collector = collector;
        this.jsonFile = jsonFile;
        this.sections = new ArrayList<>(
            List.of(
                "Collecting processor information...",
                "Collecting memory information...",
                "Collecting PCI devices...",
                "Collecting USB devices...",
                "Collecting Disk information..."
            )
        );
    }

    /* Prepares separate terminal-formatted sections from a collected snapshot. */
    private static Snapshot createReport(
        SystemSnapshot.CollectionResult collection
    ) {
        SystemSnapshot data = collection.snapshot();
        return new Snapshot(
            List.of(
                processorInfo(data.processor()),
                memoryInfo(data.memory()),
                pciInfo(data.pci()),
                usbInfo(data.usb()),
                diskInfo(data.disks())
            ),
            collection
        );
    }

    /* Renders processor topology and loads for the terminal report. */
    private static String processorInfo(SystemSnapshot.Processor info) {
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
        for (SystemSnapshot.CpuLoad load : info.logicalLoads()) {
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
            for (SystemSnapshot.CoreLoad load : info.physicalLoads()) {
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

    /* Formats memory values for the terminal report. */
    private static String memoryInfo(SystemSnapshot.Memory memory) {
        return String.format(
            Locale.ROOT,
            "Total: %.1f GiB%nUsed: %.1f GiB (%.1f%%)",
            memory.totalKiB() / 1048576.0,
            memory.usedKiB() / 1048576.0,
            memory.usedPercent()
        );
    }

    /* Formats an ID consistently as a four-digit hexadecimal value. */
    private static String hex(int id) {
        return String.format(Locale.ROOT, "0x%04x", id);
    }

    /* Builds the PCI device list shown on the PCI tab. */
    private static String pciInfo(List<SystemSnapshot.PciDevice> devices) {
        StringBuilder output = new StringBuilder();
        output.append("PCI devices (").append(devices.size()).append(")\n");
        if (devices.isEmpty()) output.append("No PCI devices found.\n");
        for (SystemSnapshot.PciDevice d : devices) {
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
    private static String usbInfo(List<SystemSnapshot.UsbDevice> devices) {
        StringBuilder output = new StringBuilder();
        output.append("USB devices (").append(devices.size()).append(")\n");
        if (devices.isEmpty()) output.append("No USB devices found.\n");
        for (SystemSnapshot.UsbDevice d : devices) {
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

    /* builds the disk tab with one block per mounted filesystem */
    private static String diskInfo(List<SystemSnapshot.Disk> disks) {
        StringBuilder output = new StringBuilder();
        output.append("Mounted filesystems: ").append(disks.size()).append('\n');
        
        if (disks.isEmpty()) {
            output.append("No filesystems to display.\n");
        }

        for (SystemSnapshot.Disk d : disks) {
            output.append('\n');
            output.append("Mount: ").append(cleanText(d.mountPoint())).append('\n');    
            output.append(" Device: ").append(cleanText(d.device())).append('\n');    
            output.append(" Type: ").append(d.fsType()).append('\n');

            if (d.totalBytes() == null) {
                /* mount exists but size is unknown */
                output.append(" Usage: unavailable (").append(d.note()).append(")\n");
            }
            else {
                output.append(" Total: ").append(formatBytes(d.totalBytes())).append('\n');
                output.append(" Used: ").append(formatBytes(d.usedBytes()));
                output.append(String.format(Locale.ROOT, " (%.1f%%)", d.usedPercent()));
                output.append('\n');
                output.append(" Available: ").append(formatBytes(d.availableBytes())).append('\n');

                }    
            }        
            return output.toString();
        }

        /* replaces control characters with "?". */
        private static String cleanText(String text) {
            return text.replaceAll("\\p{Cntrl}","?");
        }

        /* converts bytecount into more readable units */
        private static String formatBytes(long bytes) {
            String[] units = {"B" , "KiB" , "MiB", "GiB" ,"TiB" , "PiB" };
            double value = bytes;
            int unit = 0;
            while (value >= 1024 && unit < units.length -1) {
                value = value / 1024;
                unit = unit + 1;
            }
            if (unit == 0) {
              return bytes + "B";           
            }
            return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
        }


    /* Appends PCI vendor counts and both requested orderings to the PCI tab. */
    private static void appendPciAnalysis(
        StringBuilder output,
        List<SystemSnapshot.PciDevice> devices
    ) {
        Comparator<SystemSnapshot.PciDevice> address =
            Comparator.comparingInt(SystemSnapshot.PciDevice::bus)
            .thenComparingInt(SystemSnapshot.PciDevice::device)
            .thenComparingInt(SystemSnapshot.PciDevice::function);
        Map<String, Integer> counts = new TreeMap<>(
            String.CASE_INSENSITIVE_ORDER
        );
        for (SystemSnapshot.PciDevice d : devices) {
            counts.merge(d.vendorName(), 1, Integer::sum);
        }
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
                    SystemSnapshot.PciDevice::vendorName,
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
    private static void appendAddress(
        StringBuilder output,
        SystemSnapshot.PciDevice d
    ) {
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
                previousCpuTimes = refresh.snapshot().collection().cpuTimes();
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
        String[] shortTabs = { "CPU", "RAM", "PCI", "USB", "DSK" };
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
            status = status.substring(
                0,
                status.offsetByCodePoints(0, terminalWidth)
            );
        }
        output.append(HELP.render(status));
        return output.toString();
    }

    /* Collects a snapshot in the command executor and reports failures to the UI. */
    private Command refreshCommand(
        Map<Integer, SystemSnapshot.CpuTicks> previousTimes
    ) {
        return () -> {
            Snapshot snapshot;
            try {
                snapshot = createReport(collector.collect(previousTimes));
            } catch (IOException | RuntimeException e) {
                String error = e.getMessage();
                return new RefreshMessage(
                    null,
                    error == null ? e.getClass().getSimpleName() : error
                );
            }
            if (jsonFile != null) {
                try {
                    JsonSnapshotWriter.writeAtomically(jsonFile, snapshot.data());
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
