package com.efpt.rider.connections

import com.efpt.rider.model.ConnectionOrigin
import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.model.DatabaseType
import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.psi.DbElement
import com.intellij.database.view.DatabaseView
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * Access to Rider Database tool window data sources (the Rider counterpart of the Visual Studio
 * Server Explorer data connections used by VsDataHelper).
 *
 * This is the ONLY class touching com.intellij.database.* - it is loaded only after [DataConnections]
 * has verified that the optional Database Tools plugin is enabled.
 */
internal object RiderDataSources {
    private val LOG = logger<RiderDataSources>()

    /** All supported data sources with ADO.NET connection strings (reads passwords - call off the EDT). */
    fun getDataConnections(project: Project): List<DatabaseConnectionModel> {
        val manager = LocalDataSourceManager.getInstanceOrNull(project) ?: return emptyList()
        val result = mutableListOf<DatabaseConnectionModel>()
        for (ds in manager.dataSources) {
            try {
                val type = JdbcConnectionStrings.databaseType(ds.url)
                if (type == DatabaseType.Undefined) continue
                val password = runCatching { DatabaseCredentials.getInstance().loadPassword(ds)?.toString() }
                    .onFailure { LOG.warn("Unable to read password of data source ${ds.name}", it) }
                    .getOrNull()
                val connectionString = JdbcConnectionStrings.toConnectionString(
                    ds.url ?: continue,
                    ds.username,
                    password,
                    driverProperties(ds),
                ) ?: continue
                result += DatabaseConnectionModel(
                    connectionName = ds.name,
                    connectionString = connectionString,
                    databaseType = type,
                    origin = ConnectionOrigin.RiderDataSource,
                )
            } catch (e: Exception) {
                LOG.warn("Unable to read data source ${ds.name}", e)
            }
        }
        return result
    }

    private fun driverProperties(ds: LocalDataSource): Map<String, String> =
        runCatching { ds.driverProperties.toMap() }.getOrDefault(emptyMap())

    /** The data source of the Database tool window selection; cheap (no password access). */
    fun selected(e: AnActionEvent): LocalDataSource? {
        e.getData(DatabaseView.DATABASE_RELATED_SINGLE_DATA_SOURCE)?.localDataSource?.let { return it }
        val element = e.getData(DatabaseView.DB_ELEMENTS)?.firstOrNull()
            ?: e.getData(LangDataKeys.PSI_ELEMENT_ARRAY)?.filterIsInstance<DbElement>()?.firstOrNull()
            ?: e.getData(CommonDataKeys.PSI_ELEMENT) as? DbElement
            ?: return null
        return element.dataSource.delegate as? LocalDataSource
    }

    fun selectedName(e: AnActionEvent): String? = selected(e)?.name

    fun selectedType(e: AnActionEvent): DatabaseType? = selected(e)?.let { JdbcConnectionStrings.databaseType(it.url) }
}
