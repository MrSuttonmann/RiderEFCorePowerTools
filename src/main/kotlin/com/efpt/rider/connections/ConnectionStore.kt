package com.efpt.rider.connections

import com.efpt.rider.model.ConnectionOrigin
import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.model.DatabaseType
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Tag

/**
 * Saved database connections - the counterpart of Visual Studio's Server Explorer data
 * connections plus the EF Core Power Tools CredentialStore. Only names and provider types are
 * stored in the settings file; connection strings live in the IDE password safe.
 */
@Service(Service.Level.APP)
@State(name = "EFCorePowerToolsConnections", storages = [Storage("efCorePowerToolsConnections.xml")])
class ConnectionStore : SimplePersistentStateComponent<ConnectionStore.StoreState>(StoreState()) {

    class StoreState : BaseState() {
        var connections by list<SavedConnection>()
    }

    @Tag("connection")
    class SavedConnection : BaseState() {
        var name by string()
        var databaseType by property(0)
    }

    /** All saved connections with their connection strings resolved (call off EDT; the password safe may block). */
    fun getConnections(): List<DatabaseConnectionModel> = state.connections.mapNotNull { saved ->
        val name = saved.name ?: return@mapNotNull null
        DatabaseConnectionModel(
            connectionName = name,
            connectionString = PasswordSafe.instance.getPassword(attributes(name)) ?: "",
            databaseType = DatabaseType.fromValue(saved.databaseType),
            origin = ConnectionOrigin.Stored,
        )
    }

    /** Saves (or replaces) a connection; returns the saved name. Mirrors VsDataHelper.SaveDataConnectionAsync / CredentialStore.SaveCredential. */
    fun save(model: DatabaseConnectionModel): String {
        val name = model.connectionName?.takeIf { it.isNotBlank() }
            ?: savedConnectionName(model.connectionString ?: "", model.databaseType)
        // Assigning a new list (rather than mutating in place) marks the state as modified.
        state.connections = (state.connections.filter { it.name != name } +
            SavedConnection().apply { this.name = name; databaseType = model.databaseType.value }).toMutableList()
        PasswordSafe.instance.set(attributes(name), Credentials(name, model.connectionString ?: ""))
        model.connectionName = name
        return name
    }

    /** Names of the saved connections (no password safe access - cheap). */
    fun names(): List<String> = state.connections.mapNotNull { it.name }

    /**
     * CredentialStore.SaveCredential: like [save] but refuses duplicate names
     * ("Name already used: '...' - please pick another one").
     */
    fun saveNew(model: DatabaseConnectionModel): String {
        val name = model.connectionName
        if (name != null && names().any { it == name }) {
            throw IllegalStateException("Name already used: '$name' - please pick another one")
        }
        return save(model)
    }

    fun remove(name: String) {
        state.connections = state.connections.filter { it.name != name }.toMutableList()
        PasswordSafe.instance.set(attributes(name), null)
    }

    private fun attributes(name: String) = CredentialAttributes(generateServiceName("EF Core Power Tools", name))

    companion object {
        val instance: ConnectionStore get() = service()

        /** Port of VsDataHelper.GetSavedConnectionName. */
        fun savedConnectionName(connectionString: String, dbType: DatabaseType): String {
            val builder = ConnectionStringBuilder(connectionString)
            if (dbType == DatabaseType.SQLServer) {
                val server = builder["Data Source"] ?: builder["Server"] ?: builder["Address"] ?: ""
                val db = builder["Initial Catalog"] ?: builder["Database"] ?: ""
                return listOf(server.replace(".database.windows.net", ""), db).filter { it.isNotEmpty() }.joinToString(".")
                    .ifEmpty { "SQL Server" }
            }
            var result = ""
            builder["Data Source"]?.let { result += it.replace(".database.windows.net", "") }
            builder["DataSource"]?.let { result += it }
            builder["Host"]?.let { result += it }
            builder["Server"]?.let { result += it }
            builder["Database"]?.let { result += ".$it" }
            builder["Initial Catalog"]?.let { result += ".$it" }
            if (result.length > 1 && result.startsWith(".")) result = result.substring(1)
            return result.ifEmpty { dbType.displayName }
        }
    }
}

/**
 * Minimal ADO.NET connection string parser (DbConnectionStringBuilder semantics: case-insensitive
 * keys, ';' separated, values optionally quoted with ' or ").
 */
class ConnectionStringBuilder(connectionString: String) {
    private val keys = sortedMapOf<String, String>(String.CASE_INSENSITIVE_ORDER)

    init {
        var i = 0
        val s = connectionString
        while (i < s.length) {
            while (i < s.length && (s[i] == ';' || s[i].isWhitespace())) i++
            if (i >= s.length) break
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim()
            i = eq + 1
            while (i < s.length && s[i] == ' ') i++
            val value: String
            if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                val quote = s[i]
                val sb = StringBuilder()
                i++
                while (i < s.length) {
                    if (s[i] == quote) {
                        if (i + 1 < s.length && s[i + 1] == quote) { sb.append(quote); i += 2; continue }
                        i++
                        break
                    }
                    sb.append(s[i]); i++
                }
                value = sb.toString()
                while (i < s.length && s[i] != ';') i++
            } else {
                val end = s.indexOf(';', i).let { if (it < 0) s.length else it }
                value = s.substring(i, end).trim()
                i = end
            }
            keys[key] = value
        }
    }

    operator fun get(key: String): String? = keys[key]

    fun containsKey(key: String) = keys.containsKey(key)

    val entries: Map<String, String> get() = keys
}
