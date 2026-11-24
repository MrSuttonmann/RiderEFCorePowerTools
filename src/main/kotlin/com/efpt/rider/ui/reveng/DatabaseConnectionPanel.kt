package com.efpt.rider.ui.reveng

import com.efpt.rider.connections.ConnectionStore
import com.efpt.rider.handlers.reveng.RevEngLocale
import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.model.ConnectionOrigin
import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.SchemaInfo
import com.efpt.rider.tools.ProcessRunner
import com.efpt.rider.ui.CodeGenerationItem
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPopupMenu

/**
 * Shared content of PickServerDatabaseDialog and wizard page 1 (PickServerDatabaseViewModel /
 * WizardDataViewModel page 1): connection selector with add / remove, EF Core version, schema filter.
 */
class DatabaseConnectionPanel(private val project: Project, private val wizardMode: Boolean) {
    private val connections = CollectionComboBoxModel<DatabaseConnectionModel>()
    private val connectionCombo = ComboBox(connections).apply {
        renderer = object : com.intellij.ui.ColoredListCellRenderer<DatabaseConnectionModel>() {
            override fun customizeCellRenderer(list: javax.swing.JList<out DatabaseConnectionModel>, value: DatabaseConnectionModel?, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value?.displayName ?: "")
                // Rider: the VS tooltip shows the raw connection string; passwords are redacted here.
                toolTipText = value?.toolTip?.let { ProcessRunner.redact(it) }
            }
        }
    }
    private val removeButton = JButton(AllIcons.General.Remove).apply { toolTipText = RevEngLocale.RemoveSelectedConnection }
    private val addButton = JButton(RevEngLocale.Add)
    private val versions = CollectionComboBoxModel<CodeGenerationItem>()
    private val versionCombo = ComboBox(versions).apply { toolTipText = RevEngLocale.EFCoreVersionTip }
    private val filterSchemasCheck = JBCheckBox(RevEngLocale.FilterSchemas)
    private val addSchemasButton = JButton(RevEngLocale.Add).apply { toolTipText = RevEngLocale.AddSchemasToFilter }

    private lateinit var versionLabelRow: Row
    private lateinit var versionRow: Row
    private lateinit var filterRow: Row

    private var uiHintValue: String? = null

    /** Schemas used when [filterSchemas] is checked. */
    var schemas: MutableList<SchemaInfo> = mutableListOf()
        private set

    /** Notified when the selection changes (wizard: Next button state). */
    var onChange: () -> Unit = {}

    init {
        connectionCombo.addActionListener { updateState(); onChange() }
        filterSchemasCheck.addActionListener { updateState(); onChange() }
        versionCombo.addActionListener { onChange() }
        removeButton.addActionListener { removeConnection() }
        addSchemasButton.addActionListener { filterSchemas() }
        addButton.addActionListener {
            val menu = JPopupMenu()
            // Rider: VS has "Add Database Connection" (DDEX dialog) and "Add Custom Connection"; Rider has no
            // DDEX, so a single entry opens the connection dialog for all supported providers.
            menu.add(action(RevEngLocale.AddDatabaseConnection + "...") { addConnection() })
            menu.add(action(RevEngLocale.AddExternalDacPac + "...") { addDefinition() })
            menu.show(addButton, 0, addButton.height)
        }
    }

    val component: JComponent by lazy {
        panel {
            row {
                cell(connectionCombo).align(AlignX.FILL).resizableColumn()
                cell(removeButton)
                cell(addButton)
            }
            versionLabelRow = row { label(RevEngLocale.EFCoreVersionTip) }
            versionRow = row { cell(versionCombo).align(AlignX.FILL) }
            filterRow = row {
                cell(filterSchemasCheck)
                cell(addSchemasButton).align(AlignX.RIGHT)
            }
        }.also { updateState() }
    }

    val preferredFocus: JComponent get() = connectionCombo

    // ------------------------------------------------------------------ IPickServerDatabaseDialog publishing

    fun publishConnections(models: List<DatabaseConnectionModel>) = addAll(models)

    fun publishDefinitions(models: List<DatabaseConnectionModel>) = addAll(models)

    private fun addAll(models: List<DatabaseConnectionModel>) {
        models.filter { m -> connections.items.none { it.displayName == m.displayName } }.forEach { connections.add(it) }
        updateState()
    }

    fun publishSchemas(models: List<SchemaInfo>) {
        filterSchemasCheck.isSelected = models.isNotEmpty()
        models.filter { m -> schemas.none { it.name == m.name } }.forEach { schemas.add(it) }
        updateState()
    }

    /** PublishCodeGenerationMode: an empty list hides the version selector; a single "DAB" item hides version and schema rows. */
    fun publishCodeGenerationMode(mode: CodeGenerationMode, allowedVersions: List<CodeGenerationItem>) {
        component // make sure the rows exist
        if (allowedVersions.size == 1 && allowedVersions[0].value == "DAB") {
            versionLabelRow.visible(false)
            versionRow.visible(false)
            filterRow.visible(false)
            dabMode = mode
            return
        }
        allowedVersions.filter { a -> versions.items.none { it.value == a.value } }.forEach { versions.add(it) }
        if (allowedVersions.isEmpty()) {
            versionLabelRow.visible(false)
            versionRow.visible(false)
        }
        versions.selectedItem = versions.items.firstOrNull { it.key == mode.value }
        fallbackMode = mode
    }

    private var dabMode: CodeGenerationMode? = null
    private var fallbackMode: CodeGenerationMode = CodeGenerationMode.EFCore8

    /** UiHint setter: preselects a connection by name or definition path. */
    var uiHint: String?
        get() = selected?.let { it.connectionName ?: it.filePath } ?: uiHintValue
        set(value) {
            if (!value.isNullOrEmpty()) {
                connections.items.firstOrNull { it.connectionName != null && it.connectionName.equals(value, true) }?.let { select(it) }
                connections.items.firstOrNull { it.filePath != null && it.filePath.equals(value, true) }?.let { select(it) }
            }
            uiHintValue = value
        }

    /**
     * LoadedCommand. Dialog (PickServerDatabaseViewModel.Loaded_Executed): preselect by UiHint only.
     * Wizard (WizardDataViewModel.Page1Loaded_Executed): connections first (UiHint or the first one), then SQL projects.
     */
    fun loaded() {
        val hint = uiHint
        if (!wizardMode) {
            if (selected == null && !hint.isNullOrEmpty()) {
                connections.items.firstOrNull { it.filePath == null && it.connectionName.equals(hint, true) }?.let { select(it); return }
                connections.items.firstOrNull { !it.filePath.isNullOrBlank() && it.filePath.equals(hint, true) }?.let { select(it) }
            }
            return
        }
        if (connections.items.any { it.filePath == null } && selected == null) {
            if (!hint.isNullOrEmpty()) {
                connections.items.firstOrNull { it.connectionName == hint }?.let { select(it); return }
            }
            select(connections.items[0])
            return
        }
        if (selected == null && connections.items.any { it.filePath != null }) {
            select(preSelectDatabaseDefinition(hint) ?: connections.items.firstOrNull())
        }
    }

    private fun preSelectDatabaseDefinition(hint: String?): DatabaseConnectionModel? {
        val subset = connections.items.filter { !it.filePath.isNullOrBlank() && it.filePath!!.endsWith(".sqlproj") }
        if (!hint.isNullOrEmpty() && hint.endsWith(".sqlproj")) {
            subset.firstOrNull { it.filePath == hint }?.let { return it }
        }
        return subset.minByOrNull { java.io.File(it.filePath!!).nameWithoutExtension }
    }

    // ------------------------------------------------------------------ results

    val selected: DatabaseConnectionModel? get() = connections.selected

    val codeGenerationMode: CodeGenerationMode
        get() = dabMode ?: versions.selected?.let { CodeGenerationMode.fromValue(it.key) } ?: fallbackMode

    val filterSchemas: Boolean get() = filterSchemasCheck.isSelected

    /** Identifies the choices the object list was loaded from (WizardDataViewModel.GetObjectsSourceKey). */
    fun sourceKey(): String {
        val schemaNames = if (filterSchemas) schemas.mapNotNull { it.name }.sorted().joinToString(",") else ""
        return listOf(selected?.databaseType, selected?.connectionString, selected?.filePath, codeGenerationMode.value, filterSchemas, schemaNames)
            .joinToString("|") { it?.toString() ?: "" }
    }

    // ------------------------------------------------------------------ commands

    private fun select(model: DatabaseConnectionModel?) {
        connections.selectedItem = model
        connectionCombo.repaint()
        updateState()
        onChange()
    }

    private fun updateState() {
        val sel = selected
        // RemoveDatabaseConnection_CanExecute. Rider: Rider data sources are managed in the Database tool window.
        removeButton.isEnabled = sel != null && sel.filePath == null && sel.origin != ConnectionOrigin.RiderDataSource
        addSchemasButton.isEnabled = filterSchemasCheck.isSelected // FilterSchemas_CanExecute
    }

    /** AddAdhocDatabaseConnection_Executed: show the connection dialog and save to the credential store. */
    private fun addConnection() {
        val newConnection = ConnectionDialog.show(component) ?: return
        try {
            ConnectionStore.instance.saveNew(newConnection)
        } catch (e: Exception) {
            Messages.showInfoMessage(component, "${RevEngLocale.UnableToAddConnection}: ${e.message}", com.efpt.rider.util.Ui.TITLE)
            return
        }
        connections.add(newConnection)
        select(newConnection)
    }

    /** AddDatabaseDefinition_Executed / VsDataHelper.PromptForDacpac. */
    private fun addDefinition() {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withExtensionFilter("SQL Server Database Project", "dacpac")
            .withTitle("Select .dacpac File")
        val file = try {
            FileChooser.chooseFile(descriptor, project, null)
        } catch (e: Exception) {
            Messages.showInfoMessage(component, "${RevEngLocale.UnableToAddToList}: ${e.message}", com.efpt.rider.util.Ui.TITLE)
            return
        } ?: return
        val model = DatabaseConnectionModel(
            filePath = java.io.File(file.path).path,
            databaseType = DatabaseType.SQLServerDacpac,
            origin = ConnectionOrigin.Definition,
        )
        connections.add(model)
        select(model)
    }

    /** RemoveDatabaseConnection_Executed. */
    private fun removeConnection() {
        val sel = selected ?: return
        try {
            if (sel.filePath == null && sel.origin == ConnectionOrigin.Stored) {
                ConnectionStore.instance.remove(sel.connectionName ?: return)
            }
            connections.remove(sel)
        } catch (e: Exception) {
            Messages.showInfoMessage(component, "${RevEngLocale.UnableToRemoveConnection}: ${e.message}", com.efpt.rider.util.Ui.TITLE)
            return
        }
        select(null)
    }

    /** FilterSchemas_Executed. */
    private fun filterSchemas() {
        val result = PickSchemasDialog.show(component, schemas) ?: return
        schemas = result.toMutableList()
        onChange()
    }

    private fun action(text: String, block: () -> Unit) = object : AbstractAction(text) {
        override fun actionPerformed(e: ActionEvent?) = block()
    }
}
