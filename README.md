# EF Core Power Tools for Rider

A JetBrains Rider port of the [EF Core Power Tools](https://github.com/ErikEJ/EFCorePowerTools) Visual Studio extension
by Erik Ejlskov Jensen. This is an unofficial port; it is not affiliated with or endorsed by the original project.
It ships the **same .NET runners** as the VSIX (`efreveng` for reverse engineering, `efpt` for model inspection), so
generated code and the `efpt.config.json` / `efpt.renaming.json` files are identical to what Visual Studio produces —
teams can mix both IDEs on one config.

## Commands

| Where | Command | VS equivalent |
|---|---|---|
| Project ▸ EF Core Power Tools | Reverse Engineer (wizard), Reverse Engineer (Legacy), Refresh | same |
| | Data API Builder Scaffold (preview) | same |
| | Add DbContext Diagram, View DbContext DDL SQL, View DbContext as DebugView, Add AsDgml() extension, Compare DbContext to database | same (.NET 10+ projects referencing EF Core) |
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
* .NET 10 runtime and SDK on PATH (`dotnet msbuild -getProperty` is used to read project properties).
* EF Core 10 and projects targeting .NET 10 or later. Like upstream since
  [#3562](https://github.com/ErikEJ/EFCorePowerTools/pull/3562), EF Core 8/9 and .NET 8/9/.NET Standard projects are not
  supported; existing `efpt.config.json` files that select EF Core 8 or 9 are migrated to EF Core 10.

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

The end-to-end runner test is opt-in: set `EFPT_E2E_RUNNER` (extracted `efreveng100` folder) and `EFPT_E2E_DB`
(SQLite database with `Artist`, `Album`, `AlbumView`) and run `./gradlew test`.

See [docs/PORTING.md](docs/PORTING.md) for the architecture.

## CI and releases

* **Build** (`.github/workflows/build.yml`): every push to `main` and every pull request builds and tests the plugin
  and uploads the zip as a workflow artifact.
* **Release** (`.github/workflows/release.yml`): push a version tag to publish a GitHub release with the installable zip:

  ```sh
  git tag v0.2.0
  git push origin v0.2.0
  ```

  The tag (without the `v`) becomes the plugin version. Tags containing `-` (e.g. `v0.2.0-beta1`) are marked as
  pre-releases. Release builds compile the .NET runners from source so the plugin works on Windows, macOS and Linux.

Both workflows take the runners from `ErikEJ/EFCorePowerTools` at the commit pinned in `EFPT_REF`; update it to pick up
upstream changes.

## Credits and license

All functionality originates from [EF Core Power Tools](https://github.com/ErikEJ/EFCorePowerTools)
(MIT License, Copyright (c) 2018 Erik Ejlskov Jensen). This repository's Kotlin code is a translation of that
project's Visual Studio extension, and the plugin bundles .NET runners built from it. See
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for the full license text. Please report issues with the
Rider port here, not to the upstream project.

The Rider port itself is also licensed under the [MIT License](LICENSE).
