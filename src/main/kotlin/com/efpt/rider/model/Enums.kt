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

/** Mirrors RevEng.Common.CodeGenerationMode. */
enum class CodeGenerationMode(val value: Int, val displayName: String, val revEngVersion: String) {
    EFCore8(4, "EF Core 8", "8"),
    EFCore9(5, "EF Core 9", "9"),
    EFCore10(6, "EF Core 10", "10");

    companion object {
        fun fromValue(value: Int): CodeGenerationMode = entries.firstOrNull { it.value == value } ?: EFCore8
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
