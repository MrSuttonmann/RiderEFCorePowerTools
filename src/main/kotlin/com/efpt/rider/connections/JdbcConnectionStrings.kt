package com.efpt.rider.connections

import com.efpt.rider.model.DatabaseType
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Converts the JDBC URL + credentials of a Rider data source into an ADO.NET connection string for
 * the provider used by EF Core Power Tools.
 *
 * Rider: Visual Studio's Server Explorer stores ADO.NET connection strings directly; Rider data sources
 * are JDBC based, so the connection string is rebuilt here (best effort - unsupported URL shapes or
 * drivers map to [DatabaseType.Undefined]).
 */
object JdbcConnectionStrings {

    /** Cheap provider detection from the JDBC URL (used from AnAction.update). */
    fun databaseType(url: String?): DatabaseType {
        val u = url?.trim()?.lowercase() ?: return DatabaseType.Undefined
        return when {
            u.startsWith("jdbc:sqlserver:") || u.startsWith("jdbc:jtds:sqlserver:") -> DatabaseType.SQLServer
            u.startsWith("jdbc:postgresql:") -> DatabaseType.Npgsql
            u.startsWith("jdbc:mysql:") || u.startsWith("jdbc:mariadb:") -> DatabaseType.Mysql
            u.startsWith("jdbc:sqlite:") -> DatabaseType.SQLite
            u.startsWith("jdbc:oracle:thin:") -> DatabaseType.Oracle
            u.startsWith("jdbc:firebirdsql:") || u.startsWith("jdbc:firebird:") -> DatabaseType.Firebird
            u.startsWith("jdbc:snowflake:") -> DatabaseType.Snowflake
            else -> DatabaseType.Undefined
        }
    }

    /**
     * Builds the ADO.NET connection string; returns null when the URL cannot be converted.
     * [properties] are additional JDBC driver properties configured on the data source.
     */
    fun toConnectionString(url: String, user: String?, password: String?, properties: Map<String, String> = emptyMap()): String? =
        try {
            when (databaseType(url)) {
                DatabaseType.SQLServer -> sqlServer(url.trim(), user, password, properties)
                DatabaseType.Npgsql -> postgres(url.trim(), user, password, properties)
                DatabaseType.Mysql -> mysql(url.trim(), user, password, properties)
                DatabaseType.SQLite -> sqlite(url.trim())
                DatabaseType.Oracle -> oracle(url.trim(), user, password)
                DatabaseType.Firebird -> firebird(url.trim(), user, password, properties)
                DatabaseType.Snowflake -> snowflake(url.trim(), user, password, properties)
                else -> null
            }
        } catch (_: Exception) {
            null
        }

    // ------------------------------------------------------------------ SQL Server

    private fun sqlServer(url: String, user: String?, password: String?, extra: Map<String, String>): String? {
        val jtds = url.startsWith("jdbc:jtds:", true)
        val rest = url.substringAfter(if (jtds) "sqlserver:" else "jdbc:sqlserver:").removePrefix("//")
        // jdbc:sqlserver://host[\instance][:port][;prop=value]*
        // jdbc:jtds:sqlserver://host[:port][/database][;prop=value]*
        val parts = rest.split(';')
        var hostPart = parts[0]
        val props = ci(extra)
        parts.drop(1).filter { it.contains('=') }.forEach { props[it.substringBefore('=').trim()] = it.substringAfter('=').trim() }

        var database: String? = null
        if (jtds && hostPart.contains('/')) {
            database = hostPart.substringAfter('/')
            hostPart = hostPart.substringBefore('/')
        }
        var host = hostPart
        var port: String? = null
        var instance: String? = null
        if (host.contains('\\')) {
            instance = host.substringAfter('\\')
            host = host.substringBefore('\\')
        }
        if (instance?.contains(':') == true) {
            port = instance.substringAfter(':')
            instance = instance.substringBefore(':')
        } else if (host.contains(':') && !host.startsWith("[")) {
            port = host.substringAfter(':')
            host = host.substringBefore(':')
        }
        props["serverName"]?.let { if (host.isEmpty()) host = it }
        props["instanceName"]?.let { instance = it }
        props["instance"]?.let { instance = it }
        props["portNumber"]?.let { port = it }
        props["port"]?.let { port = it }
        database = props["databaseName"] ?: props["database"] ?: database

        val dataSource = buildString {
            val localDb = instance?.startsWith("LOCALDB", true) == true || host.startsWith("(localdb)", true)
            if (localDb) {
                // Rider: LocalDB data sources use jTDS named pipes; ADO.NET uses the (localdb) syntax
                append(if (host.startsWith("(localdb)", true)) host else "(localdb)\\MSSQLLocalDB")
                return@buildString
            }
            append(host.ifEmpty { "." })
            instance?.takeIf { it.isNotEmpty() }?.let { append('\\').append(it) }
            port?.takeIf { it.isNotEmpty() && it != "1433" }?.let { append(',').append(it) }
        }

        val sb = Builder()
        sb.add("Data Source", dataSource)
        database?.takeIf { it.isNotEmpty() }?.let { sb.add("Initial Catalog", it) }
        val u = user?.takeIf { it.isNotEmpty() } ?: props["user"]
        val p = password ?: props["password"]
        val integrated = props["integratedSecurity"].equals("true", true) || props["authenticationScheme"] != null && u.isNullOrEmpty()
        when {
            !props["authentication"].isNullOrEmpty() && props["authentication"] != "NotSpecified" -> {
                sb.add("Authentication", mapAuthentication(props["authentication"]!!))
                u?.let { sb.add("User ID", it) }
                if (!p.isNullOrEmpty()) sb.add("Password", p)
            }
            !u.isNullOrEmpty() && !integrated -> {
                sb.add("User ID", u)
                sb.add("Password", p ?: "")
            }
            else -> sb.add("Integrated Security", "True")
        }
        props["encrypt"]?.let {
            sb.add(
                "Encrypt",
                when (it.lowercase()) {
                    "true", "mandatory" -> "True"
                    "strict" -> "Strict"
                    else -> "False"
                },
            )
        }
        props["ssl"]?.let { if (jtds) sb.add("Encrypt", if (it.equals("off", true)) "False" else "True") }
        if (props["trustServerCertificate"].equals("true", true)) sb.add("TrustServerCertificate", "True")
        props["applicationIntent"]?.let { sb.add("Application Intent", it) }
        props["multiSubnetFailover"]?.let { sb.add("Multi Subnet Failover", it) }
        return sb.toString()
    }

    private fun mapAuthentication(jdbc: String): String = when (jdbc.lowercase()) {
        "sqlpassword" -> "SqlPassword"
        "activedirectorypassword" -> "Active Directory Password"
        "activedirectoryintegrated" -> "Active Directory Integrated"
        "activedirectoryinteractive" -> "Active Directory Interactive"
        "activedirectoryserviceprincipal" -> "Active Directory Service Principal"
        "activedirectorymanagedidentity", "activedirectorymsi" -> "Active Directory Managed Identity"
        "activedirectorydefault" -> "Active Directory Default"
        else -> jdbc
    }

    // ------------------------------------------------------------------ PostgreSQL

    private fun postgres(url: String, user: String?, password: String?, extra: Map<String, String>): String {
        // jdbc:postgresql://host1[:port1][,host2[:port2]]/database?prop=value  |  jdbc:postgresql:database  |  jdbc:postgresql:/
        val rest = url.substringAfter("jdbc:postgresql:")
        val query = queryParams(rest)
        val props = ci(extra).also { it.putAll(query) }
        val path = rest.substringBefore('?')
        var hosts = "localhost"
        var port: String? = null
        val database: String
        if (path.startsWith("//")) {
            val authority = path.removePrefix("//").substringBefore('/')
            database = decode(path.removePrefix("//").substringAfter('/', ""))
            val hostList = authority.split(',').filter { it.isNotEmpty() }
            if (hostList.isNotEmpty()) {
                val parsed = hostList.map { splitHostPort(it) }
                hosts = parsed.joinToString(",") { it.first }
                port = parsed.mapNotNull { it.second }.distinct().singleOrNull()
                if (parsed.size > 1 && parsed.any { it.second != null }) {
                    hosts = parsed.joinToString(",") { if (it.second != null) "${it.first}:${it.second}" else it.first }
                    port = null
                }
            }
        } else {
            database = decode(path.removePrefix("/"))
        }
        val sb = Builder()
        sb.add("Host", hosts)
        port?.let { sb.add("Port", it) }
        if (database.isNotEmpty()) sb.add("Database", database)
        (user?.takeIf { it.isNotEmpty() } ?: props["user"])?.let { sb.add("Username", it) }
        (password ?: props["password"])?.let { sb.add("Password", it) }
        props["sslmode"]?.let {
            sb.add(
                "SSL Mode",
                when (it.lowercase()) {
                    "disable" -> "Disable"
                    "allow" -> "Allow"
                    "prefer" -> "Prefer"
                    "require" -> "Require"
                    "verify-ca" -> "VerifyCA"
                    "verify-full" -> "VerifyFull"
                    else -> it
                },
            )
        }
        if (props["sslmode"] == null && props["ssl"].equals("true", true)) sb.add("SSL Mode", "Require")
        props["currentSchema"]?.let { sb.add("Search Path", it) }
        return sb.toString()
    }

    // ------------------------------------------------------------------ MySQL / MariaDB

    private fun mysql(url: String, user: String?, password: String?, extra: Map<String, String>): String {
        // jdbc:mysql://host[:port][,host2]/database?prop=value  (also jdbc:mariadb:, jdbc:mysql:loadbalance:// ...)
        val rest = url.substringAfter("//", "")
        val query = queryParams(rest)
        val props = ci(extra).also { it.putAll(query) }
        val path = rest.substringBefore('?')
        val authority = path.substringBefore('/')
        val database = decode(path.substringAfter('/', ""))
        val hostList = authority.split(',').filter { it.isNotEmpty() }.map { splitHostPort(it.substringAfter('@')) }
        val sb = Builder()
        sb.add("Server", hostList.joinToString(",") { it.first }.ifEmpty { "localhost" })
        hostList.mapNotNull { it.second }.distinct().singleOrNull()?.let { if (it != "3306") sb.add("Port", it) }
        if (database.isNotEmpty()) sb.add("Database", database)
        (user?.takeIf { it.isNotEmpty() } ?: props["user"])?.let { sb.add("Uid", it) }
        (password ?: props["password"])?.let { sb.add("Pwd", it) }
        val sslMode = props["sslMode"] ?: props["sslmode"]
        when {
            sslMode != null -> sb.add(
                "SslMode",
                when (sslMode.uppercase()) {
                    "DISABLED", "DISABLE" -> "None"
                    "PREFERRED", "PREFER" -> "Preferred"
                    "REQUIRED", "REQUIRE", "TRUST" -> "Required"
                    "VERIFY_CA", "VERIFY-CA" -> "VerifyCA"
                    "VERIFY_IDENTITY", "VERIFY-FULL", "VERIFY_FULL" -> "VerifyFull"
                    else -> sslMode
                },
            )
            props["useSSL"].equals("false", true) -> sb.add("SslMode", "None")
            props["useSSL"].equals("true", true) || props["useSsl"].equals("true", true) -> sb.add("SslMode", "Required")
        }
        if (props["allowPublicKeyRetrieval"].equals("true", true)) sb.add("AllowPublicKeyRetrieval", "True")
        return sb.toString()
    }

    // ------------------------------------------------------------------ SQLite

    private fun sqlite(url: String): String? {
        var path = url.substringAfter("jdbc:sqlite:").substringBefore('?')
        if (path.isEmpty() || path.startsWith(":memory:")) return null
        path = path.removePrefix("file:")
        // jdbc:sqlite:/C:/folder/file.db on Windows
        if (Regex("^/[A-Za-z]:[/\\\\].*").matches(path)) path = path.substring(1)
        return Builder().add("Data Source", path).toString()
    }

    // ------------------------------------------------------------------ Oracle

    private fun oracle(url: String, user: String?, password: String?): String {
        // jdbc:oracle:thin:[user/password]@host:port:SID | @//host:port/service | @(DESCRIPTION=...) | @tns_alias
        var target = url.substringAfter("jdbc:oracle:thin:")
        var u = user
        var p = password
        if (!target.startsWith("@") && target.contains('@')) {
            val creds = target.substringBefore('@')
            if (u.isNullOrEmpty()) u = creds.substringBefore('/')
            if (p.isNullOrEmpty() && creds.contains('/')) p = creds.substringAfter('/')
            target = target.substringAfter('@')
        } else {
            target = target.removePrefix("@")
        }
        target = target.substringBefore('?')
        val dataSource = when {
            target.startsWith("(") -> target
            target.startsWith("//") -> target.removePrefix("//")
            Regex("^[^:/]+:\\d+:[^:/]+$").matches(target) -> {
                // host:port:SID
                val (host, port, sid) = target.split(':')
                "(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=$host)(PORT=$port))(CONNECT_DATA=(SID=$sid)))"
            }
            else -> target
        }
        val sb = Builder()
        u?.takeIf { it.isNotEmpty() }?.let { sb.add("User Id", it) }
        p?.let { sb.add("Password", it) }
        sb.add("Data Source", dataSource)
        return sb.toString()
    }

    // ------------------------------------------------------------------ Firebird

    private fun firebird(url: String, user: String?, password: String?, extra: Map<String, String>): String {
        // jdbc:firebirdsql://host[:port]/path?prop=value | jdbc:firebirdsql:host[/port]:path | jdbc:firebirdsql:embedded:path | jdbc:firebird:...
        var rest = url.substringAfter(':').substringAfter(':') // drop "jdbc:firebirdsql:" / "jdbc:firebird:"
        val query = queryParams(rest)
        val props = ci(extra).also { it.putAll(query) }
        rest = rest.substringBefore('?')
        val sb = Builder()
        var serverType: String? = null
        var host: String? = null
        var port: String? = null
        val database: String
        when {
            rest.startsWith("embedded:", true) -> {
                serverType = "1"
                database = rest.substringAfter(':')
            }
            rest.startsWith("native:", true) || rest.startsWith("local:", true) -> {
                rest = rest.substringAfter(':')
                if (rest.startsWith("//")) {
                    val authority = rest.removePrefix("//").substringBefore('/')
                    splitHostPort(authority).let { host = it.first; port = it.second }
                    database = rest.removePrefix("//").substringAfter('/')
                } else {
                    database = rest
                }
            }
            rest.startsWith("//") -> {
                val authority = rest.removePrefix("//").substringBefore('/')
                splitHostPort(authority).let { host = it.first; port = it.second }
                database = rest.removePrefix("//").substringAfter('/')
            }
            Regex("^[^:/\\\\]+(/\\d+)?:.+").matches(rest) && !Regex("^[A-Za-z]:[/\\\\].*").matches(rest) -> {
                // host[/port]:path
                val authority = rest.substringBefore(':')
                host = authority.substringBefore('/')
                port = authority.substringAfter('/', "").takeIf { it.isNotEmpty() }
                database = rest.substringAfter(':')
            }
            else -> database = rest
        }
        if (serverType == null) sb.add("DataSource", host ?: "localhost")
        port?.let { if (it != "3050") sb.add("Port", it) }
        sb.add("Database", decode(database))
        (user?.takeIf { it.isNotEmpty() } ?: props["user"] ?: props["user_name"])?.let { sb.add("User", it) }
        (password ?: props["password"])?.let { sb.add("Password", it) }
        (props["charSet"] ?: props["encoding"] ?: props["lc_ctype"])?.let { sb.add("Charset", it) }
        props["roleName"]?.let { sb.add("Role", it) }
        serverType?.let { sb.add("ServerType", it) }
        return sb.toString()
    }

    // ------------------------------------------------------------------ Snowflake

    private fun snowflake(url: String, user: String?, password: String?, extra: Map<String, String>): String {
        // jdbc:snowflake://<account_identifier>.snowflakecomputing.com/?db=...&schema=...&warehouse=...&role=...
        val rest = url.substringAfter("jdbc:snowflake://")
        val query = queryParams(rest)
        val props = ci(extra).also { it.putAll(query) }
        val host = rest.substringBefore('/').substringBefore('?').substringBefore(':')
        val account = props["account"] ?: host.substringBefore(".snowflakecomputing.com").substringBefore('.')
        val sb = Builder()
        sb.add("ACCOUNT", account)
        sb.add("HOST", host)
        (props["db"] ?: props["database"])?.let { sb.add("DB", it) }
        props["schema"]?.let { sb.add("SCHEMA", it) }
        props["warehouse"]?.let { sb.add("WAREHOUSE", it) }
        props["role"]?.let { sb.add("ROLE", it) }
        (user?.takeIf { it.isNotEmpty() } ?: props["user"])?.let { sb.add("USER", it) }
        (password ?: props["password"])?.let { sb.add("PASSWORD", it) }
        props["authenticator"]?.let { sb.add("AUTHENTICATOR", it) }
        return sb.toString()
    }

    // ------------------------------------------------------------------ helpers

    private fun ci(source: Map<String, String>) = sortedMapOf<String, String>(String.CASE_INSENSITIVE_ORDER).also { it.putAll(source) }

    private fun queryParams(rest: String): Map<String, String> {
        val query = rest.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&', ';').filter { it.contains('=') }
            .associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=')) }
    }

    private fun splitHostPort(authority: String): Pair<String, String?> {
        if (authority.startsWith("[")) {
            val host = authority.substringBefore(']') + "]"
            val port = authority.substringAfter("]:", "").takeIf { it.isNotEmpty() }
            return host to port
        }
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "").takeIf { it.isNotEmpty() }
        return host to port
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8)
    } catch (_: Exception) {
        s
    }

    /** ADO.NET connection string builder with DbConnectionStringBuilder quoting rules. */
    private class Builder {
        private val sb = StringBuilder()

        fun add(key: String, value: String): Builder {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(key).append('=').append(quote(value))
            return this
        }

        override fun toString() = sb.toString()

        private fun quote(value: String): String {
            val needsQuoting = value.contains(';') || value.contains('\'') || value.contains('"') ||
                value != value.trim() || value.startsWith("=")
            if (!needsQuoting) return value
            return if (!value.contains('"')) "\"$value\"" else "'" + value.replace("'", "''") + "'"
        }
    }
}
