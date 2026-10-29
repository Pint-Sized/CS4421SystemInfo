import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
/* Appends a compact summary of a system snapshot to the history file. */
public class HardwareLogger {
    /* The history file is created under the working directory when first written. */
    private final Path logFile =
            Path.of("hardware-history", "hardware-history.csv");

    /*
     * Appends a timestamp and selected snapshot values. Despite the .csv suffix,
     * this legacy format has no field delimiters or CSV escaping.
     */
    public void appendRecord(SystemSnapshot data) throws IOException {
       // Capture local wall-clock time for the history entry.
        LocalDateTime currentTime = LocalDateTime.now();
        DateTimeFormatter formatTime = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
        String finalTime = currentTime.format(formatTime);

        // Ensure the parent directory exists before opening the log file.
        Files.createDirectories(logFile.getParent());

        // Select the snapshot values currently recorded by the history logger.
        int logicalCpus = data.processor().logicalCpus();
         long memoryTotal = data.memory().totalKiB();
         long memoryUsed = data.memory().usedKiB();
        String cpuModel = data.processor().model();
        int pciTotal = data.pci().size();
        int usbTotal = data.usb().size();

        // Create the file if absent; otherwise append this concatenated record.
        Files.writeString(
                logFile,
                finalTime + logicalCpus + memoryTotal + memoryUsed + cpuModel + pciTotal + usbTotal + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }
 }