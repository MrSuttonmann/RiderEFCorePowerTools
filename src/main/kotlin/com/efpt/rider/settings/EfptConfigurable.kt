package com.efpt.rider.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/** Settings | Tools | EF Core Power Tools. */
class EfptConfigurable : BoundConfigurable("EF Core Power Tools") {
    private val s get() = EfptSettings.instance

    override fun createPanel(): DialogPanel = panel {
        group("Reverse Engineering") {
            row {
                checkBox("Open generated DbContext")
                    .bindSelected(s::openGeneratedDbContext)
                    .comment("Open the generated DbContext after reverse engineering")
            }
            row {
                checkBox("Run cleanup of obsolete files")
                    .bindSelected(s::runCleanup)
                    .comment("Remove obsolete files after reverse engineering")
            }
            row {
                checkBox("Merge .dacpac files")
                    .bindSelected(s::mergeDacpacs)
                    .comment("Merge dependent .dacpac files")
            }
            row {
                checkBox("Use alternate result set discovery")
                    .bindSelected(s::useLegacyResultSetDiscovery)
                    .comment("Use sp_describe_first_result_set to retrieve stored procedure result sets")
            }
            row {
                checkBox("Use result set fallback")
                    .bindSelected(s::useStoredProcedureResultSetFallback)
                    .comment("Try to infer stored procedure result sets from the stored procedure definition when live discovery fails")
            }
        }
        group("Preview Features") {
            row {
                checkBox("Discover multiple result sets from SQL stored procedures")
                    .bindSelected(s::discoverMultipleResultSets)
                    .comment("Discover multiple result sets from SQL stored procedures, requires Dapper")
            }
        }
        group(".NET") {
            row("dotnet executable:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select dotnet Executable"))
                    .bindText({ s.dotnetPath ?: "" }, { s.dotnetPath = it })
                    .align(AlignX.FILL)
                    .comment("Leave empty to use 'dotnet' from PATH. The .NET 10 runtime is required.")
            }
        }
    }
}
