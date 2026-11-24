package com.efpt.rider.model

/** Mirrors RevEng.Common.DatabaseType - numeric values are persisted in efpt.config.json and passed to efreveng. */
enum class DatabaseType(val value: Int, val displayName: String) {
    Undefined(0, "Undefined"),
    SQLServer(3, "SQL Server"),
    SQLite(4, "SQLite"),
    Npgsql(5, "PostgreSQL"),
    Mysql(6, "MySQL"),
    Oracle(7, "Oracle"),
    SQLServerDacpac(8, "SQL Server .dacpac"),
    Firebird(10, "Firebird"),
    Snowflake(11, "Snowflake");

    companion object {
        fun fromValue(value: Int): DatabaseType = entries.firstOrNull { it.value == value } ?: Undefined
    }
}

/**
 * Mirrors RevEng.Common.CodeGenerationMode. Values 0-5 must not be reused: 4 and 5 were EF Core 8 and 9,
 * which upstream removed (ErikEJ/EFCorePowerTools#3562).
 */
enum class CodeGenerationMode(val value: Int, val displayName: String) {
    EFCore10(6, "EF Core 10"),
    EFCore11(7, "EF Core 11");

    companion object {
        /**
         * Persisted EF Core 8/9 modes (4, 5) are migrated to EF Core 10, like the ReverseEngineerOptions
         * setter upstream. Unknown or missing values also fall back to EF Core 10.
         */
        fun fromValue(value: Int): CodeGenerationMode = entries.firstOrNull { it.value == value } ?: EFCore10
    }
}

/** Mirrors RevEng.Common.ObjectType. */
enum class ObjectType(val value: Int) {
    Table(0),
    Procedure(1),
    ScalarFunction(2),
    View(3);

    val hasColumns: Boolean get() = this == View || this == Table

    companion object {
        fun fromValue(value: Int): ObjectType = entries.firstOrNull { it.value == value } ?: Table
    }
}

/** Mirrors EFCorePowerTools.Handlers.GenerationType (efpt model analysis). */
enum class GenerationType {
    Dgml,
    Ddl,
    DebugView,
    DbContextList,
    DbContextCompare,
}
