package com.efpt.rider.model

import com.efpt.rider.json.DcJson
import com.efpt.rider.json.JsonWritable
import com.efpt.rider.json.bool
import com.efpt.rider.json.int
import com.efpt.rider.json.long
import com.efpt.rider.json.objects
import com.efpt.rider.json.put
import com.efpt.rider.json.putIfNotDefault
import com.efpt.rider.json.str
import com.efpt.rider.json.strings
import com.google.gson.JsonArray
import com.google.gson.JsonObject

// Kotlin ports of the RevEng.Common DTOs. JSON member names and default-value emission match
// the [DataContract]/[DataMember] declarations of the C# originals.

class SchemaInfo(var name: String? = null) : JsonWritable {
    override fun toJson() = JsonObject().apply { put("Name", name) }

    companion object {
        fun fromJson(o: JsonObject) = SchemaInfo(o.str("Name"))
    }
}

class IrregularWord(var singular: String? = null, var plural: String? = null, var matchEnding: Boolean = true) : JsonWritable {
    override fun toJson() = JsonObject().apply {
        put("MatchEnding", matchEnding)
        put("Plural", plural)
        put("Singular", singular)
    }

    companion object {
        fun fromJson(o: JsonObject) = IrregularWord(o.str("Singular"), o.str("Plural"), o.bool("MatchEnding"))
    }
}

class ReplacementRule(var rule: String? = null, var replacement: String? = null) : JsonWritable {
    override fun toJson() = JsonObject().apply {
        put("Replacement", replacement)
        put("Rule", rule)
    }

    companion object {
        fun fromJson(o: JsonObject) = ReplacementRule(o.str("Rule"), o.str("Replacement"))
    }
}

/** An object selected for generation, as persisted in efpt.config.json "Tables". */
class SerializationTableModel(
    var name: String,
    var objectType: ObjectType,
    excludedColumns: List<String>? = null,
    excludedIndexes: List<String>? = null,
) : JsonWritable {
    var excludedColumns: List<String>? = excludedColumns?.takeIf { it.isNotEmpty() }
    var excludedIndexes: List<String>? = excludedIndexes?.takeIf { it.isNotEmpty() }
    var useLegacyResultSetDiscovery: Boolean = false
    var mappedType: String? = null
    var generateEmptyResultType: Boolean = false

    init {
        require(name.isNotBlank()) { "Value cannot be empty or only white spaces." }
    }

    override fun toJson() = JsonObject().apply {
        putIfNotDefault("ExcludedColumns", excludedColumns)
        putIfNotDefault("ExcludedIndexes", excludedIndexes)
        putIfNotDefault("GenerateEmptyResultType", generateEmptyResultType)
        putIfNotDefault("MappedType", mappedType)
        put("Name", name)
        put("ObjectType", objectType.value)
        putIfNotDefault("UseLegacyResultSetDiscovery", useLegacyResultSetDiscovery)
    }

    fun copy(): SerializationTableModel = SerializationTableModel(name, objectType, excludedColumns, excludedIndexes).also {
        it.useLegacyResultSetDiscovery = useLegacyResultSetDiscovery
        it.mappedType = mappedType
        it.generateEmptyResultType = generateEmptyResultType
    }

    override fun toString() = name

    companion object {
        fun fromJson(o: JsonObject) = SerializationTableModel(
            o.str("Name") ?: "",
            ObjectType.fromValue(o.int("ObjectType")),
            o.strings("ExcludedColumns"),
            o.strings("ExcludedIndexes"),
        ).also {
            it.useLegacyResultSetDiscovery = o.bool("UseLegacyResultSetDiscovery")
            it.mappedType = o.str("MappedType")
            it.generateEmptyResultType = o.bool("GenerateEmptyResultType")
        }
    }
}

class ColumnModel(
    var name: String,
    var storeType: String? = null,
    var isPrimaryKey: Boolean = false,
    var isForeignKey: Boolean = false,
) : JsonWritable {
    override fun toJson() = JsonObject().apply {
        putIfNotDefault("IsForeignKey", isForeignKey)
        putIfNotDefault("IsPrimaryKey", isPrimaryKey)
        put("Name", name)
        putIfNotDefault("StoreType", storeType)
    }

    override fun toString() = name

    companion object {
        fun fromJson(o: JsonObject) = ColumnModel(o.str("Name") ?: "", o.str("StoreType"), o.bool("IsPrimaryKey"), o.bool("IsForeignKey"))
    }
}

/** A database object as discovered by efreveng. */
class TableModel(
    var name: String,
    var schema: String?,
    val databaseType: DatabaseType,
    var objectType: ObjectType,
    var columns: List<ColumnModel>?,
) : JsonWritable {
    val displayName: String
        get() = when {
            databaseType == DatabaseType.SQLServer || databaseType == DatabaseType.SQLServerDacpac -> "[$schema].[$name]"
            schema.isNullOrEmpty() -> name
            else -> "$schema.$name"
        }

    override fun toJson() = JsonObject().apply {
        putIfNotDefault("Columns", columns)
        put("DatabaseType", databaseType.value)
        put("Name", name)
        put("ObjectType", objectType.value)
        put("Schema", schema)
    }

    override fun toString() = displayName

    companion object {
        fun fromJson(o: JsonObject) = TableModel(
            o.str("Name") ?: "",
            o.str("Schema"),
            DatabaseType.fromValue(o.int("DatabaseType")),
            ObjectType.fromValue(o.int("ObjectType")),
            o.objects("Columns", ColumnModel::fromJson),
        )

        fun listFromJson(json: String): List<TableModel> =
            DcJson.parse(json).asJsonArray.map { fromJson(it.asJsonObject) }
    }
}

class ColumnNamer(var name: String? = null, var newName: String? = null) : JsonWritable {
    override fun toJson() = JsonObject().apply {
        put("Name", name)
        put("NewName", newName)
    }

    companion object {
        fun fromJson(o: JsonObject) = ColumnNamer(o.str("Name"), o.str("NewName"))
    }
}

class TableRenamer(var name: String? = null, var newName: String? = null, var columns: MutableList<ColumnNamer>? = null) : JsonWritable {
    override fun toJson() = JsonObject().apply {
        putIfNotDefault("Columns", columns)
        put("Name", name)
        put("NewName", newName)
    }

    companion object {
        fun fromJson(o: JsonObject) = TableRenamer(o.str("Name"), o.str("NewName"), o.objects("Columns", ColumnNamer::fromJson)?.toMutableList())
    }
}

/** One entry of efpt.renaming.json. */
class Schema : JsonWritable {
    var useSchemaName: Boolean = false
    var schemaName: String? = null
    var tables: MutableList<TableRenamer>? = mutableListOf()
    var tableRegexPattern: String? = null
    var tablePatternReplaceWith: String? = null
    var columnRegexPattern: String? = null
    var columnPatternReplaceWith: String? = null

    override fun toJson() = JsonObject().apply {
        putIfNotDefault("ColumnPatternReplaceWith", columnPatternReplaceWith)
        putIfNotDefault("ColumnRegexPattern", columnRegexPattern)
        putIfNotDefault("SchemaName", schemaName)
        putIfNotDefault("TablePatternReplaceWith", tablePatternReplaceWith)
        putIfNotDefault("TableRegexPattern", tableRegexPattern)
        putIfNotDefault("Tables", tables)
        put("UseSchemaName", useSchemaName)
    }

    companion object {
        fun fromJson(o: JsonObject) = Schema().apply {
            useSchemaName = o.bool("UseSchemaName")
            schemaName = o.str("SchemaName")
            tables = o.objects("Tables", TableRenamer::fromJson)?.toMutableList()
            tableRegexPattern = o.str("TableRegexPattern")
            tablePatternReplaceWith = o.str("TablePatternReplaceWith")
            columnRegexPattern = o.str("ColumnRegexPattern")
            columnPatternReplaceWith = o.str("ColumnPatternReplaceWith")
        }

        fun listFromJson(text: String): List<Schema> = DcJson.parse(text).asJsonArray.map { fromJson(it.asJsonObject) }

        fun listToJson(list: List<Schema>): String = DcJson.write(JsonArray().also { a -> list.forEach { a.add(it.toJson()) } })
    }
}

/** Mirrors RevEng.Common.ReverseEngineerOptions - the efpt.config.json file. */
class ReverseEngineerOptions : JsonWritable {
    // [IgnoreDataMember] - runtime only
    var databaseType: DatabaseType = DatabaseType.Undefined
    var connectionString: String? = null
    var projectPath: String? = null
    var optionsPath: String? = null
    var dacpac: String? = null
    var customReplacers: List<Schema>? = null
    var defaultDacpacSchema: String? = null
    var installNuGetPackage: Boolean = false

    // Persisted
    var outputPath: String? = null
    var outputContextPath: String? = null
    var useSchemaFolders: Boolean = false
    var useSchemaNamespaces: Boolean = false
    var projectRootNamespace: String? = null
    var modelNamespace: String? = null
    var contextNamespace: String? = null
    var useFluentApiOnly: Boolean = true
    var contextClassName: String? = null
    var tables: MutableList<SerializationTableModel>? = null
    var useDatabaseNames: Boolean = false
    var useInflector: Boolean = true
    var uncountableWords: List<String>? = null
    var irregularWords: List<IrregularWord>? = null
    var pluralRules: List<ReplacementRule>? = null
    var singularRules: List<ReplacementRule>? = null
    var useHandleBars: Boolean = false
    var useT4: Boolean = false
    var useT4Split: Boolean = false
    var selectedHandlebarsLanguage: Int = 2
    var includeConnectionString: Boolean = false
    var selectedToBeGenerated: Int = 0
    var useLegacyPluralizer: Boolean = false
    var useSpatial: Boolean = false
    var useHierarchyId: Boolean = false
    var useDbContextSplitting: Boolean = false
    var useNodaTime: Boolean = false
    var filterSchemas: Boolean = false
    var useBoolPropertiesWithoutDefaultSql: Boolean = false
    var useNullableReferences: Boolean = false
    var useNoObjectFilter: Boolean = false
    var useNoDefaultConstructor: Boolean = false
    var useNoNavigations: Boolean = false
    var useManyToManyEntity: Boolean = false
    var codeGenerationMode: CodeGenerationMode = CodeGenerationMode.EFCore8
    var uiHint: String? = null
    var schemas: MutableList<SchemaInfo>? = null
    var preserveCasingWithRegex: Boolean = true
    var useDateOnlyTimeOnly: Boolean = true
    var t4TemplatePath: String? = null
    var useDecimalDataAnnotationForSprocResult: Boolean = true
    var usePrefixNavigationNaming: Boolean = false
    var useAsyncStoredProcedureCalls: Boolean = true
    var useDatabaseNamesForRoutines: Boolean = true
    var useInternalAccessModifiersForSprocsAndFunctions: Boolean = false
    var minimumProductVersion: String? = null
    var useTypedTvpParameters: Boolean = true

    override fun toJson() = JsonObject().apply {
        put("CodeGenerationMode", codeGenerationMode.value)
        put("ContextClassName", contextClassName)
        put("ContextNamespace", contextNamespace)
        put("FilterSchemas", filterSchemas)
        put("IncludeConnectionString", includeConnectionString)
        put("IrregularWords", irregularWords)
        put("MinimumProductVersion", minimumProductVersion)
        put("ModelNamespace", modelNamespace)
        put("OutputContextPath", outputContextPath)
        put("OutputPath", outputPath)
        put("PluralRules", pluralRules)
        put("PreserveCasingWithRegex", preserveCasingWithRegex)
        put("ProjectRootNamespace", projectRootNamespace)
        put("Schemas", schemas)
        put("SelectedHandlebarsLanguage", selectedHandlebarsLanguage)
        put("SelectedToBeGenerated", selectedToBeGenerated)
        put("SingularRules", singularRules)
        put("T4TemplatePath", t4TemplatePath)
        put("Tables", tables)
        put("UiHint", uiHint)
        put("UncountableWords", uncountableWords)
        put("UseAsyncStoredProcedureCalls", useAsyncStoredProcedureCalls)
        put("UseBoolPropertiesWithoutDefaultSql", useBoolPropertiesWithoutDefaultSql)
        put("UseDatabaseNames", useDatabaseNames)
        put("UseDatabaseNamesForRoutines", useDatabaseNamesForRoutines)
        put("UseDateOnlyTimeOnly", useDateOnlyTimeOnly)
        put("UseDbContextSplitting", useDbContextSplitting)
        put("UseDecimalDataAnnotationForSprocResult", useDecimalDataAnnotationForSprocResult)
        put("UseFluentApiOnly", useFluentApiOnly)
        put("UseHandleBars", useHandleBars)
        put("UseHierarchyId", useHierarchyId)
        put("UseInflector", useInflector)
        put("UseInternalAccessModifiersForSprocsAndFunctions", useInternalAccessModifiersForSprocsAndFunctions)
        put("UseLegacyPluralizer", useLegacyPluralizer)
        put("UseManyToManyEntity", useManyToManyEntity)
        put("UseNoDefaultConstructor", useNoDefaultConstructor)
        put("UseNoNavigations", useNoNavigations)
        put("UseNoObjectFilter", useNoObjectFilter)
        put("UseNodaTime", useNodaTime)
        put("UseNullableReferences", useNullableReferences)
        put("UsePrefixNavigationNaming", usePrefixNavigationNaming)
        put("UseSchemaFolders", useSchemaFolders)
        put("UseSchemaNamespaces", useSchemaNamespaces)
        put("UseSpatial", useSpatial)
        put("UseT4", useT4)
        put("UseT4Split", useT4Split)
        put("UseTypedTvpParameters", useTypedTvpParameters)
    }

    /** ReverseEngineerOptionsExtensions.Write: clears UiHint (it lives in the .user file) and serializes. */
    fun write(): String {
        uiHint = null
        return DcJson.write(toJson())
    }

    companion object {
        /** Deserializes with DataContractJsonSerializer semantics: absent members get the CLR default. */
        fun fromJson(o: JsonObject) = ReverseEngineerOptions().apply {
            codeGenerationMode = CodeGenerationMode.fromValue(o.int("CodeGenerationMode"))
            contextClassName = o.str("ContextClassName")
            contextNamespace = o.str("ContextNamespace")
            filterSchemas = o.bool("FilterSchemas")
            includeConnectionString = o.bool("IncludeConnectionString")
            irregularWords = o.objects("IrregularWords", IrregularWord::fromJson)
            minimumProductVersion = o.str("MinimumProductVersion")
            modelNamespace = o.str("ModelNamespace")
            outputContextPath = o.str("OutputContextPath")
            outputPath = o.str("OutputPath")
            pluralRules = o.objects("PluralRules", ReplacementRule::fromJson)
            preserveCasingWithRegex = o.bool("PreserveCasingWithRegex")
            projectRootNamespace = o.str("ProjectRootNamespace")
            schemas = o.objects("Schemas", SchemaInfo::fromJson)?.toMutableList()
            selectedHandlebarsLanguage = o.int("SelectedHandlebarsLanguage")
            selectedToBeGenerated = o.int("SelectedToBeGenerated")
            singularRules = o.objects("SingularRules", ReplacementRule::fromJson)
            t4TemplatePath = o.str("T4TemplatePath")
            tables = o.objects("Tables", SerializationTableModel::fromJson)?.toMutableList()
            uiHint = o.str("UiHint")
            uncountableWords = o.strings("UncountableWords")
            useAsyncStoredProcedureCalls = o.bool("UseAsyncStoredProcedureCalls")
            useBoolPropertiesWithoutDefaultSql = o.bool("UseBoolPropertiesWithoutDefaultSql")
            useDatabaseNames = o.bool("UseDatabaseNames")
            useDatabaseNamesForRoutines = o.bool("UseDatabaseNamesForRoutines")
            useDateOnlyTimeOnly = o.bool("UseDateOnlyTimeOnly")
            useDbContextSplitting = o.bool("UseDbContextSplitting")
            useDecimalDataAnnotationForSprocResult = o.bool("UseDecimalDataAnnotationForSprocResult")
            useFluentApiOnly = o.bool("UseFluentApiOnly")
            useHandleBars = o.bool("UseHandleBars")
            useHierarchyId = o.bool("UseHierarchyId")
            useInflector = o.bool("UseInflector")
            useInternalAccessModifiersForSprocsAndFunctions = o.bool("UseInternalAccessModifiersForSprocsAndFunctions")
            useLegacyPluralizer = o.bool("UseLegacyPluralizer")
            useManyToManyEntity = o.bool("UseManyToManyEntity")
            useNoDefaultConstructor = o.bool("UseNoDefaultConstructor")
            useNoNavigations = o.bool("UseNoNavigations")
            useNoObjectFilter = o.bool("UseNoObjectFilter")
            useNodaTime = o.bool("UseNodaTime")
            useNullableReferences = o.bool("UseNullableReferences")
            usePrefixNavigationNaming = o.bool("UsePrefixNavigationNaming")
            useSchemaFolders = o.bool("UseSchemaFolders")
            useSchemaNamespaces = o.bool("UseSchemaNamespaces")
            useSpatial = o.bool("UseSpatial")
            useT4 = o.bool("UseT4")
            useT4Split = o.bool("UseT4Split")
            useTypedTvpParameters = o.bool("UseTypedTvpParameters")
        }
    }
}

/** Mirrors ReverseEngineerUserOptions (efpt.config.json.user). */
class ReverseEngineerUserOptions(var uiHint: String? = null) : JsonWritable {
    override fun toJson() = JsonObject().apply { put("UiHint", uiHint) }

    companion object {
        fun fromJson(o: JsonObject) = ReverseEngineerUserOptions(o.str("UiHint"))
    }
}

/** Mirrors RevEng.Common.ReverseEngineerCommandOptions - the JSON handed to efreveng for code generation. */
class ReverseEngineerCommandOptions : JsonWritable {
    var databaseType: DatabaseType = DatabaseType.Undefined
    var connectionString: String? = null
    var projectPath: String? = null
    var outputPath: String? = null
    var optionsPath: String? = null
    var outputContextPath: String? = null
    var useSchemaFolders: Boolean = false
    var useSchemaNamespaces: Boolean = false
    var modelNamespace: String? = null
    var contextNamespace: String? = null
    var projectRootNamespace: String? = null
    var useFluentApiOnly: Boolean = false
    var contextClassName: String? = null
    var tables: List<SerializationTableModel>? = null
    var useDatabaseNames: Boolean = false
    var useInflector: Boolean = false
    var useHandleBars: Boolean = false
    var useT4: Boolean = false
    var t4TemplatePath: String? = null
    var useT4Split: Boolean = false
    var selectedHandlebarsLanguage: Int = 0
    var includeConnectionString: Boolean = false
    var selectedToBeGenerated: Int = 0
    var dacpac: String? = null
    var customReplacers: List<Schema>? = null
    var defaultDacpacSchema: String? = null
    var useLegacyPluralizer: Boolean = false
    var uncountableWords: List<String>? = null
    var irregularWords: List<IrregularWord>? = null
    var pluralRules: List<ReplacementRule>? = null
    var singularRules: List<ReplacementRule>? = null
    var useSpatial: Boolean = false
    var useHierarchyId: Boolean = false
    var useDbContextSplitting: Boolean = false
    var useNodaTime: Boolean = false
    var useBoolPropertiesWithoutDefaultSql: Boolean = false
    var useNullableReferences: Boolean = false
    var useNoObjectFilter: Boolean = false
    var useNoNavigations: Boolean = false
    var useNoDefaultConstructor: Boolean = false
    var filterSchemas: Boolean = false
    var schemas: List<SchemaInfo>? = null
    var runCleanup: Boolean = false
    var useManyToManyEntity: Boolean = false
    var useMultipleSprocResultSets: Boolean = false
    var mergeDacpacs: Boolean = false
    var useLegacyResultSetDiscovery: Boolean = false
    var useStoredProcedureResultSetFallback: Boolean = true
    var useAsyncCalls: Boolean = false
    var useDecimalDataAnnotation: Boolean = false
    var preserveCasingWithRegex: Boolean = false
    var useDateOnlyTimeOnly: Boolean = false
    var usePrefixNavigationNaming: Boolean = false
    var useDatabaseNamesForRoutines: Boolean = false
    var useInternalAccessModifiersForSprocsAndFunctions: Boolean = false
    var useTypedTvpParameters: Boolean = false
    var fileLineEndingStyle: String? = "native"
    var fileCharset: String? = "utf-8-bom"

    override fun toJson() = JsonObject().apply {
        put("ConnectionString", connectionString)
        put("ContextClassName", contextClassName)
        put("ContextNamespace", contextNamespace)
        put("CustomReplacers", customReplacers)
        put("Dacpac", dacpac)
        put("DatabaseType", databaseType.value)
        put("DefaultDacpacSchema", defaultDacpacSchema)
        put("FileCharset", fileCharset)
        put("FileLineEndingStyle", fileLineEndingStyle)
        put("FilterSchemas", filterSchemas)
        put("IncludeConnectionString", includeConnectionString)
        put("IrregularWords", irregularWords)
        put("MergeDacpacs", mergeDacpacs)
        put("ModelNamespace", modelNamespace)
        put("OptionsPath", optionsPath)
        put("OutputContextPath", outputContextPath)
        put("OutputPath", outputPath)
        put("PluralRules", pluralRules)
        put("PreserveCasingWithRegex", preserveCasingWithRegex)
        put("ProjectPath", projectPath)
        put("ProjectRootNamespace", projectRootNamespace)
        put("RunCleanup", runCleanup)
        put("Schemas", schemas)
        put("SelectedHandlebarsLanguage", selectedHandlebarsLanguage)
        put("SelectedToBeGenerated", selectedToBeGenerated)
        put("SingularRules", singularRules)
        put("T4TemplatePath", t4TemplatePath)
        put("Tables", tables)
        put("UncountableWords", uncountableWords)
        put("UseAsyncCalls", useAsyncCalls)
        put("UseBoolPropertiesWithoutDefaultSql", useBoolPropertiesWithoutDefaultSql)
        put("UseDatabaseNames", useDatabaseNames)
        put("UseDatabaseNamesForRoutines", useDatabaseNamesForRoutines)
        put("UseDateOnlyTimeOnly", useDateOnlyTimeOnly)
        put("UseDbContextSplitting", useDbContextSplitting)
        put("UseDecimalDataAnnotation", useDecimalDataAnnotation)
        put("UseFluentApiOnly", useFluentApiOnly)
        put("UseHandleBars", useHandleBars)
        put("UseHierarchyId", useHierarchyId)
        put("UseInflector", useInflector)
        put("UseInternalAccessModifiersForSprocsAndFunctions", useInternalAccessModifiersForSprocsAndFunctions)
        put("UseLegacyPluralizer", useLegacyPluralizer)
        put("UseLegacyResultSetDiscovery", useLegacyResultSetDiscovery)
        put("UseManyToManyEntity", useManyToManyEntity)
        put("UseMultipleSprocResultSets", useMultipleSprocResultSets)
        put("UseNoDefaultConstructor", useNoDefaultConstructor)
        put("UseNoNavigations", useNoNavigations)
        put("UseNoObjectFilter", useNoObjectFilter)
        put("UseNodaTime", useNodaTime)
        put("UseNullableReferences", useNullableReferences)
        put("UsePrefixNavigationNaming", usePrefixNavigationNaming)
        put("UseSchemaFolders", useSchemaFolders)
        put("UseSchemaNamespaces", useSchemaNamespaces)
        put("UseSpatial", useSpatial)
        put("UseStoredProcedureResultSetFallback", useStoredProcedureResultSetFallback)
        put("UseT4", useT4)
        put("UseT4Split", useT4Split)
        put("UseTypedTvpParameters", useTypedTvpParameters)
    }

    fun write(): String = DcJson.write(toJson())
}

/** Mirrors RevEng.Common.ReverseEngineerResult. */
class ReverseEngineerResult(
    val entityTypeFilePaths: List<String> = emptyList(),
    val contextFilePath: String? = null,
    val contextConfigurationFilePaths: List<String> = emptyList(),
    val entityErrors: List<String> = emptyList(),
    val entityWarnings: List<String> = emptyList(),
    val databaseEdition: Int = 0,
    val databaseEditionId: Long = 0,
    val databaseVersion: Int = 0,
    val databaseLevel: Int = 0,
) {
    val hasIssues: Boolean get() = entityErrors.isNotEmpty() || entityWarnings.isNotEmpty()

    companion object {
        fun fromJson(o: JsonObject) = ReverseEngineerResult(
            o.strings("EntityTypeFilePaths") ?: emptyList(),
            o.str("ContextFilePath"),
            o.strings("ContextConfigurationFilePaths") ?: emptyList(),
            o.strings("EntityErrors") ?: emptyList(),
            o.strings("EntityWarnings") ?: emptyList(),
            o.int("DatabaseEdition"),
            o.long("DatabaseEditionId"),
            o.int("DatabaseVersion"),
            o.int("DatabaseLevel"),
        )
    }
}

/** Mirrors RevEng.Common.NuGetPackage. */
class NuGetPackage(
    val packageId: String,
    val version: String,
    val databaseTypes: List<DatabaseType>,
    val isMainProviderPackage: Boolean = false,
    val useMethodName: String? = null,
) {
    var installed: Boolean = false
}

/** Mirrors RevEng.Common.Dab.DataApiBuilderOptions (efpt.dab.json style options for dabbuilder). */
class DataApiBuilderOptions : JsonWritable {
    var databaseType: DatabaseType = DatabaseType.Undefined
    var connectionString: String? = null // [IgnoreDataMember]
    var projectPath: String? = null
    var tables: MutableList<SerializationTableModel>? = null
    var dacpac: String? = null
    var connectionStringName: String? = "dab-connection-string"
    var mergeDacpacs: Boolean = false
    var optional: Boolean = false

    override fun toJson() = JsonObject().apply {
        put("ConnectionStringName", connectionStringName)
        put("Dacpac", dacpac)
        put("DatabaseType", databaseType.value)
        put("MergeDacpacs", mergeDacpacs)
        put("Optional", optional)
        put("ProjectPath", projectPath)
        put("Tables", tables)
    }

    fun write(): String = DcJson.write(toJson())

    companion object {
        fun fromJson(o: JsonObject) = DataApiBuilderOptions().apply {
            connectionStringName = o.str("ConnectionStringName")
            dacpac = o.str("Dacpac")
            databaseType = DatabaseType.fromValue(o.int("DatabaseType"))
            mergeDacpacs = o.bool("MergeDacpacs")
            optional = o.bool("Optional")
            projectPath = o.str("ProjectPath")
            tables = o.objects("Tables", SerializationTableModel::fromJson)?.toMutableList()
        }
    }
}

/** A database connection or database definition (SQL project / .dacpac) offered to the user. Mirrors DatabaseConnectionModel. */
data class DatabaseConnectionModel(
    var connectionName: String? = null,
    var connectionString: String? = null,
    var databaseType: DatabaseType = DatabaseType.Undefined,
    var filePath: String? = null,
    /** Where this connection came from (plugin store, Rider data source, ...). */
    var origin: ConnectionOrigin = ConnectionOrigin.Stored,
) {
    val displayName: String
        get() {
            if (databaseType == DatabaseType.SQLServerDacpac) {
                val fp = filePath ?: return "<null>"
                val name = fp.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
                return when {
                    fp.endsWith(".sqlproj", true) || fp.endsWith(".csproj", true) -> "$name (SQL Project)"
                    fp.endsWith(".dacpac", true) -> "$name (.dacpac)"
                    else -> fp
                }
            }
            return if (origin == ConnectionOrigin.RiderDataSource) "$connectionName (Rider data source)" else "$connectionName ($databaseType)"
        }

    val toolTip: String get() = if (databaseType == DatabaseType.SQLServerDacpac) (filePath ?: "<null>") else (connectionString ?: "")

    override fun toString() = displayName
}

enum class ConnectionOrigin { Stored, RiderDataSource, Definition }
