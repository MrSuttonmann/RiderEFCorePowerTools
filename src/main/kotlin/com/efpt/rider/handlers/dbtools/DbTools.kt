package com.efpt.rider.handlers.dbtools

import com.efpt.rider.connections.DataConnections
import com.efpt.rider.json.DcJson
import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.model.ConnectionOrigin
import com.efpt.rider.model.DataApiBuilderOptions
import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.ObjectType
import com.efpt.rider.model.ReverseEngineerUserOptions
import com.efpt.rider.model.SchemaInfo
import com.efpt.rider.model.SerializationTableModel
import com.efpt.rider.model.TableModel
import com.efpt.rider.project.Providers
import com.efpt.rider.project.SqlProjHelper
import com.efpt.rider.settings.EfptSettings
import com.efpt.rider.tools.EfRevEngLauncher
import com.efpt.rider.ui.CodeGenerationItem
import com.efpt.rider.ui.PickServerDatabaseDialog
import com.efpt.rider.ui.PickServerDatabaseRequest
import com.efpt.rider.ui.PickServerDatabaseResult
import com.efpt.rider.ui.tables.PickTablesDialog
import com.efpt.rider.util.PathHelper
import com.efpt.rider.util.Ui
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Path

/** Shared code of the "database tools" handlers (ER diagram, DGML database diagram, DAB builder, CLI). */
internal object DbTools {
    // English texts of ReverseEngineerLocale used by these handlers.
    const val CANNOT_GENERATE_WHILE_DEBUGGING = "Cannot generate code while debugging"
    const val GETTING_READY_TO_CONNECT = "Getting ready to connect..."
    const val LOADING_DATABASE_OBJECTS = "Loading database objects..."
    const val UNABLE_TO_BUILD_DATABASE_PROJECT = "Unable to build selected Database Project"
    const val UNSUPPORTED_PROVIDER = "Unsupported provider"
    const val GENERATING_CODE = "Generating code..."
    fun reverseEngineerCompleted(elapsed: String) = "Reverse engineer completed in $elapsed"

    /** Stopwatch.Elapsed.ToString(@"mm\:ss"). */
    fun formatElapsed(millis: Long): String {
        val totalSeconds = millis / 1000
        return "%02d:%02d".format((totalSeconds / 60) % 60, totalSeconds % 60)
    }

    /** The "DAB" entry the DAB / ER diagram / CLI handlers publish with PublishCodeGenerationMode. */
    val dabCodeGenerationItems = listOf(CodeGenerationItem(CodeGenerationMode.EFCore10.value, "DAB"))

    /** VsDataHelper.GetDataConnectionsAsync (+ optional Providers.DabProviders filter). Off the EDT. */
    fun connections(project: Project, dabOnly: Boolean): List<DatabaseConnectionModel> {
        val all = DataConnections.getDataConnections(project).values
        return if (dabOnly) all.filter { Providers.dabProviders.contains(it.databaseType) } else all.toList()
    }

    /** SqlProjHelper.GetDacpacProjectsInActiveSolutionAsync wrapped as DatabaseConnectionModel definitions. Off the EDT. */
    fun definitions(dacpacList: List<String>?): List<DatabaseConnectionModel> =
        dacpacList.orEmpty().map {
            DatabaseConnectionModel(filePath = it, databaseType = DatabaseType.SQLServerDacpac, origin = ConnectionOrigin.Definition)
        }

    /** Shows PickServerDatabaseDialog on the EDT (from a background thread). Null when cancelled. */
    fun pickConnection(project: Project, request: PickServerDatabaseRequest): PickServerDatabaseResult? =
        Ui.invokeAndWait { PickServerDatabaseDialog.show(project, request) }

    /** Copies the picked connection into [options] (ChooseDataBaseConnectionAsync tail). */
    fun applyPicked(options: DataApiBuilderOptions, connection: DatabaseConnectionModel?) {
        options.dacpac = connection?.filePath
        if (connection != null) {
            options.connectionString = connection.connectionString
            options.databaseType = connection.databaseType
        }
    }

    /**
     * GetDatabaseInfoAsync shared by the handlers: for a database definition builds the SQL project and
     * points [options] at the .dacpac. Returns null (after showing the VS message) when not usable.
     * [setMergeDacpacs] = ER / DGML diagram handlers; [setFilePath] = CLI handler.
     */
    fun getDatabaseInfo(
        project: Project,
        options: DataApiBuilderOptions,
        setMergeDacpacs: Boolean,
        setFilePath: Boolean = false,
    ): DatabaseConnectionModel? {
        val dbInfo = DatabaseConnectionModel()

        if (!options.connectionString.isNullOrEmpty()) {
            dbInfo.connectionString = options.connectionString
            dbInfo.databaseType = options.databaseType
        }

        val dacpac = options.dacpac
        if (!dacpac.isNullOrEmpty()) {
            dbInfo.databaseType = DatabaseType.SQLServerDacpac
            dbInfo.connectionString =
                "Data Source=(local);Initial Catalog=${PathHelper.fileNameWithoutExtension(dacpac)};Integrated Security=true;"
            options.connectionString = dbInfo.connectionString
            options.databaseType = dbInfo.databaseType
            if (setMergeDacpacs) options.mergeDacpacs = EfptSettings.instance.mergeDacpacs

            options.dacpac = SqlProjHelper.buildSqlProject(project, dacpac)
            if (options.dacpac.isNullOrEmpty()) {
                Ui.showMessage(project, UNABLE_TO_BUILD_DATABASE_PROJECT)
                return null
            }
            if (setFilePath) dbInfo.filePath = options.dacpac
        }

        if (dbInfo.databaseType == DatabaseType.Undefined) {
            Ui.showError(project, UNSUPPORTED_PROVIDER)
            return null
        }

        return dbInfo
    }

    /**
     * LoadDataBaseObjectsAsync: loads tables (via the efreveng EF Core 10 runner), filters them with [include],
     * shows PickTablesDialog with the options' current tables preselected and stores the selection.
     * Returns false when cancelled or the objects could not be loaded.
     */
    fun loadDatabaseObjects(
        project: Project,
        options: DataApiBuilderOptions,
        dbInfo: DatabaseConnectionModel,
        include: (TableModel) -> Boolean,
    ): Boolean {
        val predefinedTables: List<TableModel>
        try {
            val launcher = EfRevEngLauncher(CodeGenerationMode.EFCore10)
            val mergeDacpacs = EfptSettings.instance.mergeDacpacs
            val tables = if (!options.dacpac.isNullOrEmpty()) {
                launcher.getTables(options.dacpac!!, DatabaseType.SQLServerDacpac, null, mergeDacpacs)
            } else {
                // Rider: connections already carry the plain connection string (no DataConnection.Open/Decrypt).
                launcher.getTables(dbInfo.connectionString ?: "", dbInfo.databaseType, null, mergeDacpacs)
            }
            predefinedTables = tables.filter(include)
        } catch (ex: IllegalStateException) {
            // VS: catch (InvalidOperationException ex) => ShowError(ex.Message)
            Ui.showError(project, ex.message ?: ex.toString())
            return false
        }

        val preselectedTables = mutableListOf<SerializationTableModel>()
        val current = options.tables
        if (!current.isNullOrEmpty()) {
            preselectedTables.addAll(normalizeTables(current, dbInfo.databaseType == DatabaseType.SQLServer))
        }

        Ui.status(project, "")

        val result = Ui.invokeAndWait {
            PickTablesDialog.show(project, predefinedTables, emptyList(), preselectedTables, sqliteToolboxInstalled = true)
        } ?: return false

        options.tables = result.objects.toMutableList()
        return true
    }

    fun tablesAndViews(t: TableModel) = t.objectType == ObjectType.Table || t.objectType == ObjectType.View

    /** ReverseEngineerHelper.NormalizeTables. */
    fun normalizeTables(tables: List<SerializationTableModel>, shouldFix: Boolean): List<SerializationTableModel> =
        tables.map { table ->
            if (shouldFix && !table.name.startsWith("[")) {
                table.name = "[" + table.name.replaceFirst(".", "].[") + "]"
            }
            table
        }

    /** Writes [options] like File.WriteAllText(path, options.Write(), Encoding.UTF8). */
    fun saveOptions(optionsPath: String, options: DataApiBuilderOptions) {
        DcJson.writeFile(Path.of(optionsPath), options.write())
    }

    // ------------------------------------------------------------------ DataApiBuilderOptionsExtensions.TryRead

    fun tryReadDabOptions(optionsPath: String?): DataApiBuilderOptions? {
        if (optionsPath.isNullOrEmpty()) return null
        if (!File(optionsPath).exists()) return null
        if (File("$optionsPath.ignore").exists()) return null
        return runCatching { DataApiBuilderOptions.fromJson(DcJson.readFile(Path.of(optionsPath)).asJsonObject) }.getOrNull()
    }

    // ------------------------------------------------------------------ ReverseEngineerUserOptionsExtensions

    /** ReverseEngineerUserOptionsExtensions.TryRead: reads "[optionsPath].user". */
    fun tryReadUserOptions(optionsPath: String, projectDirectory: String): ReverseEngineerUserOptions? {
        val path = File("$optionsPath.user")
        if (!path.exists()) return null
        val options = runCatching { ReverseEngineerUserOptions.fromJson(DcJson.readFile(path.toPath()).asJsonObject) }.getOrNull()
            ?: return null
        options.uiHint?.takeIf { it.isNotEmpty() }?.let { options.uiHint = PathHelper.getFullPathForSqlProj(it, projectDirectory) }
        return options
    }

    /** ReverseEngineerUserOptionsExtensions.Write + File.WriteAllText("[optionsPath].user", ..., UTF8). */
    fun writeUserOptions(optionsPath: String, options: ReverseEngineerUserOptions, projectDirectory: String) {
        val hint = options.uiHint
        if (!hint.isNullOrEmpty() && PathHelper.isDefinitionPath(hint)) {
            options.uiHint = PathHelper.setRelativePathForSqlProj(hint, projectDirectory)
        }
        DcJson.writeFile(Path.of("$optionsPath.user"), DcJson.write(options.toJson()))
    }

    /** Request used by the DAB-style handlers (DAB builder, ER diagram, CLI). */
    fun dabRequest(project: Project, uiHint: String?): PickServerDatabaseRequest {
        val connections = connections(project, dabOnly = true)
        val definitions = definitions(SqlProjHelper.getDacpacProjectsInActiveSolution(project))
        return PickServerDatabaseRequest(
            connections = connections,
            definitions = definitions,
            schemas = emptyList<SchemaInfo>(),
            codeGenerationMode = CodeGenerationMode.EFCore10,
            allowedVersions = dabCodeGenerationItems,
            uiHint = uiHint?.takeIf { it.isNotEmpty() },
        )
    }
}
