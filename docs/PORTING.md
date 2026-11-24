# Porting guide: EF Core Power Tools (VS) → Rider plugin

Reference source: `C:\Source\Git\EFCorePowerTools` (read only — never modify it).
VS extension UI lives in `src/GUI` (`Shared/` = handlers, view models, helpers; `EFCorePowerTools/` = WPF dialogs, package, vsct; `RevEng.Shared/` = DTOs).

## Goal

Behavioural parity with the Visual Studio extension. Same commands, same dialogs and options, same files
written (`efpt.config.json`, `.user`, `efpt.renaming.json`, generated code via the same bundled `efreveng`/`efpt`
runners), same messages. Where Visual Studio has a facility Rider lacks, use the closest Rider equivalent and
leave a short `// Rider:` comment explaining the substitution.

## Architecture

Pure IntelliJ-platform frontend plugin written in Kotlin (no ReSharper backend). All database/EF work is done
out of process by the same .NET runners the VSIX ships (copied into the plugin's `tools/` folder at build time).

| Package | Contents |
|---|---|
| `model` | DTO ports (`ReverseEngineerOptions`, `SerializationTableModel`, `TableModel`, `Schema`, …) with DataContract-compatible JSON (`toJson()` / `fromJson()`), enums. |
| `json.DcJson` | JSON read/write matching .NET DataContractJsonSerializer output (ordinal key order, 3-space indent, `\/`, UTF-8 BOM). Use `DcJson.writeFile` for any file the VS extension writes. |
| `tools` | `ProcessRunner` (run `dotnet`), `BundledTools` (unzip runners), `EfRevEngLauncher` (efreveng: tables, generate, dgml, erdiagram, dacpac report, dab), `EfptLauncher` (efpt: DGML/DDL/DebugView/contextlist/schemacompare), `ResultDeserializer`. |
| `project` | `DotNetProject` (≈ VS `Project` + `ProjectExtensions`: TFM checks, config files, msbuild properties, assets/NuGet, build, `installPackage`), `SolutionProjects` (all projects, selection), `SqlProjHelper`, `Providers` (NuGet package table), `MsBuild`. |
| `connections` | `ConnectionStore` (saved connections, secrets in PasswordSafe; = Server Explorer/CredentialStore), `ConnectionStringBuilder`, `DataConnections` (= VsDataHelper). |
| `settings` | `EfptSettings.instance` (= `AdvancedOptions.Instance`), `EfptConfigurable`. |
| `ui` | Shared dialogs: `PickServerDatabaseDialog`, `tables.PickTablesDialog`/`PickTablesPanel`, `actions.ProjectPicker`. |
| `handlers` | One object per VS handler. Entry-point signatures are fixed (actions call them) — implement the bodies, add private helpers / new files freely. |
| `util` | `Ui` (= VSHelper / VS.StatusBar / VS.Documents / LogError), `PathHelper`. |

## Threading rules

* Handler entry points are invoked on the EDT from `actionPerformed`.
* Never run processes, msbuild, file walks or password-safe reads on the EDT. Use
  `Ui.runBackground(project, title) { indicator -> ... }` for long work; it reports exceptions via `Ui.logError`.
* Show dialogs from background work with `Ui.invokeAndWait { ... }` (returns the dialog result).
* `Ui.showError/showWarning/showMessage/notify/status/openFile/openPreview` are safe from any thread.
* After an external process writes files, call `Ui.refreshPaths(...)` so Rider sees them.

## Mapping cheatsheet

| Visual Studio | Rider plugin |
|---|---|
| `VS.StatusBar.ShowMessageAsync` | `Ui.status(project, text)` / progress indicator text |
| `VSHelper.ShowError/ShowWarning/ShowMessage` | `Ui.showError/showWarning/showMessage` |
| `EFCorePowerToolsPackage.LogError` | `Ui.logError(project, messages, exception)` |
| `VS.Documents.OpenAsync` / `OpenInPreviewTabAsync` | `Ui.openFile` / `Ui.openPreview` |
| `VS.MessageBox.ShowAsync` yes/no | `Ui.confirm` |
| `VS.Build.BuildProjectAsync` + output path | `project.build()` then `project.getOutputAssemblyPath()` |
| `project.GetAttributeAsync("X")` | `project.getProperty("X")` (off EDT) |
| NuGetHelper.InstallPackage | `project.installPackage(id, version)` (`dotnet add package`) |
| `project.AddExistingFilesAsync` | not needed (SDK-style globbing); just write the file and refresh |
| WPF dialog | `DialogWrapper` + Kotlin UI DSL v2 (`com.intellij.ui.dsl.builder.panel`), `JBTable`, `CheckboxTree`, `JBList`, `SearchTextField` |
| `AdvancedOptions.Instance.X` | `EfptSettings.instance.x` |
| Telemetry | omitted (do not port) |
| Localized `*Locale` resources | use the English text of the `.resx` / `Locale` strings inline |

## Build

`./gradlew compileKotlin` from the repo root (`C:\Source\Git\rider plugins\Rider-EFPT`). Other agents work in
the same tree at the same time: only edit files you own. If compilation fails only in files you don't own,
ignore it and re-run later; never edit or "fix" another area's files. Avoid `./gradlew clean`.
