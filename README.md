# EF Core Power Tools for Rider

A JetBrains Rider port of the [EF Core Power Tools](https://github.com/ErikEJ/EFCorePowerTools) Visual Studio extension.
It ships the **same .NET runners** as the VSIX (`efreveng` for reverse engineering, `efpt` for model inspection), so
generated code and the `efpt.config.json` / `efpt.renaming.json` files are identical to what Visual Studio produces —
teams can mix both IDEs on one config.

## Commands

| Where | Command | VS equivalent |
|---|---|---|
| Project ▸ EF Core Power Tools | Reverse Engineer (wizard), Reverse Engineer (Legacy), Refresh | same |
| | Data API Builder Scaffold (preview) | same |
| | Add DbContext Diagram, View DbContext DDL SQL, View DbContext as DebugView, Add AsDgml() extension, Compare DbContext to database | same (net8+ projects referencing EF Core) |
| | Visualize Database Schema, ER Diagram, Analyze (preview, SQL projects) | same |
| | Options, About | same |
| SQL Database Project | Create EF Core DbContext... | same |
| `efpt.*config.json` / `efcpt-config.json` | EF Core Power Tools - Edit / Refresh | same |
| `dab-config.json` | EF Core Power Tools - Start DAB | same |
| Database tool window data source | Reverse Engineer, ER Diagram, Analyze (experimental) | Server Explorer commands |
| Tools ▸ EF Core Power Tools | shortcuts | — |
| Settings ▸ Tools ▸ EF Core Power Tools | options | Tools ▸ Options ▸ EF Core Power Tools |

## Requirements

* Rider 2026.3+ (`sinceBuild` 263).
* .NET 8 runtime (plus .NET 10 for EF Core 10) and a .NET 8+ SDK on PATH (`dotnet msbuild -getProperty` is used to read project properties).

## Differences from Visual Studio

| VS facility | Rider plugin |
|---|---|
| Server Explorer / DDEX data connections | Saved connections (secrets in the IDE password safe) + Rider Database tool window data sources (JDBC URLs converted to ADO.NET connection strings) |
| DGML viewer | Diagram rendered to SVG (image viewer) / offline HTML with pan & zoom; `.dgml` kept and opened as XML |
| VS build / NuGet APIs | `dotnet build` / `dotnet add package` |
| `cmd` windows for `efcpt` / `dab start` | Run tool window console tabs |
| Startup project for `efpt` | The project itself (VS falls back to this too) |
| `efpt.postrun.cmd` | Same on Windows; `efpt.postrun.sh` on macOS/Linux |
| Telemetry, localization | Not ported (English only) |

Per-feature details are in the `// Rider:` comments in the source.

## Building

```sh
./gradlew buildPlugin            # -> build/distributions/Rider-EFPT-<version>.zip (install from disk)
./gradlew test                   # unit tests
./gradlew runIde                 # sandbox Rider with the plugin
```

* Builds against a local Rider (`-PriderPath`, `RIDER_PATH`, or `%LOCALAPPDATA%/Programs/Rider`), otherwise downloads `riderVersion`.
* The .NET runners are copied from an EF Core Power Tools clone: `-PefptRepo=<path>` (default `../../EFCorePowerTools`).
* **macOS / Linux:** the prebuilt runner zips in that repo are trimmed to Windows native libraries. Build with
  `-PbuildToolsFromSource=true` to `dotnet publish` the runners from source with Windows, Linux and macOS runtimes.

The end-to-end runner test is opt-in: set `EFPT_E2E_RUNNER` (extracted `efreveng80` folder) and `EFPT_E2E_DB`
(SQLite database with `Artist`, `Album`, `AlbumView`) and run `./gradlew test`.

See [docs/PORTING.md](docs/PORTING.md) for the architecture.
