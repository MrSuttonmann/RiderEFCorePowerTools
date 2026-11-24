package com.efpt.rider.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** Application level options - the equivalent of the VS "Tools > Options > EF Core Power Tools > General" page (AdvancedOptions). */
class EfptSettingsState : BaseState() {
    // Preview Features
    /** Discover multiple result sets from SQL stored procedures, requires Dapper. */
    var discoverMultipleResultSets by property(false)

    // Reverse Engineering
    /** Open the generated DbContext after reverse engineering. */
    var openGeneratedDbContext by property(true)

    /** Remove obsolete files after reverse engineering. */
    var runCleanup by property(true)

    /** Merge dependent .dacpac files. */
    var mergeDacpacs by property(false)

    /** Use sp_describe_first_result_set to retrieve stored procedure result sets. */
    var useLegacyResultSetDiscovery by property(false)

    /** Try to infer stored procedure result sets from the stored procedure definition when live discovery fails. */
    var useStoredProcedureResultSetFallback by property(true)

    // Rider specific
    /** Path or command used to launch `dotnet`. Empty means resolve from PATH. */
    var dotnetPath by string("")
}

@Service(Service.Level.APP)
@State(name = "EFCorePowerToolsSettings", storages = [Storage("efCorePowerTools.xml")])
class EfptSettings : SimplePersistentStateComponent<EfptSettingsState>(EfptSettingsState()) {
    companion object {
        val instance: EfptSettingsState get() = service<EfptSettings>().state

        fun dotnet(): String = instance.dotnetPath?.takeIf { it.isNotBlank() } ?: "dotnet"
    }
}
