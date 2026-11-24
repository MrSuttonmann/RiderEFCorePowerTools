package com.efpt.rider.ui

import com.efpt.rider.handlers.reveng.RevEngLocale
import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.model.SchemaInfo
import com.efpt.rider.tools.BundledTools
import com.efpt.rider.ui.reveng.DatabaseConnectionPanel
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.Dimension
import javax.swing.JComponent

/** Mirrors Contracts.ViewModels.CodeGenerationItem: Key = CodeGenerationMode value, Value = caption. */
data class CodeGenerationItem(val key: Int, val value: String) {
    override fun toString() = value
}

/** Inputs of IPickServerDatabaseDialog (PublishConnections / PublishDefinitions / PublishSchemas / PublishCodeGenerationMode / PublishUiHint). */
data class PickServerDatabaseRequest(
    val connections: List<DatabaseConnectionModel> = emptyList(),
    val definitions: List<DatabaseConnectionModel> = emptyList(),
    /** Non-null => schemas are pre-published (filter enabled with these schemas). */
    val schemas: List<SchemaInfo>? = null,
    val codeGenerationMode: CodeGenerationMode = CodeGenerationMode.EFCore10,
    /** Empty => the EF Core version selector is hidden. A single item with value "DAB" also hides the schema filter. */
    val allowedVersions: List<CodeGenerationItem> = emptyList(),
    val uiHint: String? = null,
    val title: String = "Choose Database Connection",
)

/** Payload of PickServerDatabaseDialog (DatabaseConnectionModel + options). */
data class PickServerDatabaseResult(
    val connection: DatabaseConnectionModel?,
    val codeGenerationMode: CodeGenerationMode,
    val filterSchemas: Boolean,
    val schemas: List<SchemaInfo>?,
    val uiHint: String?,
)

/**
 * Port of PickServerDatabaseDialog (+ PickServerDatabaseViewModel, ConnectionDialog, PickSchemasDialog):
 * choose a saved connection / Rider data source / SQL project / .dacpac, add or remove connections,
 * optionally filter schemas and pick the EF Core version. Call on the EDT; returns null when cancelled.
 */
object PickServerDatabaseDialog {
    fun show(project: Project, request: PickServerDatabaseRequest): PickServerDatabaseResult? {
        val connectionPanel = DatabaseConnectionPanel(project, wizardMode = false)
        if (request.connections.isNotEmpty()) connectionPanel.publishConnections(request.connections)
        if (request.definitions.isNotEmpty()) connectionPanel.publishDefinitions(request.definitions)
        request.schemas?.let { connectionPanel.publishSchemas(it) }
        connectionPanel.publishCodeGenerationMode(request.codeGenerationMode, request.allowedVersions)
        if (!request.uiHint.isNullOrEmpty()) connectionPanel.uiHint = request.uiHint
        connectionPanel.loaded()

        val dialog = Dialog(project, connectionPanel, request.title)
        if (!dialog.showAndGet()) return null
        return PickServerDatabaseResult(
            connection = connectionPanel.selected,
            codeGenerationMode = connectionPanel.codeGenerationMode,
            filterSchemas = connectionPanel.filterSchemas,
            schemas = connectionPanel.schemas.toList(),
            uiHint = connectionPanel.uiHint,
        )
    }

    private class Dialog(project: Project, private val connectionPanel: DatabaseConnectionPanel, title: String) : DialogWrapper(project) {
        init {
            this.title = title
            // Ok_CanExecute: a connection must be selected
            connectionPanel.onChange = { isOKActionEnabled = connectionPanel.selected != null }
            init()
            isOKActionEnabled = connectionPanel.selected != null
        }

        override fun createCenterPanel(): JComponent = panel {
            row { cell(connectionPanel.component).align(AlignX.FILL) }
            row {
                cell(ActionLink(BundledTools.pluginVersion) { BrowserUtil.browse(RevEngLocale.ReleaseNotesUrl) })
                    .applyToComponent { toolTipText = RevEngLocale.ReleaseNotes }
                    .align(AlignX.RIGHT)
            }
        }.apply { minimumSize = Dimension(420, 160); preferredSize = Dimension(520, preferredSize.height) }

        override fun getPreferredFocusedComponent() = connectionPanel.preferredFocus
    }
}
