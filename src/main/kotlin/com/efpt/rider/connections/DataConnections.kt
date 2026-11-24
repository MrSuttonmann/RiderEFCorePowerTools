package com.efpt.rider.connections

import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.util.Ui
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project

/**
 * Port of VsDataHelper: all database connections available to the user (saved EF Core Power Tools
 * connections + Rider Database tool window data sources converted to ADO.NET connection strings).
 */
object DataConnections {
    private const val DATABASE_PLUGIN_ID = "com.intellij.database"

    /** True when the optional Database Tools plugin is installed and enabled (Rider data sources available). */
    val isDatabasePluginEnabled: Boolean
        get() {
            val id = PluginId.getId(DATABASE_PLUGIN_ID)
            return PluginManagerCore.getPlugin(id) != null && !PluginManagerCore.isDisabled(id)
        }

    /**
     * VsDataHelper.GetDataConnectionsAsync - keyed by connection name. Call off the EDT.
     * Order matches VS: IDE data connections (Server Explorer -> Rider data sources) first, then the
     * credential store connections (sorted by name). Duplicate names keep the first entry.
     */
    fun getDataConnections(project: Project): Map<String, DatabaseConnectionModel> {
        val result = linkedMapOf<String, DatabaseConnectionModel>()
        if (isDatabasePluginEnabled) {
            try {
                RiderDataSources.getDataConnections(project).forEach { c ->
                    val name = c.connectionName ?: return@forEach
                    result.putIfAbsent(name, c)
                }
            } catch (e: Exception) {
                Ui.logError(project, listOf(e.message ?: e.toString()), null)
            }
        }
        try {
            ConnectionStore.instance.getConnections().sortedBy { it.connectionName }.forEach { c ->
                val name = c.connectionName ?: return@forEach
                result.putIfAbsent(name, c)
            }
        } catch (e: Exception) {
            Ui.logError(project, listOf(e.message ?: e.toString()), null)
        }
        return result
    }

    /**
     * Name of the Rider data source selected in the Database tool window (the key used in
     * [getDataConnections]), or null. Must be cheap: called from AnAction.update on the BGT.
     */
    fun selectedDataSourceName(e: AnActionEvent): String? =
        if (isDatabasePluginEnabled) runCatching { RiderDataSources.selectedName(e) }.getOrNull() else null

    /** Provider of the selected Rider data source (Undefined if not supported by EF Core Power Tools). Cheap. */
    fun selectedDataSourceType(e: AnActionEvent): DatabaseType? =
        if (isDatabasePluginEnabled) runCatching { RiderDataSources.selectedType(e) }.getOrNull() else null
}
