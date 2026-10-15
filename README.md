# System Fetch

System Fetch is a Java 21 command-line system information tool for Linux. It
reports CPU model, logical and physical core counts and load, memory usage,
PCI and USB devices, and resolved hardware names when available.

## Requirements

- Linux, with system information exposed through `/proc` and `/sys`
- Java 21
- Maven
- An interactive terminal for the tabbed TUI

The launcher compiles the application and resolves its runtime dependencies
with Maven. The first run may need network access to download Maven dependencies
and, when hardware ID databases are not installed locally, the PCI/USB ID
databases.

## Run

From the project directory, run the launcher:

```sh
./system-fetch
```

The launcher prints `Loading system information...`, builds as needed, and
forwards its arguments to the application. It uses the TUI in an interactive
terminal. Without an interactive terminal (or when `TERM=dumb`), it prints a
one-time plain-text report.

Force a one-time plain-text report, including from an interactive terminal:

```sh
./system-fetch --plain
```

## TUI controls and refresh

The TUI shows Processor, Memory, PCI, and USB tabs. Values refresh every two
seconds; system data collection runs in the background so navigation remains
responsive. If a refresh fails, the last successful information remains
visible and the status line reports the error.

- Left/right arrows or Tab: change tabs
- Up/down arrows: scroll
- Page Up/Page Down: scroll by a page
- Home/End: jump to the start/end
- `q` or Escape: quit

The terminal should be at least 40 columns by 7 rows. Report lines wrap to fit
the available width.

## JSON export

Pass `--json-file` to write a structured snapshot:

```sh
./system-fetch --json-file ./system-info.json
```

In the interactive TUI, the JSON file is refreshed every two seconds. The
program writes each new document to a temporary file beside the destination
and atomically renames it into place, so readers see a complete previous or
current snapshot rather than a partial write. Atomic replacement must be
supported by the destination filesystem. In plain-text mode, one JSON snapshot
is written before the program exits.

Combine plain-text output and JSON export:

```sh
./system-fetch --plain --json-file "./system info.json"
```

The JSON document has `schema_version` and UTC `generated_at` fields, plus:

- `processor`: model, logical CPU count, physical core count when available,
  topology availability/completeness, per-logical-CPU and per-physical-core
  load percentages, and their averages.
- `memory`: total and used memory in KiB and used percentage.
- `pci_devices` and `usb_devices`: device IDs and resolved names.

If physical topology is unavailable, `physical_cores` and the average physical
load are `null`, the physical-core load list is empty, and
`physical_topology_available` is `false`. Load arrays are empty and averages
are `null` until a prior CPU sample is available. Incomplete topology is marked
with `physical_topology_complete: false`; CPUs without topology data are
counted individually.

The default `system-info.json` output is ignored by Git. Choose another
destination if you want to keep or track an export.

## Hardware names and limitations

PCI and USB names are resolved using `pci.ids` and `usb.ids` databases. The
program searches the project directory, `data/`, `ids/`, `/usr/share/hwdata/`,
and `/usr/share/misc/`. If a database is missing, it tries to download and
cache it under `data/`; if that is unavailable, raw hexadecimal IDs are shown.

CPU topology depends on information provided by Linux and the processor
architecture. If physical-core details are missing, the tool reports that
limitation and provides logical CPU load instead. In containers, `/proc` and
`/sys` may describe the host rather than the container's CPU quota, so reported
CPU counts and utilization may not match the resources available to the
container. NixOS and other Linux distributions are supported where Java 21,
Maven, and the expected Linux system files are available.

## Troubleshooting

- **`mvn: command not found`:** install Maven and ensure it is on `PATH`.
- **The launcher is not executable:** run `chmod +x system-fetch`, then retry.
- **Hardware names show as hexadecimal IDs:** install the matching hardware ID
  database or allow network access for the initial download.
- **Physical-core count is unavailable:** the kernel or architecture did not
  provide usable socket/core topology; logical CPU reporting remains available.
- **JSON export fails:** check that the destination directory is writable and
  that its filesystem supports atomic replacement.
