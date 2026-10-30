import java.io.IOException; /* Imports java.io.IOException for use in this file. */
import java.nio.file.AtomicMoveNotSupportedException; /* Imports java.nio.file.AtomicMoveNotSupportedException for use in this file. */
import java.nio.file.Files; /* Imports java.nio.file.Files for use in this file. */
import java.nio.file.Path; /* Imports java.nio.file.Path for use in this file. */
import java.nio.file.StandardCopyOption; /* Imports java.nio.file.StandardCopyOption for use in this file. */
import java.time.Instant; /* Imports java.time.Instant for use in this file. */
import java.util.List; /* Imports java.util.List for use in this file. */
import java.util.Locale; /* Imports java.util.Locale for use in this file. */

/* Serializes a system snapshot and atomically replaces the requested JSON file. */
final class JsonSnapshotWriter { /* Continues the current operation. */

    private JsonSnapshotWriter() {} /* Declares the JsonSnapshotWriter method. */

    /* Writes beside the target so the final rename stays on the same filesystem. */
    static void writeAtomically(Path target, SystemSnapshot snapshot) /* Declares the writeAtomically method. */
        throws IOException { /* Continues the current operation. */
        Path parent = target.toAbsolutePath().getParent(); /* Stores the calculated value in parent. */
        Files.createDirectories(parent); /* Uses the named Java API to perform this operation. */
        Path temporary = Files.createTempFile(parent, ".systemfetch-", ".tmp"); /* Stores the calculated value in temporary. */
        try { /* Starts work that may need error handling or resource cleanup. */
            Files.writeString(temporary, toJson(snapshot)); /* Uses the named Java API to perform this operation. */
            Files.move( /* Uses the named Java API to perform this operation. */
                temporary, /* Provides the next part of the current expression. */
                target, /* Provides the next part of the current expression. */
                StandardCopyOption.ATOMIC_MOVE, /* Provides the next part of the current expression. */
                StandardCopyOption.REPLACE_EXISTING /* Continues the current operation. */
            ); /* Provides the next part of the current expression. */
        } catch (AtomicMoveNotSupportedException e) { /* Provides the next part of the current expression. */
            throw new IOException( /* Reports this error to the caller. */
                "Atomic replacement is not supported for " + target, /* Provides the next part of the current expression. */
                e /* Continues the current operation. */
            ); /* Provides the next part of the current expression. */
        } finally { /* Provides the next part of the current expression. */
            Files.deleteIfExists(temporary); /* Uses the named Java API to perform this operation. */
        } /* This brace ends a code block. */
    } /* This brace ends a code block. */

    /* Builds the versioned JSON document without an external serialization dependency. */
    private static String toJson(SystemSnapshot snapshot) { /* Declares the toJson method. */
        SystemSnapshot.Processor processor = snapshot.processor(); /* Stores the calculated value in processor. */
        SystemSnapshot.Memory memory = snapshot.memory(); /* Stores the calculated value in memory. */
        StringBuilder json = new StringBuilder("{\n"); /* Stores the calculated value in json. */
        json.append("  \"schema_version\": 1,\n  \"generated_at\": ") /* Adds this value to the output being built. */
            .append(jsonString(Instant.now().toString())) /* Adds this value to the output being built. */
            .append(",\n  \"processor\": {\n"); /* Adds this value to the output being built. */
        // Keep the schema's section order stable for predictable downstream readers.
        json.append("    \"model\": ").append(jsonString(processor.model())).append(",\n"); /* Adds this value to the output being built. */
        json.append("    \"logical_cpus\": ").append(processor.logicalCpus()).append(",\n"); /* Adds this value to the output being built. */
        json.append("    \"physical_cores\": ") /* Adds this value to the output being built. */
            .append(processor.physicalCores() == null ? "null" : processor.physicalCores()) /* Adds this value to the output being built. */
            .append(",\n    \"physical_topology_available\": ") /* Adds this value to the output being built. */
            .append(processor.topologyAvailable()) /* Adds this value to the output being built. */
            .append(",\n    \"physical_topology_complete\": ") /* Adds this value to the output being built. */
            .append(processor.topologyComplete()) /* Adds this value to the output being built. */
            .append(",\n    \"logical_cpu_load_percent\": ["); /* Adds this value to the output being built. */
        for (int i = 0; i < processor.logicalLoads().size(); i++) { /* Repeats this work for each matching value. */
            SystemSnapshot.CpuLoad load = processor.logicalLoads().get(i); /* Stores the calculated value in load. */
            if (i > 0) json.append(','); /* Checks this condition before choosing what to do. */
            json.append("\n      {\"id\": ") /* Adds this value to the output being built. */
                .append(load.id()) /* Adds this value to the output being built. */
                .append(", \"utilization\": ") /* Adds this value to the output being built. */
                .append(load.utilization()) /* Adds this value to the output being built. */
                .append('}'); /* Adds this value to the output being built. */
        } /* This brace ends a code block. */
        appendArrayClose(json, processor.logicalLoads()); /* Calls this operation with the values shown. */
        json.append(",\n    \"average_logical_load_percent\": ") /* Adds this value to the output being built. */
            .append(jsonNumber(processor.averageLogicalLoad())) /* Adds this value to the output being built. */
            .append(",\n    \"physical_core_load_percent\": ["); /* Adds this value to the output being built. */
        for (int i = 0; i < processor.physicalLoads().size(); i++) { /* Repeats this work for each matching value. */
            SystemSnapshot.CoreLoad load = processor.physicalLoads().get(i); /* Stores the calculated value in load. */
            if (i > 0) json.append(','); /* Checks this condition before choosing what to do. */
            json.append("\n      {\"name\": ") /* Adds this value to the output being built. */
                .append(jsonString(load.name())) /* Adds this value to the output being built. */
                .append(", \"utilization\": ") /* Adds this value to the output being built. */
                .append(load.utilization()) /* Adds this value to the output being built. */
                .append('}'); /* Adds this value to the output being built. */
        } /* This brace ends a code block. */
        appendArrayClose(json, processor.physicalLoads()); /* Calls this operation with the values shown. */
        json.append(",\n    \"average_physical_core_load_percent\": ") /* Adds this value to the output being built. */
            .append(jsonNumber(processor.averagePhysicalLoad())) /* Adds this value to the output being built. */
            .append("\n  },\n  \"memory\": {\n") /* Adds this value to the output being built. */
            .append("    \"total_kib\": ") /* Adds this value to the output being built. */
            .append(memory.totalKiB()) /* Adds this value to the output being built. */
            .append(",\n    \"used_kib\": ") /* Adds this value to the output being built. */
            .append(memory.usedKiB()) /* Adds this value to the output being built. */
            .append(",\n    \"used_percent\": ") /* Adds this value to the output being built. */
            .append(memory.usedPercent()) /* Adds this value to the output being built. */
            .append("\n  },\n  \"pci_devices\": ["); /* Adds this value to the output being built. */
        // Device IDs stay hexadecimal strings to preserve their conventional notation.
        for (int i = 0; i < snapshot.pci().size(); i++) { /* Repeats this work for each matching value. */
            SystemSnapshot.PciDevice device = snapshot.pci().get(i); /* Stores the calculated value in device. */
            if (i > 0) json.append(','); /* Checks this condition before choosing what to do. */
            json.append("\n    {\"bus\": ") /* Adds this value to the output being built. */
                .append(device.bus()) /* Adds this value to the output being built. */
                .append(", \"device\": ") /* Adds this value to the output being built. */
                .append(device.device()) /* Adds this value to the output being built. */
                .append(", \"function\": ") /* Adds this value to the output being built. */
                .append(device.function()) /* Adds this value to the output being built. */
                .append(", \"vendor_id\": ") /* Adds this value to the output being built. */
                .append(jsonString(hex(device.vendor()))) /* Adds this value to the output being built. */
                .append(", \"device_id\": ") /* Adds this value to the output being built. */
                .append(jsonString(hex(device.id()))) /* Adds this value to the output being built. */
                .append(", \"class\": ") /* Adds this value to the output being built. */
                .append(jsonString(String.format(Locale.ROOT, "%06x", device.deviceClass()))) /* Adds this value to the output being built. */
                .append(", \"vendor_name\": ") /* Adds this value to the output being built. */
                .append(jsonString(device.vendorName())) /* Adds this value to the output being built. */
                .append(", \"name\": ") /* Adds this value to the output being built. */
                .append(jsonString(device.name())) /* Adds this value to the output being built. */
                .append('}'); /* Adds this value to the output being built. */
        } /* This brace ends a code block. */
        appendArrayClose(json, snapshot.pci()); /* Calls this operation with the values shown. */
        json.append(",\n  \"usb_devices\": ["); /* Adds this value to the output being built. */
        for (int i = 0; i < snapshot.usb().size(); i++) { /* Repeats this work for each matching value. */
            SystemSnapshot.UsbDevice device = snapshot.usb().get(i); /* Stores the calculated value in device. */
            if (i > 0) json.append(','); /* Checks this condition before choosing what to do. */
            json.append("\n    {\"vendor_id\": ") /* Adds this value to the output being built. */
                .append(jsonString(hex(device.vendor()))) /* Adds this value to the output being built. */
                .append(", \"product_id\": ") /* Adds this value to the output being built. */
                .append(jsonString(hex(device.id()))) /* Adds this value to the output being built. */
                .append(", \"vendor_name\": ") /* Adds this value to the output being built. */
                .append(jsonString(device.vendorName())) /* Adds this value to the output being built. */
                .append(", \"name\": ") /* Adds this value to the output being built. */
                .append(jsonString(device.name())) /* Adds this value to the output being built. */
                .append('}'); /* Adds this value to the output being built. */
        } /* This brace ends a code block. */
        appendArrayClose(json, snapshot.usb()); /* Calls this operation with the values shown. */

        json.append(",\n \"disks\": ["); /* Adds this value to the output being built. */
        for (int i =0; i < snapshot.disks().size(); i++) { /* Repeats this work for each matching value. */
            SystemSnapshot.Disk disk = snapshot.disks().get(i); /* Stores the calculated value in disk. */
            if (i > 0) json.append(','); /* Checks this condition before choosing what to do. */
            json.append("\n     {\"device\": ") /* Adds this value to the output being built. */
                .append(jsonString(disk.device())) /* Adds this value to the output being built. */
                .append(", \"mount_point\": ") /* Adds this value to the output being built. */
                .append(jsonString(disk.mountPoint())) /* Adds this value to the output being built. */
                .append(", \"type\": ") /* Adds this value to the output being built. */
                .append(jsonString(disk.fsType())) /* Adds this value to the output being built. */
                .append(", \"total_bytes\": ") /* Adds this value to the output being built. */
                .append(jsonNullable(disk.totalBytes())) /* Adds this value to the output being built. */
                .append(", \"used_bytes\": ") /* Adds this value to the output being built. */
                .append(jsonNullable(disk.usedBytes())) /* Adds this value to the output being built. */
                .append(", \"available_bytes\": ") /* Adds this value to the output being built. */
                .append(jsonNullable(disk.availableBytes())) /* Adds this value to the output being built. */
                .append(", \"used_percent\": ") /* Adds this value to the output being built. */
                .append(jsonNullable(disk.usedPercent())) /* Adds this value to the output being built. */
                .append(", \"note\": ") /* Adds this value to the output being built. */
                .append(disk.note() == null ? "null" : jsonString(disk.note())) /* Adds this value to the output being built. */
                .append('}'); /* Adds this value to the output being built. */
            } /* This brace ends a code block. */
            appendArrayClose(json, snapshot.disks()); /* Calls this operation with the values shown. */

        return json.append("\n}\n").toString(); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Closes arrays with consistent indentation, including empty arrays. */
    private static void appendArrayClose(StringBuilder json, List<?> values) { /* Declares the appendArrayClose method. */
        if (!values.isEmpty()) json.append('\n').append("    "); /* Checks this condition before choosing what to do. */
        json.append(']'); /* Adds this value to the output being built. */
    } /* This brace ends a code block. */

    /* JSON has no representation for a not-yet-available measurement. */
    private static String jsonNumber(Double number) { /* Declares the jsonNumber method. */
        return number == null ? "null" : number.toString(); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Writes a number as JSON, or "null" when it is missing or not a real number*/
    private static String jsonNullable(Number number) { /* Declares the jsonNullable method. */
        if (number == null) { /* Checks this condition before choosing what to do. */
            return "null"; /* Returns this value to the caller. */
        } /* This brace ends a code block. */
        if (number instanceof Double d && (d.isNaN() || d.isInfinite())) { /* Checks this condition before choosing what to do. */
            return "null"; /* Returns this value to the caller. */
        } /* This brace ends a code block. */
        return number.toString(); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Formats hardware IDs as four-digit hexadecimal strings. */
    private static String hex(int id) { /* Declares the hex method. */
        return String.format(Locale.ROOT, "0x%04x", id); /* Returns this value to the caller. */
    } /* This brace ends a code block. */

    /* Escapes JSON control characters and quotes in hardware-provided names. */
    private static String jsonString(String value) { /* Declares the jsonString method. */
        StringBuilder escaped = new StringBuilder("\""); /* Stores the calculated value in escaped. */
        for (int i = 0; i < value.length(); i++) { /* Repeats this work for each matching value. */
            char character = value.charAt(i); /* Stores the calculated value in character. */
            switch (character) { /* Selects or handles one of the available cases. */
                case '"' -> escaped.append("\\\""); /* Selects or handles one of the available cases. */
                case '\\' -> escaped.append("\\\\"); /* Selects or handles one of the available cases. */
                case '\b' -> escaped.append("\\b"); /* Selects or handles one of the available cases. */
                case '\f' -> escaped.append("\\f"); /* Selects or handles one of the available cases. */
                case '\n' -> escaped.append("\\n"); /* Selects or handles one of the available cases. */
                case '\r' -> escaped.append("\\r"); /* Selects or handles one of the available cases. */
                case '\t' -> escaped.append("\\t"); /* Selects or handles one of the available cases. */
                default -> { /* Selects or handles one of the available cases. */
                    if (character < 0x20) { /* Checks this condition before choosing what to do. */
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character)); /* Adds this value to the output being built. */
                    } else { /* Provides the next part of the current expression. */
                        escaped.append(character); /* Adds this value to the output being built. */
                    } /* This brace ends a code block. */
                } /* This brace ends a code block. */
            } /* This brace ends a code block. */
        } /* This brace ends a code block. */
        return escaped.append('"').toString(); /* Returns this value to the caller. */
    } /* This brace ends a code block. */
} /* This brace ends a code block. */
