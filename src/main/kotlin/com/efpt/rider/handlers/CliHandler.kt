package com.efpt.rider.handlers

import com.efpt.rider.handlers.dbtools.ConsoleRunner
import com.efpt.rider.handlers.dbtools.DbTools
import com.efpt.rider.model.DataApiBuilderOptions
import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.ReverseEngineerUserOptions
import com.efpt.rider.project.DotNetProject
import com.efpt.rider.util.Ui
import com.intellij.execution.configurations.GeneralCommandLine
import java.io.File

/** Port of CliHandler - efcpt-config.json editing and running the efcpt CLI. */
object CliHandler {
    private const val CONFIG_FILE_NAME = "efcpt-config.json"

    /** CliHandler.EditConfigAsync. */
    fun editConfig(project: DotNetProject) {
        try {
            val optionsPath = project.directory.resolve(CONFIG_FILE_NAME).toFile()
            if (!optionsPath.exists()) return
            // VS opens the file in the JSON editor (validated against the "$schema" in the file); so does Rider.
            Ui.openFile(project.ideProject, optionsPath.path)
        } catch (e: Exception) {
            Ui.logError(project.ideProject, emptyList(), e)
        }
    }

    /** CliHandler.RunCliAsync. */
    fun runCli(project: DotNetProject) {
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

        val projectPath = project.directory.toString()
        val optionsPath = File(projectPath, CONFIG_FILE_NAME).path
        if (!File(optionsPath).exists()) return

        Ui.runBackground(ide, "Running efcpt") { indicator ->
            val userOptions = DbTools.tryReadUserOptions(optionsPath, projectPath) ?: ReverseEngineerUserOptions()

            val options = DataApiBuilderOptions()

            // ChooseDataBaseConnectionAsync (VS filters the connections with Providers.DabProviders here as well)
            val picked = DbTools.pickConnection(ide, DbTools.dabRequest(ide, userOptions.uiHint))
            if (picked == null) {
                Ui.status(ide, "")
                return@runBackground
            }
            DbTools.applyPicked(options, picked.connection)
            userOptions.uiHint = picked.uiHint

            Ui.status(ide, DbTools.GETTING_READY_TO_CONNECT)
            indicator.text = DbTools.GETTING_READY_TO_CONNECT

            val dbInfo = DbTools.getDatabaseInfo(ide, options, setMergeDacpacs = false, setFilePath = true)
            if (dbInfo == null) {
                Ui.status(ide, "")
                return@runBackground
            }

            // SaveOptions
            if (!userOptions.uiHint.isNullOrEmpty()) {
                DbTools.writeUserOptions(optionsPath, userOptions, projectPath)
                Ui.refreshPaths("$optionsPath.user")
            }

            Ui.status(ide, "")
            launchCli(project, optionsPath, dbInfo)
        }
    }

    /**
     * LaunchCli. VS opens `cmd.exe /k "cd /d <folder> && efcpt "<dacpac or connection string>" <provider>"`.
     * Rider runs the same efcpt command line in a Run tool window console tab (working directory = the
     * config folder), so the CLI's own output (including its update/version check) is visible.
     */
    private fun launchCli(project: DotNetProject, configPath: String, database: DatabaseConnectionModel) {
        val path = File(configPath).parentFile
        val commandLine = GeneralCommandLine(
            ConsoleRunner.resolveTool("efcpt"),
            database.filePath ?: database.connectionString ?: "",
            com.efpt.rider.project.Providers.toDatabaseShortName(database.databaseType),
        )
            .withWorkingDirectory(path.toPath())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        ConsoleRunner.run(
            project.ideProject,
            "efcpt",
            commandLine,
            onStartFailure = {
                // Rider: cmd.exe would print "'efcpt' is not recognized..."; give the install command instead.
                val version = 10 // Upstream only supports EF Core 10 (#3562).
                listOf(
                    "",
                    "Unable to start the EF Core Power Tools CLI ('efcpt'). Install it with:",
                    "dotnet tool install ErikEJ.EFCorePowerTools.Cli -g --version $version.*",
                )
            },
            rerun = { launchCli(project, configPath, database) },
        )
    }
}
