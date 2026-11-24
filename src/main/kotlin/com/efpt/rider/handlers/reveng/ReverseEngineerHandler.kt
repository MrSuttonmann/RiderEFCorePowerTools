package com.efpt.rider.handlers.reveng

import com.efpt.rider.actions.ProjectPicker
import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.NuGetPackage
import com.efpt.rider.model.ReverseEngineerOptions
import com.efpt.rider.model.Schema
import com.efpt.rider.model.SerializationTableModel
import com.efpt.rider.project.DotNetProject
import com.efpt.rider.project.SolutionProjects
import com.efpt.rider.ui.reveng.EfCoreModelDialog
import com.efpt.rider.ui.reveng.PickConfigDialog
import com.efpt.rider.ui.tables.PickTablesDialog
import com.efpt.rider.util.Ui
import com.intellij.openapi.progress.ProgressIndicator
import java.io.File

/**
 * Port of EFCorePowerTools.Handlers.ReverseEngineer.ReverseEngineerHandler (the "legacy" multi-dialog flow).
 * Entry points are called on the EDT; implementations run long work in the background.
 */
object ReverseEngineerHandler {
    private const val TASK_TITLE = "EF Core Power Tools: Reverse Engineering"

    /** ReverseEngineerCodeFirstAsync(uiHint): picks a config file when the project has several. */
    fun reverseEngineerCodeFirst(project: DotNetProject, uiHint: String? = null) {
        val ide = project.ideProject
        if (Ui.isDebugging(ide)) {
            Ui.showError(ide, RevEngLocale.CannotGenerateCodeWhileDebugging)
            return
        }
        Ui.runBackground(ide, TASK_TITLE) { indicator ->
            val optionsPaths = project.getConfigFiles()
            var optionsPath = optionsPaths[0]
            if (optionsPaths.size > 1) {
                optionsPath = Ui.invokeAndWait { PickConfigDialog.show(ide, optionsPaths, project.fullPath) } ?: return@runBackground
            }
            run(project, optionsPath, onlyGenerate = false, fromSqlProj = false, uiHint = uiHint, indicator = indicator)
        }
    }

    /** ReverseEngineerCodeFirstAsync(project, optionsPath, onlyGenerate, fromSqlProj, uiHint). */
    fun reverseEngineerCodeFirst(project: DotNetProject, optionsPath: String, onlyGenerate: Boolean, fromSqlProj: Boolean = false, uiHint: String? = null) {
        val ide = project.ideProject
        if (Ui.isDebugging(ide)) {
            Ui.showError(ide, RevEngLocale.CannotGenerateCodeWhileDebugging)
            return
        }
        Ui.runBackground(ide, TASK_TITLE) { indicator ->
            run(project, optionsPath, onlyGenerate, fromSqlProj, uiHint, indicator)
        }
    }

    /** OnSqlProjectContextMenuInvokeHandler / CmdIdSqlprojCreate: DropSqlprojOptionsAsync then reverse engineer. */
    fun createFromSqlProject(sqlProject: DotNetProject) {
        val ide = sqlProject.ideProject
        Ui.runBackground(ide, TASK_TITLE) { indicator ->
            // Candidate projects: C# projects without an efpt.config.json (project root)
            val candidateProjects = SolutionProjects.all(ide)
                .filter { it.isCSharpProject }
                .filter { !it.directory.resolve("efpt.config.json").toFile().exists() }
            if (candidateProjects.isEmpty()) return@runBackground

            val (optionsPath, project) = dropSqlprojOptions(candidateProjects, sqlProject.fullPath)
            if (optionsPath != null && project != null) {
                if (Ui.isDebugging(ide)) {
                    Ui.showError(ide, RevEngLocale.CannotGenerateCodeWhileDebugging)
                    return@runBackground
                }
                // Rider: VS passes no UiHint here, but DropSqlprojOptionsAsync does not persist the SQL project
                // (the .user file is not written), so the refresh-by-hint lookup can never find it and the VS
                // command fails. The SQL project path is passed as the hint, which is the evident intent.
                run(project, optionsPath, onlyGenerate = false, fromSqlProj = true, uiHint = sqlProject.fullPath, indicator = indicator)
            } else if (optionsPath == null && project != null) {
                Ui.showWarning(ide, "Project '${project.name}' already contains an EF Core Power Tools config file (efpt.config.json).")
            }
        }
    }

    /** DropSqlprojOptionsAsync. Call off the EDT. Returns (null, null) when cancelled; (null, project) when a config exists. */
    internal fun dropSqlprojOptions(candidateProjects: List<DotNetProject>, sqlProjectPath: String): Pair<String?, DotNetProject?> {
        var project = candidateProjects[0]
        if (candidateProjects.size > 1) {
            project = Ui.invokeAndWait { ProjectPicker.pick(project.ideProject, candidateProjects) } ?: return null to null
        }
        val optionsPath = project.getConfigFiles()[0]
        if (File(optionsPath).exists()) return null to project

        val session = RevEngCore.Session(project, optionsPath)
        session.options = ReverseEngineerOptions().apply {
            tables = mutableListOf()
            codeGenerationMode = CodeGenerationMode.EFCore10
            databaseType = DatabaseType.SQLServerDacpac
            uiHint = sqlProjectPath
            projectRootNamespace = project.getProperty("RootNamespace")
            outputPath = "Models"
        }
        RevEngCore.saveOptions(session, null, null to null)
        return optionsPath to project
    }

    /** The body of ReverseEngineerCodeFirstAsync(project, optionsPath, onlyGenerate, fromSqlProj, uiHint). Off the EDT. */
    private fun run(project: DotNetProject, optionsPath: String, onlyGenerate: Boolean, fromSqlProj: Boolean, uiHint: String?, indicator: ProgressIndicator) {
        val ide = project.ideProject
        val session = RevEngCore.Session(project, optionsPath)
        RevEngCore.load(session, uiHint)?.let { Ui.showMessage(ide, it) }
        val options = session.options
        val userOptions = session.userOptions

        var forceEdit = false
        var neededPackages: List<NuGetPackage> = emptyList()
        var dbInfo: DatabaseConnectionModel? = null

        if (onlyGenerate || fromSqlProj) {
            forceEdit = !RevEngCore.chooseDataBaseConnectionByUiHint(session)
            if (forceEdit) {
                Ui.status(ide, RevEngLocale.DatabaseConnectionNotFoundCannotRefresh)
            } else {
                dbInfo = RevEngCore.getDatabaseInfo(session) ?: return
                options.customReplacers = session.namingOptionsAndPath.first
                options.installNuGetPackage = !onlyGenerate
            }
        }

        if (!onlyGenerate || forceEdit) {
            if (!fromSqlProj) {
                if (!RevEngCore.chooseDataBaseConnection(session)) {
                    Ui.status(ide, "")
                    return
                }
                Ui.status(ide, RevEngLocale.GettingReadyToConnect)
                indicator.text = RevEngLocale.GettingReadyToConnect
                dbInfo = RevEngCore.getDatabaseInfo(session)
                if (dbInfo == null) {
                    Ui.status(ide, "")
                    return
                }
            }

            // Rider: VS would fail with a NullReferenceException when a SQL project can no longer be found.
            if (dbInfo == null) {
                Ui.showError(ide, RevEngLocale.DatabaseConnectionNotFoundCannotRefresh)
                return
            }

            Ui.status(ide, RevEngLocale.LoadingDatabaseObjects)
            indicator.text = RevEngLocale.LoadingDatabaseObjects
            if (!loadDataBaseObjects(session, dbInfo)) {
                Ui.status(ide, "")
                return
            }

            Ui.status(ide, RevEngLocale.LoadingOptions)
            indicator.text = RevEngLocale.LoadingOptions
            neededPackages = RevEngCore.neededPackages(project, options)
            options.installNuGetPackage = neededPackages.any { it.databaseTypes.contains(options.databaseType) && !it.installed }

            if (!getModelOptions(session)) {
                Ui.status(ide, "")
                return
            }
            // Rider: the VS "HACK Work around for issue with web app project system on initial run" (dropping
            // the .user file for new configurations) is not needed in Rider, so the connection hint is kept.
        }

        RevEngCore.saveOptions(session, userOptions, options.customReplacers to session.namingOptionsAndPath.second)

        RevEngCore.installNuGetPackages(project, onlyGenerate, options, forceEdit, indicator = indicator)

        val missingProviderPackage = RevEngCore.missingProviderPackage(options, neededPackages)

        RevEngCore.generateFiles(project, options, missingProviderPackage, onlyGenerate, neededPackages, isCalledByWizard = false, indicator = indicator)

        RevEngCore.runPostRun(ide, optionsPath)
    }

    /** LoadDataBaseObjectsAsync. */
    private fun loadDataBaseObjects(session: RevEngCore.Session, dbInfo: DatabaseConnectionModel): Boolean {
        val options = session.options
        val predefinedTables = try {
            RevEngCore.getTables(options, dbInfo)
        } catch (e: IllegalStateException) {
            // InvalidOperationException in VS (table list errors)
            Ui.showError(session.ide, e.message ?: e.toString())
            return false
        }

        val isSqliteToolboxInstalled = options.databaseType != DatabaseType.SQLite
        val preselectedTables = mutableListOf<SerializationTableModel>()
        if (!options.tables.isNullOrEmpty()) {
            preselectedTables += ReverseEngineerHelper.normalizeTables(options.tables!!, dbInfo.databaseType == DatabaseType.SQLServer)
        }
        Ui.status(session.ide, "")

        val result = Ui.invokeAndWait {
            PickTablesDialog.show(
                session.ide,
                predefinedTables,
                session.namingOptionsAndPath.first,
                preselectedTables,
                isSqliteToolboxInstalled,
                RevEngLocale.ChooseDatabaseObjects,
            )
        } ?: return false
        options.tables = result.objects.toMutableList()
        options.customReplacers = result.customReplacers.toList<Schema>()
        return true
    }

    /** GetModelOptionsAsync. */
    private fun getModelOptions(session: RevEngCore.Session): Boolean {
        val options = session.options
        val presets = RevEngCore.modelPresets(options, session.project.name)
        val allowedTemplates = ReverseEngineerHelper.calculateAllowedTemplates(options.codeGenerationMode)
        Ui.status(session.ide, "")
        val result = Ui.invokeAndWait {
            EfCoreModelDialog.show(session.ide, presets, allowedTemplates, options.selectedHandlebarsLanguage)
        } ?: return false
        RevEngCore.applyModelResult(options, result)
        return true
    }
}
