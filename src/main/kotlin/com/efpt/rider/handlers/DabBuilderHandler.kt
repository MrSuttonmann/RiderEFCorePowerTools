package com.efpt.rider.handlers

import com.efpt.rider.handlers.dbtools.ConsoleRunner
import com.efpt.rider.handlers.dbtools.DbTools
import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.model.DataApiBuilderOptions
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.ObjectType
import com.efpt.rider.model.ReverseEngineerUserOptions
import com.efpt.rider.project.DotNetProject
import com.efpt.rider.tools.EfRevEngLauncher
import com.efpt.rider.util.Ui
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.ui.Messages
import java.io.File

/** Port of DabBuilderHandler - Data API Builder scaffolding. */
object DabBuilderHandler {
    fun buildDabConfig(project: DotNetProject) {
        val ide = project.ideProject
        try {
            if (Ui.isDebugging(ide)) {
                Ui.showError(ide, DbTools.CANNOT_GENERATE_WHILE_DEBUGGING)
                return
            }
        } catch (e: Exception) {
            Ui.logError(ide, emptyList(), e)
            return
        }

        Ui.runBackground(ide, "Building Data API Builder configuration") { indicator ->
            val projectPath = project.directory.toString()
            val optionsPath = File(projectPath, "dab-options.json").path

            val options = DbTools.tryReadDabOptions(optionsPath) ?: DataApiBuilderOptions()
            val userOptions = DbTools.tryReadUserOptions(optionsPath, projectPath) ?: ReverseEngineerUserOptions()

            options.projectPath = projectPath

            // ChooseDataBaseConnectionAsync
            val picked = DbTools.pickConnection(ide, DbTools.dabRequest(ide, userOptions.uiHint))
            if (picked == null) {
                Ui.status(ide, "")
                return@runBackground
            }
            DbTools.applyPicked(options, picked.connection)
            userOptions.uiHint = picked.uiHint

            Ui.status(ide, DbTools.GETTING_READY_TO_CONNECT)
            indicator.text = DbTools.GETTING_READY_TO_CONNECT

            val dbInfo = DbTools.getDatabaseInfo(ide, options, setMergeDacpacs = false)
            if (dbInfo == null) {
                Ui.status(ide, "")
                return@runBackground
            }

            Ui.status(ide, DbTools.LOADING_DATABASE_OBJECTS)
            indicator.text = DbTools.LOADING_DATABASE_OBJECTS

            val loaded = DbTools.loadDatabaseObjects(ide, options, dbInfo) { t ->
                t.objectType == ObjectType.Table ||
                    t.objectType == ObjectType.View ||
                    (t.objectType == ObjectType.Procedure &&
                        (options.databaseType == DatabaseType.SQLServer || options.databaseType == DatabaseType.SQLServerDacpac))
            }
            if (!loaded) {
                Ui.status(ide, "")
                return@runBackground
            }

            saveOptions(project, optionsPath, options, userOptions)

            // GenerateFilesAsync
            indicator.text = DbTools.GENERATING_CODE
            Ui.status(ide, DbTools.GENERATING_CODE)
            val started = System.currentTimeMillis()
            val cmdPath = EfRevEngLauncher(CodeGenerationMode.EFCore8).getDabConfigPath(optionsPath, dbInfo.connectionString ?: "")
            val elapsed = System.currentTimeMillis() - started

            if (File(cmdPath).exists()) {
                Ui.openFile(ide, cmdPath)
            }

            Ui.status(ide, DbTools.reverseEngineerCompleted(DbTools.formatElapsed(elapsed)))

            options.connectionString = null

            val envFile = File(projectPath, ".env")
            if (!envFile.exists()) {
                // VS.MessageBox.ShowConfirmAsync(line1, line2)
                val create = Ui.invokeAndWait {
                    Messages.showYesNoDialog(
                        ide,
                        "Create .env file with your connection string?\n\nRemember to exclude from source control!",
                        Ui.TITLE,
                        Messages.getQuestionIcon(),
                    ) == Messages.YES
                }
                if (create) {
                    envFile.writeText("dab-connection-string=${dbInfo.connectionString}", Charsets.UTF_8)
                    Ui.refreshPaths(envFile.path)
                }
            }
        }
    }

    /** SaveOptionsAsync. */
    private fun saveOptions(project: DotNetProject, optionsPath: String, options: DataApiBuilderOptions, userOptions: ReverseEngineerUserOptions?) {
        val file = File(optionsPath)
        if (file.exists() && !file.canWrite()) {
            Ui.showError(project.ideProject, "Unable to save options, the file is readonly: $optionsPath")
            return
        }

        if (!File("$optionsPath.ignore").exists()) {
            if (userOptions != null && !userOptions.uiHint.isNullOrEmpty()) {
                DbTools.writeUserOptions(optionsPath, userOptions, project.directory.toString())
            }

            DbTools.saveOptions(optionsPath, options)

            // Rider: project.AddExistingFilesAsync is not needed for SDK-style projects; refresh the VFS instead.
            Ui.refreshPaths(optionsPath)
        }
    }

    /**
     * DabBuilderHandler.LaunchDab - starts `dab start` for the dab-config.json at [configPath].
     * VS opens `cmd.exe /k "cd /d <folder> && dab start"`; Rider runs it in a Run tool window console tab
     * (with a stop button) with the config folder as working directory.
     */
    fun launchDab(ideProject: com.intellij.openapi.project.Project, configPath: String) {
        val path = File(configPath).parentFile ?: return
        val commandLine = GeneralCommandLine(ConsoleRunner.resolveTool("dab"), "start")
            .withWorkingDirectory(path.toPath())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        ConsoleRunner.run(
            ideProject,
            "dab start",
            commandLine,
            onStartFailure = {
                listOf(
                    "",
                    "Unable to start the Data API Builder CLI ('dab'). Install it with:",
                    "dotnet tool install -g Microsoft.DataApiBuilder",
                )
            },
        )
    }
}
