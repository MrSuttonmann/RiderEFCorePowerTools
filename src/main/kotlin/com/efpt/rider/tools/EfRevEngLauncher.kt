package com.efpt.rider.tools

import com.efpt.rider.json.DcJson
import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.ObjectType
import com.efpt.rider.model.ReverseEngineerCommandOptions
import com.efpt.rider.model.ReverseEngineerOptions
import com.efpt.rider.model.ReverseEngineerResult
import com.efpt.rider.model.SchemaInfo
import com.efpt.rider.model.SerializationTableModel
import com.efpt.rider.model.TableModel
import com.efpt.rider.settings.EfptSettings
import com.efpt.rider.util.PathHelper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Port of EFCorePowerTools.Handlers.ReverseEngineer.EfRevEngLauncher: drives the bundled
 * efreveng{80,90,100} console app (database access, scaffolding, diagrams, reports).
 */
class EfRevEngLauncher(private val codeGenerationMode: CodeGenerationMode) {
    private val revEngVersion = codeGenerationMode.revEngVersion
    private val exeName = "efreveng${revEngVersion}0.dll"
    private val zipName = "efreveng${revEngVersion}0.exe.zip"
    private val folderPrefix = "efreveng$revEngVersion."

    /** Returns the database objects (tables, views, routines) of a database or .dacpac. */
    fun getTables(connectionString: String, databaseType: DatabaseType, schemas: List<SchemaInfo>?, mergeDacpacs: Boolean): List<TableModel> {
        val args = mutableListOf(mergeDacpacs.toString().replaceFirstChar { it.uppercase() }, databaseType.value.toString(), connectionString)
        if (schemas != null) args += schemas.joinToString(",") { it.name ?: "" }
        return ResultDeserializer.buildTableResult(run(args))
    }

    /** Database schema DGML diagram; returns the path of the generated .dgml file. */
    fun getDiagram(connectionString: String, optionsPath: String, schemaList: List<String>): String {
        val args = mutableListOf("dgml", optionsPath, connectionString)
        if (schemaList.isNotEmpty()) args += schemaList.joinToString(",")
        return ResultDeserializer.buildDiagramResult(run(args))
    }

    /** Mermaid ER diagram; returns the path of the generated markdown file. */
    fun getErDiagram(optionsPath: String, connectionString: String): String =
        ResultDeserializer.buildDiagramResult(run(listOf("erdiagram", optionsPath, connectionString)))

    /** .dacpac / database analysis report; returns the report path. */
    fun getReportPath(path: String, isConnectionString: Boolean): String =
        ResultDeserializer.buildDiagramResult(run(listOf(if (isConnectionString) "dacpacreportextract" else "dacpacreport", path)))

    /** Data API Builder configuration script; returns the generated script path. */
    fun getDabConfigPath(optionsPath: String, connectionString: String): String =
        ResultDeserializer.buildDiagramResult(run(listOf("dabbuilder", optionsPath, connectionString)))

    /** Runs code generation with a serialized ReverseEngineerCommandOptions file. */
    fun generate(commandOptions: ReverseEngineerCommandOptions): ReverseEngineerResult {
        val path = Files.createTempFile("efpt", ".json")
        try {
            DcJson.writeFile(path, commandOptions.write())
            return ResultDeserializer.buildResult(run(listOf(path.toString()), checkRuntime = false))
        } finally {
            runCatching { Files.deleteIfExists(path) }
        }
    }

    private fun run(args: List<String>, checkRuntime: Boolean = true): String {
        if (checkRuntime) ensureRuntime()
        val launchPath = dropNetCoreFiles()
        val cl = ProcessRunner.dotnet(launchPath.toString(), *args.toTypedArray())
        return ProcessRunner.run(cl)
    }

    private fun ensureRuntime() {
        val version = if (codeGenerationMode == CodeGenerationMode.EFCore10) "10.0" else "8.0"
        if (!ProcessRunner.isRuntimeInstalled(version.substringBefore('.'))) {
            throw IllegalStateException(
                "Reverse engineer error: Unable to launch 'dotnet' version $version. Do you have the runtime installed? Check with 'dotnet --list-runtimes'",
            )
        }
    }

    private fun dropNetCoreFiles(): Path =
        BundledTools.extractVersioned(folderPrefix, listOf(zipName, "DacFX161.zip")).resolve(exeName)

    companion object {
        /** Dummy table name used to generate an empty DbContext when no tables are selected. */
        const val DUMMY_TABLE_PREFIX = "Dummy_f4c4712c-ceae-4803-8e52-0e2049d5de9f"

        /** Port of EfRevEngLauncher.LaunchExternalRunnerAsync. */
        fun launchExternalRunner(options: ReverseEngineerOptions, codeGenerationMode: CodeGenerationMode): ReverseEngineerResult {
            val databaseObjects = options.tables ?: mutableListOf<SerializationTableModel>().also { options.tables = it }
            if (databaseObjects.none { it.objectType == ObjectType.Table }) {
                // No tables selected, so add a dummy table in order to generate an empty DbContext
                databaseObjects.add(SerializationTableModel(DUMMY_TABLE_PREFIX, ObjectType.Table, null, null))
            }
            val advanced = EfptSettings.instance
            val commandOptions = ReverseEngineerCommandOptions().apply {
                connectionString = options.connectionString
                contextClassName = options.contextClassName
                customReplacers = options.customReplacers
                dacpac = options.dacpac
                databaseType = options.databaseType
                defaultDacpacSchema = options.defaultDacpacSchema
                includeConnectionString = options.includeConnectionString
                outputPath = options.outputPath
                contextNamespace = options.contextNamespace
                modelNamespace = options.modelNamespace
                outputContextPath = options.outputContextPath
                useSchemaFolders = options.useSchemaFolders
                projectPath = options.projectPath
                projectRootNamespace = options.projectRootNamespace
                selectedToBeGenerated = options.selectedToBeGenerated
                tables = databaseObjects
                useDatabaseNames = options.useDatabaseNames
                useFluentApiOnly = options.useFluentApiOnly
                useHandleBars = options.useHandleBars
                selectedHandlebarsLanguage = options.selectedHandlebarsLanguage
                useT4 = options.useT4
                useT4Split = options.useT4Split
                t4TemplatePath = options.t4TemplatePath?.let { PathHelper.getAbsPath(it, options.projectPath ?: "") }
                useInflector = options.useInflector
                useLegacyPluralizer = options.useLegacyPluralizer
                uncountableWords = options.uncountableWords
                singularRules = options.singularRules
                pluralRules = options.pluralRules
                irregularWords = options.irregularWords
                useSpatial = options.useSpatial
                useHierarchyId = options.useHierarchyId
                useDbContextSplitting = options.useDbContextSplitting
                useNodaTime = options.useNodaTime
                useBoolPropertiesWithoutDefaultSql = options.useBoolPropertiesWithoutDefaultSql
                useNullableReferences = options.useNullableReferences
                useNoObjectFilter = options.useNoObjectFilter
                useNoNavigations = options.useNoNavigations
                useNoDefaultConstructor = options.useNoDefaultConstructor
                useManyToManyEntity = options.useManyToManyEntity
                runCleanup = advanced.runCleanup
                useMultipleSprocResultSets = advanced.discoverMultipleResultSets
                optionsPath = options.optionsPath
                mergeDacpacs = advanced.mergeDacpacs
                useLegacyResultSetDiscovery = advanced.useLegacyResultSetDiscovery
                useStoredProcedureResultSetFallback = advanced.useStoredProcedureResultSetFallback
                useAsyncCalls = options.useAsyncStoredProcedureCalls
                preserveCasingWithRegex = options.preserveCasingWithRegex
                useDateOnlyTimeOnly = options.useDateOnlyTimeOnly
                useSchemaNamespaces = options.useSchemaNamespaces
                useDecimalDataAnnotation = options.useDecimalDataAnnotationForSprocResult
                usePrefixNavigationNaming = options.usePrefixNavigationNaming
                useDatabaseNamesForRoutines = options.useDatabaseNamesForRoutines
                useTypedTvpParameters = options.useTypedTvpParameters
            }
            return EfRevEngLauncher(codeGenerationMode).generate(commandOptions)
        }
    }
}

/** Port of ResultDeserializer - parses the "Result:" / "Error:" protocol of the .NET runners. */
object ResultDeserializer {
    private val resultMarker = Regex("Result:\\r?\\n")
    private val errorMarker = Regex("Error:\\r?\\n")
    private val nl = System.lineSeparator()

    fun buildResult(output: String): ReverseEngineerResult {
        val parts = output.split(resultMarker)
        if (parts.size == 2) {
            runCatching { return ReverseEngineerResult.fromJson(DcJson.parse(parts[1]).asJsonObject) }
        }
        val errorParts = output.split(errorMarker)
        if (errorParts.size == 2) throw IllegalStateException("Reverse engineer error: $nl${errorParts[1]}")
        throw IllegalStateException("Reverse engineer error: Unable to launch external process: $nl$output")
    }

    fun buildTableResult(output: String): List<TableModel> {
        val parts = output.split(resultMarker)
        if (parts.size == 2) {
            runCatching { return TableModel.listFromJson(parts[1]) }
        }
        val errorParts = output.split(errorMarker)
        if (errorParts.size == 2) throw IllegalStateException("Table list error: $nl${errorParts[1]}")
        throw IllegalStateException("Table list error: Unable to launch external process: $nl$output")
    }

    fun buildDiagramResult(output: String): String {
        val parts = output.split(resultMarker)
        if (parts.size == 2) return parts[1].trim()
        val errorParts = output.split(errorMarker)
        if (errorParts.size == 2) throw IllegalStateException("Result error: $nl${errorParts[1]}")
        throw IllegalStateException("Launch error: Unable to launch external process: $nl$output")
    }
}
