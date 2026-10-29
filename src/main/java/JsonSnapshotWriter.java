import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/* Serializes a system snapshot and atomically replaces the requested JSON file. */
final class JsonSnapshotWriter {

    /* Utility class; instances do not hold state. */
    private JsonSnapshotWriter() {}

    /* Writes beside the target so the final rename stays on the same filesystem. */
    static void writeAtomically(Path target, SystemSnapshot snapshot)
        throws IOException {
        // The temporary file shares the destination directory for same-filesystem rename.
        Path parent = target.toAbsolutePath().getParent();
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

    /* Serializes each snapshot section in the documented, stable schema order. */
    private static String toJson(SystemSnapshot snapshot) {
        SystemSnapshot.Processor processor = snapshot.processor();
        SystemSnapshot.Memory memory = snapshot.memory();
        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"schema_version\": 1,\n  \"generated_at\": ")
            .append(jsonString(Instant.now().toString()))
            .append(",\n  \"processor\": {\n");
        // Keep processor properties ordered consistently for readers and diffs.
        json.append("    \"model\": ").append(jsonString(processor.model())).append(",\n");
        json.append("    \"logical_cpus\": ").append(processor.logicalCpus()).append(",\n");
        json.append("    \"physical_cores\": ")
            .append(processor.physicalCores() == null ? "null" : processor.physicalCores())
            .append(",\n    \"physical_topology_available\": ")
            .append(processor.topologyAvailable())
            .append(",\n    \"physical_topology_complete\": ")
            .append(processor.topologyComplete())
            .append(",\n    \"logical_cpu_load_percent\": [");
        // Emit one utilization object for each sampled logical CPU.
        for (int i = 0; i < processor.logicalLoads().size(); i++) {
            SystemSnapshot.CpuLoad load = processor.logicalLoads().get(i);
            if (i > 0) json.append(',');
            json.append("\n      {\"id\": ")
                .append(load.id())
                .append(", \"utilization\": ")
                .append(load.utilization())
                .append('}');
        }
        appendArrayClose(json, processor.logicalLoads());
        json.append(",\n    \"average_logical_load_percent\": ")
            .append(jsonNumber(processor.averageLogicalLoad()))
            .append(",\n    \"physical_core_load_percent\": [");
        // Physical-core values aggregate the logical CPUs mapped to each core.
        for (int i = 0; i < processor.physicalLoads().size(); i++) {
            SystemSnapshot.CoreLoad load = processor.physicalLoads().get(i);
            if (i > 0) json.append(',');
            json.append("\n      {\"name\": ")
                .append(jsonString(load.name()))
                .append(", \"utilization\": ")
                .append(load.utilization())
                .append('}');
        }
        appendArrayClose(json, processor.physicalLoads());
        json.append(",\n    \"average_physical_core_load_percent\": ")
            .append(jsonNumber(processor.averagePhysicalLoad()))
            .append("\n  },\n  \"memory\": {\n")
            .append("    \"total_kib\": ")
            .append(memory.totalKiB())
            .append(",\n    \"used_kib\": ")
            .append(memory.usedKiB())
            .append(",\n    \"used_percent\": ")
            .append(memory.usedPercent())
            .append("\n  },\n  \"pci_devices\": [");
        // Hardware IDs are strings so hexadecimal notation and leading zeroes survive.
        // PCI and USB entries keep resolved names alongside the original IDs.
        for (int i = 0; i < snapshot.pci().size(); i++) {
            SystemSnapshot.PciDevice device = snapshot.pci().get(i);
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
        appendArrayClose(json, snapshot.pci());
        json.append(",\n  \"usb_devices\": [");
        for (int i = 0; i < snapshot.usb().size(); i++) {
            SystemSnapshot.UsbDevice device = snapshot.usb().get(i);
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
        appendArrayClose(json, snapshot.usb());

        json.append(",\n \"disks\": [");
        // Nullable usage fields preserve mounts whose capacity could not be read.
        for (int i =0; i < snapshot.disks().size(); i++) {
            SystemSnapshot.Disk disk = snapshot.disks().get(i);
            if (i > 0) json.append(',');
            json.append("\n     {\"device\": ")
                .append(jsonString(disk.device()))
                .append(", \"mount_point\": ")
                .append(jsonString(disk.mountPoint()))
                .append(", \"type\": ")
                .append(jsonString(disk.fsType()))
                .append(", \"total_bytes\": ")
                .append(jsonNullable(disk.totalBytes()))
                .append(", \"used_bytes\": ")
                .append(jsonNullable(disk.usedBytes()))
                .append(", \"available_bytes\": ")
                .append(jsonNullable(disk.availableBytes()))
                .append(", \"used_percent\": ")
                .append(jsonNullable(disk.usedPercent()))
                .append(", \"note\": ")
                .append(disk.note() == null ? "null" : jsonString(disk.note()))
                .append('}');
            }
            appendArrayClose(json, snapshot.disks());

        json.append(",\n \"batteries\": [");
        // Battery charge and status may independently be unavailable.
        for (int i = 0; i < snapshot.batteries().size(); i++) {
            SystemSnapshot.Battery battery = snapshot.batteries().get(i);
            if (i > 0) json.append(',');
            json.append("\n     {\"name\": ")
                .append(jsonString(battery.name()))
                .append(", \"charge_percent\": ")
                .append(jsonNullable(battery.chargePercent()))
                .append(", \"status\": ")
                .append(battery.status() == null ? "null" : jsonString(battery.status()))
                .append('}');
        }
        appendArrayClose(json, snapshot.batteries());

        json.append(",\n \"sensors\": [");
        // Sensor values are nullable when sysfs exposes the sensor but not its reading.
        for (int i = 0; i < snapshot.sensors().size(); i++) {
            SystemSnapshot.Sensor sensor = snapshot.sensors().get(i);
            if (i > 0) json.append(',');
            json.append("\n     {\"chip\": ")
                .append(jsonString(sensor.chip()))
                .append(", \"label\": ")
                .append(jsonString(sensor.label()))
                .append(", \"kind\": ")
                .append(jsonString(sensor.kind()))
                .append(", \"value\": ")
                .append(jsonNullable(sensor.value()))
                .append(", \"unit\": ")
                .append(jsonString(sensor.unit()))
                .append('}');
        }
        appendArrayClose(json, snapshot.sensors());

        return json.append("\n}\n").toString();
    }

    /* Closes arrays with consistent indentation, including empty arrays. */
    private static void appendArrayClose(StringBuilder json, List<?> values) {
        if (!values.isEmpty()) json.append('\n').append("    ");
        json.append(']');
    }

    /* Emits a numeric value or JSON null for an unavailable average. */
    private static String jsonNumber(Double number) {
        return number == null ? "null" : number.toString();
    }

    /* Emits finite numbers as JSON numbers; missing and non-finite values become null. */
    private static String jsonNullable(Number number) {
        if (number == null) {
            return "null";            
        }    
        if (number instanceof Double d && (d.isNaN() || d.isInfinite())) {
            return "null";            
        }
        return number.toString();
    }

    /* Formats hardware IDs as four-digit hexadecimal strings. */
    private static String hex(int id) {
        return String.format(Locale.ROOT, "0x%04x", id);
    }

    /* Escapes quotes, backslashes, and control characters in JSON string values. */
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
}
