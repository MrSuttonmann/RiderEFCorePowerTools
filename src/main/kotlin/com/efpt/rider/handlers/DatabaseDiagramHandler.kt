package com.efpt.rider.handlers

import com.efpt.rider.handlers.dbtools.DbTools
import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.model.ConnectionOrigin
import com.efpt.rider.model.DataApiBuilderOptions
import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.SchemaInfo
import com.efpt.rider.project.DotNetProject
import com.efpt.rider.project.SqlProjHelper
import com.efpt.rider.tools.BundledTools
import com.efpt.rider.tools.EfRevEngLauncher
import com.efpt.rider.ui.PickServerDatabaseRequest
import com.efpt.rider.ui.dgml.DgmlPreview
import com.efpt.rider.util.Ui
import com.intellij.openapi.project.Project
import java.io.File

/** Port of DatabaseDiagramHandler - "Visualize Database Schema" (DGML). [connectionName] = sqlproj/dacpac path or null. */
object DatabaseDiagramHandler {
    fun generate(project: DotNetProject, connectionName: String?) {
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

        Ui.runBackground(ide, "Visualizing database schema") { indicator ->
            val optionsPath = BundledTools.tempDir.resolve("diagram-options.json").toString()

            val options = DataApiBuilderOptions()
            options.projectPath = project.directory.toString()

            val (databaseModel, schemas) = chooseDataBaseConnection(ide, options, connectionName)
            if (databaseModel == null) {
                Ui.status(ide, "")
                return@runBackground
            }

            Ui.status(ide, DbTools.GETTING_READY_TO_CONNECT)
            indicator.text = DbTools.GETTING_READY_TO_CONNECT

            val dbInfo = DbTools.getDatabaseInfo(ide, options, setMergeDacpacs = true)
            if (dbInfo == null) {
                Ui.status(ide, "")
                return@runBackground
            }

            DbTools.saveOptions(optionsPath, options)

            indicator.text = "Generating database diagram..."
            val schemaList = schemas.orEmpty().mapNotNull { it.name }
            val diagramPath = EfRevEngLauncher(CodeGenerationMode.EFCore8)
                .getDiagram(dbInfo.connectionString ?: "", optionsPath, schemaList)

            if (File(diagramPath).exists()) {
                // VS: VS.Documents.OpenInPreviewTabAsync (DGML editor). Rider: DgmlPreview.
                DgmlPreview.open(ide, diagramPath)
            }
            Ui.status(ide, "")
        }
    }

    /** ChooseDataBaseConnectionAsync: a matching connection name / definition path skips the dialog. */
    private fun chooseDataBaseConnection(
        ide: Project,
        options: DataApiBuilderOptions,
        connectionName: String?,
    ): Pair<DatabaseConnectionModel?, List<SchemaInfo>?> {
        val databaseList = DbTools.connections(ide, dabOnly = false)
        val dacpacList = SqlProjHelper.getDacpacProjectsInActiveSolution(ide)

        if (!connectionName.isNullOrEmpty() && databaseList.isNotEmpty()) {
            val connection = databaseList.firstOrNull { it.connectionName == connectionName }
            if (connection != null) {
                options.connectionString = connection.connectionString
                options.databaseType = connection.databaseType
                return connection to emptyList()
            }
        }

        if (!connectionName.isNullOrEmpty() && !dacpacList.isNullOrEmpty()) {
            // VS compares with ==; Rider paths may differ only in case/separators on Windows.
            val path = dacpacList.firstOrNull { File(it) == File(connectionName) || it.equals(connectionName, ignoreCase = true) }
            if (path != null) {
                options.dacpac = path
                options.connectionString = path
                options.databaseType = DatabaseType.SQLServerDacpac
                return DatabaseConnectionModel(
                    filePath = path,
                    databaseType = DatabaseType.SQLServerDacpac,
                    origin = ConnectionOrigin.Definition,
                ) to emptyList()
            }
        }

        val request = PickServerDatabaseRequest(
            connections = databaseList,
            definitions = DbTools.definitions(dacpacList),
            schemas = null,
            codeGenerationMode = CodeGenerationMode.EFCore8,
            allowedVersions = emptyList(),
            uiHint = connectionName?.takeIf { it.isNotEmpty() },
        )
        val picked = DbTools.pickConnection(ide, request) ?: return null to null

        DbTools.applyPicked(options, picked.connection)
        return picked.connection to (if (picked.filterSchemas) picked.schemas else null)
    }
}
