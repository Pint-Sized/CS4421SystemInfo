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

Use `--plain` to request a one-time plain-text report even in an interactive
terminal:

```sh
./system-fetch --plain
```

The interactive view refreshes processor load, memory usage, PCI devices, and
USB devices every two seconds. Collection runs in background commands so tab
navigation and scrolling remain responsive. If a refresh fails, the last
successful snapshot remains visible and the status line reports the error.
CPU utilization is calculated from successive `/proc/stat` samples without
blocking for a sampling interval.

The code separates TUI presentation (`SystemFetch`), Linux hardware and
resource collection (`SystemInfoCollector`), structured data (`SystemSnapshot`),
and JSON serialization with atomic file replacement (`JsonSnapshotWriter`).

Install Java 21 and Maven, then build and run from the project directory:

```sh
mvn compile
./system-fetch
```

The executable project-local launcher compiles the application if needed,
resolves its runtime dependencies, and forwards command-line arguments:

```sh
./system-fetch --plain
./system-fetch --plain --json-file "./system info.json"
```

To export snapshots for another program or a future web GUI, pass a JSON file
path:

```sh
./system-fetch --json-file ./system-info.json
```

The plain-text and JSON export options can be used together:

```sh
./system-fetch --plain --json-file ./system-info.json
```

While the interactive TUI is running, this file is replaced with a fresh,
complete snapshot every two seconds. Each update uses an atomic rename, so
readers see either the previous complete document or the next one. If the
filesystem does not support atomic replacement, the TUI reports the export
error and leaves the last successful file intact. In plain-text mode, one
snapshot is written before the program exits.

The JSON document includes a `schema_version`, a UTC `generated_at` timestamp,
a `processor` object with logical and physical CPU counts and load percentages,
a `memory` object with total and used KiB and usage percentage, and
`pci_devices` and `usb_devices` arrays with device IDs and names. When CPU
topology is unavailable, `physical_cores` and physical-core load are `null` or
empty, while `physical_topology_available` is `false`. If topology is only
partially available, `physical_topology_complete` is `false` and unmapped
logical CPUs are counted individually. Per-CPU and per-core load arrays are
empty and their averages are `null` until there is a prior sample to compare.

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
