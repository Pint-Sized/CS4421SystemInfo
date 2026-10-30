import java.io.IOException; /* Imports java.io.IOException for use in this file. */
import java.nio.file.Files; /* Imports java.nio.file.Files for use in this file. */
import java.nio.file.Path; /* Imports java.nio.file.Path for use in this file. */
import java.nio.file.StandardOpenOption; /* Imports java.nio.file.StandardOpenOption for use in this file. */
import java.time.LocalDateTime; /* Imports java.time.LocalDateTime for use in this file. */
import java.time.format.DateTimeFormatter; /* Imports java.time.format.DateTimeFormatter for use in this file. */


public class HardwareLogger { /* Declares the HardwareLogger type. */
    //This is the logpath, change the path/filename to something else if it is what you preffer
    private final Path logFile = /* Stores the calculated value in logFile. */
            Path.of("hardware-history", "hardware-history.csv"); /* Uses the named Java API to perform this operation. */

    //IOException is when data is unable to be transferred successfully, each parameter in appendRecord() will be appended to the file 
    public void appendRecord(SystemSnapshot data) throws IOException { /* Declares the appendRecord method. */
        
       //Timestamp generator, collects the time via "localdatetime" which then is formatted and turned into the "finalTime" variable
        LocalDateTime currentTime = LocalDateTime.now(); /* Stores the calculated value in currentTime. */
        DateTimeFormatter formatTime = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"); /* Stores the calculated value in formatTime. */
        String finalTime = currentTime.format(formatTime); /* Stores the calculated value in finalTime. */

        // Create the directory if it does not exist.
        Files.createDirectories(logFile.getParent()); /* Uses the named Java API to perform this operation. */

         int logicalCpus = data.processor().logicalCpus(); /* Stores the calculated value in logicalCpus. */
        //memory Total
         long memoryTotal = data.memory().totalKiB(); /* Stores the calculated value in memoryTotal. */
        //memory Used
         long memoryUsed = data.memory().usedKiB(); /* Stores the calculated value in memoryUsed. */
        //cpu model
        String cpuModel = data.processor().model(); /* Stores the calculated value in cpuModel. */
        //pci total
        int pciTotal = data.pci().size(); /* Stores the calculated value in pciTotal. */
        //usb total
        int usbTotal = data.usb().size(); /* Stores the calculated value in usbTotal. */
         


        // Standard.CREATE will create the file if it is not currently in the directory. Standard.APPEND will append the file based off the parameters in "appendRecord()"
        Files.writeString( /* Uses the named Java API to perform this operation. */
                logFile, /* Provides the next part of the current expression. */
                finalTime + logicalCpus + memoryTotal + memoryUsed + cpuModel + pciTotal + usbTotal + System.lineSeparator(), /* Provides the next part of the current expression. */
                StandardOpenOption.CREATE, /* Provides the next part of the current expression. */
                StandardOpenOption.APPEND /* Continues the current operation. */
        ); /* Provides the next part of the current expression. */
    } /* This brace ends a code block. */
 } /* This brace ends a code block. */