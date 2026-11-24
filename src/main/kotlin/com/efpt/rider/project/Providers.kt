package com.efpt.rider.project

import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.NuGetPackage
import com.efpt.rider.model.ObjectType
import com.efpt.rider.model.ReverseEngineerCommandOptions

/** Providers.ToDatabaseShortName extension (mssql, sqlite, npgsql, mysql, oracle, firebird, snowflake). */
fun DatabaseType.toDatabaseShortName(): String = Providers.toDatabaseShortName(this)

/** Providers.ToDatabaseType extension on the provider alias / package name. */
fun String.toDatabaseType(isDacpac: Boolean): DatabaseType = Providers.toDatabaseType(this, isDacpac)

/**
 * Port of RevEng.Common.Providers (src/GUI/RevEng.Shared/Providers.cs).
 * Package versions must be kept in sync with the C# original.
 */
object Providers {

    /** Providers.ToDatabaseType: provider package name or alias -> DatabaseType. */
    fun toDatabaseType(providerAlias: String, isDacpac: Boolean): DatabaseType {
        val provider = knownProviders[providerAlias] ?: return DatabaseType.Undefined
        return when (provider) {
            "Microsoft.EntityFrameworkCore.SqlServer" -> if (isDacpac) DatabaseType.SQLServerDacpac else DatabaseType.SQLServer
            "Microsoft.EntityFrameworkCore.Sqlite" -> DatabaseType.SQLite
            "Npgsql.EntityFrameworkCore.PostgreSQL" -> DatabaseType.Npgsql
            "Pomelo.EntityFrameworkCore.MySql" -> DatabaseType.Mysql
            "Microting.EntityFrameworkCore.MySql" -> DatabaseType.Mysql
            "Oracle.EntityFrameworkCore" -> DatabaseType.Oracle
            "FirebirdSql.EntityFrameworkCore.Firebird" -> DatabaseType.Firebird
            "EFCore.Snowflake" -> DatabaseType.Snowflake
            else -> DatabaseType.Undefined
        }
    }

    /** Providers.ToDatabaseShortName. */
    fun toDatabaseShortName(databaseType: DatabaseType): String = when (databaseType) {
        DatabaseType.Undefined -> "Undefined"
        DatabaseType.SQLServer -> "mssql"
        DatabaseType.SQLite -> "sqlite"
        DatabaseType.Npgsql -> "npgsql"
        DatabaseType.Mysql -> "mysql"
        DatabaseType.Oracle -> "oracle"
        DatabaseType.SQLServerDacpac -> "mssql"
        DatabaseType.Firebird -> "firebird"
        DatabaseType.Snowflake -> "snowflake"
    }

    /** DatabaseTypes supported by Data API Builder. */
    val dabProviders: Set<DatabaseType> = setOf(DatabaseType.SQLServer, DatabaseType.SQLServerDacpac, DatabaseType.Npgsql, DatabaseType.Mysql)

    /**
     * Providers.GetReadmeContent. The C# original reads "efcpt-readme.md" next to the assembly; here the
     * caller passes the template text.
     */
    fun getReadmeContent(
        commandOptions: ReverseEngineerCommandOptions,
        codeGenerationMode: CodeGenerationMode,
        redactedConnectionString: String,
        template: String,
    ): String {
        require(redactedConnectionString.isNotEmpty()) { "redactedConnectionString" }
        val packages = getNeededPackages(
            commandOptions.databaseType,
            commandOptions.useSpatial,
            commandOptions.useNodaTime,
            commandOptions.useHierarchyId,
            commandOptions.useMultipleSprocResultSets,
            commandOptions.tables?.any { it.objectType == ObjectType.Procedure } ?: false,
            codeGenerationMode,
        )
        return getReadMeText(commandOptions, template, packages, redactedConnectionString)
    }

    private fun unsupported(mode: CodeGenerationMode): Nothing =
        throw UnsupportedOperationException("No provider package for $mode")

    fun getNeededPackages(
        databaseType: DatabaseType,
        useSpatial: Boolean,
        useNodaTime: Boolean,
        useHierarchyId: Boolean,
        discoverMultipleResultSets: Boolean,
        hasProcedures: Boolean,
        codeGenerationMode: CodeGenerationMode,
    ): MutableList<NuGetPackage> {
        // Update versions here when adding provider and other updates
        val packages = mutableListOf<NuGetPackage>()

        if (databaseType == DatabaseType.SQLServer || databaseType == DatabaseType.SQLServerDacpac) {
            val sqlTypes = listOf(DatabaseType.SQLServer, DatabaseType.SQLServerDacpac)
            var pkgVersion = when (codeGenerationMode) {
                CodeGenerationMode.EFCore10 -> "10.0.15"
                CodeGenerationMode.EFCore11 -> "11.0.0"
            }
            packages += NuGetPackage("Microsoft.EntityFrameworkCore.SqlServer", pkgVersion, sqlTypes, true, "SqlServer")

            if (useSpatial) {
                packages += NuGetPackage("Microsoft.EntityFrameworkCore.SqlServer.NetTopologySuite", pkgVersion, sqlTypes, false, "NetTopologySuite")
            }

            if (useHierarchyId) {
                packages += NuGetPackage("Microsoft.EntityFrameworkCore.SqlServer.HierarchyId", pkgVersion, sqlTypes, false, "HierarchyId")
            }

            if (useNodaTime) {
                pkgVersion = when (codeGenerationMode) {
                    CodeGenerationMode.EFCore10 -> "10.0.1"
                    CodeGenerationMode.EFCore11 -> "11.0.1"
                }
                packages += NuGetPackage("SimplerSoftware.EntityFrameworkCore.SqlServer.NodaTime", pkgVersion, sqlTypes, false, "NodaTime")
            }

            if (hasProcedures && discoverMultipleResultSets) {
                packages += NuGetPackage("Dapper", "2.1.66", sqlTypes, false, null)
            }
        }

        if (databaseType == DatabaseType.SQLite) {
            var pkgVersion = when (codeGenerationMode) {
                CodeGenerationMode.EFCore10 -> "10.0.15"
                CodeGenerationMode.EFCore11 -> "11.0.0"
            }
            packages += NuGetPackage("Microsoft.EntityFrameworkCore.Sqlite", pkgVersion, listOf(databaseType), true, "Sqlite")

            if (useNodaTime) {
                pkgVersion = when (codeGenerationMode) {
                    CodeGenerationMode.EFCore10 -> "10.0.0"
                    else -> unsupported(codeGenerationMode)
                }
                packages += NuGetPackage("EntityFrameworkCore.Sqlite.NodaTime", pkgVersion, listOf(databaseType), false, "NodaTime")
            }
        }

        if (databaseType == DatabaseType.Npgsql) {
            val pkgVersion = when (codeGenerationMode) {
                CodeGenerationMode.EFCore10 -> "10.0.3"
                else -> unsupported(codeGenerationMode)
            }
            packages += NuGetPackage("Npgsql.EntityFrameworkCore.PostgreSQL", pkgVersion, listOf(databaseType), true, "Npgsql")

            if (useSpatial) {
                packages += NuGetPackage("Npgsql.EntityFrameworkCore.PostgreSQL.NetTopologySuite", pkgVersion, listOf(databaseType), false, "NetTopologySuite")
            }

            if (useNodaTime) {
                packages += NuGetPackage("Npgsql.EntityFrameworkCore.PostgreSQL.NodaTime", pkgVersion, listOf(databaseType), false, "NodaTime")
            }
        }

        if (databaseType == DatabaseType.Mysql) {
            val (prefix, pkgVersion) = when (codeGenerationMode) {
                CodeGenerationMode.EFCore10 -> "Microting" to "10.0.14"
                else -> unsupported(codeGenerationMode)
            }
            packages += NuGetPackage("$prefix.EntityFrameworkCore.MySql", pkgVersion, listOf(databaseType), true, "Mysql")

            if (useSpatial) {
                packages += NuGetPackage("$prefix.EntityFrameworkCore.MySql.NetTopologySuite", pkgVersion, listOf(databaseType), false, "NetTopologySuite")
            }
        }

        if (databaseType == DatabaseType.Oracle) {
            val pkgVersion = when (codeGenerationMode) {
                CodeGenerationMode.EFCore10 -> "10.23.26200"
                else -> unsupported(codeGenerationMode)
            }
            packages += NuGetPackage("Oracle.EntityFrameworkCore", pkgVersion, listOf(databaseType), true, "Oracle")
        }

        if (databaseType == DatabaseType.Firebird) {
            val pkgVersion = when (codeGenerationMode) {
                CodeGenerationMode.EFCore10 -> "13.0.0"
                else -> unsupported(codeGenerationMode)
            }
            packages += NuGetPackage("FirebirdSql.EntityFrameworkCore.Firebird", pkgVersion, listOf(databaseType), true, "Firebird")
        }

        // Snowflake: upstream has no EF Core 10 provider package yet (commented out in Providers.cs).

        return packages
    }

    private fun getReadMeText(
        options: ReverseEngineerCommandOptions,
        content: String,
        packages: List<NuGetPackage>,
        redactedConnectionString: String,
    ): String {
        val nl = System.lineSeparator()
        val extraPackages = packages.filter { !it.isMainProviderPackage && it.useMethodName != null }.map { "Use${it.useMethodName}()" }
        val useText = if (extraPackages.isNotEmpty()) ",$nl           x => x." + extraPackages.joinToString(".") else ""
        val packageText = StringBuilder()
        packages.forEach { packageText.append("  <PackageReference Include=\"${it.packageId}\" Version=\"${it.version}\" />").append(nl) }
        val mainPackage = packages.firstOrNull { it.isMainProviderPackage }
        return content.replace("[ProviderName]", mainPackage?.useMethodName ?: "")
            .replace("[ConnectionString]", redactedConnectionString.replace("\\", "\\\\"))
            .replace("[UseList]", useText)
            .replace("[PackageList]", packageText.toString())
            .replace("[ContextName]", options.contextClassName ?: "")
    }

    private val providersWithAliases: Map<String, List<String>> = linkedMapOf(
        "Microsoft.EntityFrameworkCore.SqlServer" to listOf("mssql", "sqlserver"),
        "Microsoft.EntityFrameworkCore.Sqlite" to listOf("sqlite"),
        "Npgsql.EntityFrameworkCore.PostgreSQL" to listOf("postgres", "postgresql"),
        "Pomelo.EntityFrameworkCore.MySql" to listOf("mysql"),
        "Oracle.EntityFrameworkCore" to listOf("oracle"),
        "FirebirdSql.EntityFrameworkCore.Firebird" to listOf("firebird"),
        "EFCore.Snowflake" to listOf("snowflake"),
    )

    private val knownProviders: Map<String, String> = buildMap {
        providersWithAliases.forEach { (key, aliases) ->
            put(key, key)
            aliases.forEach { put(it, key) }
        }
    }
}
