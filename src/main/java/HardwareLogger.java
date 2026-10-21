import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


public class HardwareLogger {
    
    //This is the logpath, change the path/filename to something else if it is what you preffer
    private final Path logFile =
            Path.of("hardware-history", "hardware-history.csv");

    LocalDateTime currentTime = LocalDateTime.now();
    DateTimeFormatter formatTime = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    String finalTime = currentTime.format(formatTime);

    //IOException is when data is unable to be transferred successfully, each parameter in appendRecord() will be appended to the file 
    public void appendRecord() throws IOException {
        
       //Timestamp generator, collects the time via "localdatetime" which then is formatted and turned into the "finalTime" variable
        LocalDateTime currentTime = LocalDateTime.now();
        DateTimeFormatter formatTime = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
        String finalTime = currentTime.format(formatTime);

        // Create the directory if it does not exist.
        Files.createDirectories(logFile.getParent());
         

        //ergreg
        int logicalcpus = SystemSnapshot.processor().logicalCpus(); 

        // Standard.CREATE will create the file if it is not currently in the directory. Standard.APPEND will append the file based off the parameters in "appendRecord()"
        Files.writeString(
                logFile,
                finalTime + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }
 }