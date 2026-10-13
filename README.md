# System Fetch

A dependency-free Java 17+ system fetch for Linux. It reports processor details and
per-logical-CPU load, memory usage, PCI and USB devices, resolved vendor/device
names, and PCI sorting and vendor-count summaries. PCI and USB entries are
collected into Java lists. When no USB devices are present, the program prints
that explicitly.

Compile and run from the project directory:

```sh
javac SystemFetch.java
java SystemFetch
```

The program looks for `pci.ids` and `usb.ids` in the project directory, `data/`,
`ids/`, and common system locations. If either file is missing, it attempts to
download it into `data/`. Network access is only needed for this first download;
unknown IDs are printed in hexadecimal if the file cannot be obtained.
System information is read from Linux `/proc` and `/sys` filesystems.