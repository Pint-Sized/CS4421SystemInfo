# System Fetch

A Linux system-information program written in Java 21. It reports the processor
model, logical and physical CPU counts, measured load per logical CPU and
physical core, memory usage, PCI and USB devices, and resolved vendor/device
names. The PCI tab also includes devices sorted by vendor and address, plus a
device-count summary per vendor. When Linux does not expose physical topology,
the program reports that limitation and shows load per logical CPU instead.

In an interactive terminal, TUI4J presents the report in four styled tabs:
Processor, Memory, PCI, and USB. Without an interactive terminal, it prints all
four sections as plain text. USB systems with no detected devices are reported
explicitly.

The interactive view refreshes processor load, memory usage, PCI devices, and
USB devices every two seconds. Collection runs in background commands so tab
navigation and scrolling remain responsive. If a refresh fails, the last
successful snapshot remains visible and the status line reports the error.
CPU utilization is calculated from successive `/proc/stat` samples without
blocking for a sampling interval.

Install Java 21 and Maven, then build and run from the project directory:

```sh
mvn compile exec:java
```

Use the left/right arrow keys or Tab to switch between the Processor, Memory,
PCI, and USB tabs. Use up/down to scroll, Page Up/Page Down to move by a page,
Home/End to jump to the start/end, and `q` or Escape to quit. Resize terminals
smaller than 40 columns by 7 rows when prompted. The UI wraps report lines to
fit the terminal.

Hardware ID databases are searched in the project directory, `data/`, `ids/`,
and common system locations. Missing `pci.ids` or `usb.ids` files are downloaded
into `data/` on first run, so network access is needed if no local copy is
available. If a database cannot be obtained or an ID is not listed, the raw
hexadecimal ID is shown. System information is read from Linux `/proc` and
`/sys` filesystems.
