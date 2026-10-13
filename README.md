# System Fetch

A Java 21 system fetch for Linux with an interactive TUI4J terminal interface.
It reports processor details and per-logical-CPU load, memory usage, PCI and USB
devices, resolved vendor/device names, and PCI sorting and vendor-count summaries.
PCI and USB entries are collected into Java lists. When no USB devices are
present, the program reports that explicitly.

Build and run from the project directory:

```sh
mvn compile exec:java
```

Use the left/right arrow keys or Tab to switch between the Processor, Memory,
PCI, and USB tabs. Use up/down to scroll a tab, Page Up/Page Down to move by a
page, and `q` or Escape to quit. If launched without an interactive terminal,
the program prints all sections as plain text instead. The TUI asks you to
resize terminals smaller than 40 columns by 7 rows. In an interactive terminal,
the TUI uses colored tabs, highlighted section labels, and divider lines.

The program looks for `pci.ids` and `usb.ids` in the project directory, `data/`,
`ids/`, and common system locations. If either file is missing, it attempts to
download it into `data/`. Network access is only needed for this first download;
unknown IDs are printed in hexadecimal if the file cannot be obtained. System
information is read from Linux `/proc` and `/sys` filesystems.
