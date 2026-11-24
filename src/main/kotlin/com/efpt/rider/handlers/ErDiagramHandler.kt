package com.efpt.rider.handlers

import com.efpt.rider.handlers.dbtools.DbTools
import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.model.DataApiBuilderOptions
import com.efpt.rider.project.DotNetProject
import com.efpt.rider.tools.BundledTools
import com.efpt.rider.tools.EfRevEngLauncher
import com.efpt.rider.util.Ui
import java.io.File

/** Port of ErDiagramHandler - Mermaid ER diagram. [connectionName] preselects a connection ("" = ask). */
object ErDiagramHandler {
    fun buildErDiagram(project: DotNetProject, connectionName: String) {
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

        Ui.runBackground(ide, "Building Mermaid ER diagram") { indicator ->
            val optionsPath = BundledTools.tempDir.resolve("erdiagram-options.json").toString()

            val options = DataApiBuilderOptions()
            options.projectPath = project.directory.toString()

            // VS sets Optional (= Markdown output with a ```mermaid block instead of a raw .mmd file) only on
            // VS 18+, whose Markdown editor renders Mermaid. Rider: its Markdown preview renders Mermaid, so always.
            options.optional = true

            val request = DbTools.dabRequest(ide, connectionName)
            val picked = DbTools.pickConnection(ide, request)
            if (picked == null) {
                Ui.status(ide, "")
                return@runBackground
            }
            DbTools.applyPicked(options, picked.connection)

            Ui.status(ide, DbTools.GETTING_READY_TO_CONNECT)
            indicator.text = DbTools.GETTING_READY_TO_CONNECT

            val dbInfo = DbTools.getDatabaseInfo(ide, options, setMergeDacpacs = true)
            if (dbInfo == null) {
                Ui.status(ide, "")
                return@runBackground
            }

            Ui.status(ide, DbTools.LOADING_DATABASE_OBJECTS)
            indicator.text = DbTools.LOADING_DATABASE_OBJECTS

            if (!DbTools.loadDatabaseObjects(ide, options, dbInfo, DbTools::tablesAndViews)) {
                Ui.status(ide, "")
                return@runBackground
            }

            DbTools.saveOptions(optionsPath, options)

            // GenerateFilesAsync
            indicator.text = DbTools.GENERATING_CODE
            Ui.status(ide, DbTools.GENERATING_CODE)
            val started = System.currentTimeMillis()
            val cmdPath = EfRevEngLauncher(CodeGenerationMode.EFCore10).getErDiagram(optionsPath, dbInfo.connectionString ?: "")
            val elapsed = System.currentTimeMillis() - started

            if (File(cmdPath).exists()) {
                Ui.openPreview(ide, cmdPath)
            }

            Ui.status(ide, DbTools.reverseEngineerCompleted(DbTools.formatElapsed(elapsed)))

            options.connectionString = null
        }
    }
}
