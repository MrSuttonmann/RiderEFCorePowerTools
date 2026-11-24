package com.efpt.rider.ui.compare

import com.efpt.rider.connections.ConnectionStore
import com.efpt.rider.model.ConnectionOrigin
import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.ui.PickServerDatabaseDialog
import com.efpt.rider.ui.PickServerDatabaseRequest
import com.efpt.rider.util.Ui
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Port of CompareOptionsDialog (+ CompareOptionsViewModel): choose a database connection and the
 * DbContext types to compare. Call on the EDT.
 */
class CompareOptionsDialog(
    private val project: Project,
    connections: List<DatabaseConnectionModel>,
    contextTypes: List<String>,
) : DialogWrapper(project) {

    data class Selection(val connection: DatabaseConnectionModel?, val contextTypes: List<String>)

    private val connectionModel = DefaultComboBoxModel<DatabaseConnectionModel>().apply {
        connections.forEach { addElement(it) }
    }
    private val connectionCombo = ComboBox(connectionModel).apply {
        // DisplayMemberPath="ConnectionName"
        renderer = SimpleListCellRenderer.create("") { it.connectionName ?: it.displayName }
        selectedItem = null
    }
    private val removeButton = JButton(AllIcons.Actions.Cancel).apply {
        toolTipText = "Remove selected connection"
        addActionListener { removeDatabaseConnection() }
    }
    private val addButton = JButton("Add...").apply {
        toolTipText = "Add Database Connection"
        addActionListener { addDatabaseConnection() }
    }
    private val contextList = CheckBoxList<String>().apply {
        contextTypes.forEach { addItem(it, it, true) }
        setCheckBoxListListener { _, _ -> updateState() }
    }

    init {
        title = "Choose database and contexts"
        setOKButtonText("OK")
        setCancelButtonText("Cancel")
        connectionCombo.addActionListener { updateState() }
        init()
        updateState()
    }

    override fun createCenterPanel(): JComponent {
        val top = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.emptyBottom(12)
            add(connectionCombo, BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(removeButton)
                add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
                add(addButton)
            }, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(contextList), BorderLayout.CENTER)
            // MinWidth 220 / MinHeight 150, MaxWidth 420 / MaxHeight 250 in VS; allow a little more for Swing chrome.
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(220))
            minimumSize = Dimension(JBUI.scale(220), JBUI.scale(150))
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = connectionCombo

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    private val selectedConnection: DatabaseConnectionModel?
        get() = connectionCombo.selectedItem as? DatabaseConnectionModel

    private fun checkedContexts(): List<String> =
        (0 until contextList.itemsCount).mapNotNull { i ->
            contextList.getItemAt(i)?.takeIf { contextList.isItemSelected(it) }
        }

    /** OkCommand CanExecute: SelectedDatabaseConnection != null && ContextTypes.Any(c => c.Selected). */
    private fun updateState() {
        isOKActionEnabled = selectedConnection != null && checkedContexts().isNotEmpty()
        removeButton.isEnabled = selectedConnection != null
    }

    /** CompareOptionsViewModel.GetSelection. */
    fun getSelection(): Selection = Selection(selectedConnection, checkedContexts())

    /**
     * AddDatabaseConnection_Executed. Rider: VS prompts with its Data Connection dialog
     * (PromptForNewDatabaseConnection); the plugin's connection picker (which can add and save new
     * connections) is used instead.
     */
    private fun addDatabaseConnection() {
        val newConnection = try {
            val existing = (0 until connectionModel.size).map { connectionModel.getElementAt(it) }
            PickServerDatabaseDialog.show(
                project,
                PickServerDatabaseRequest(connections = existing, title = "Add Database Connection"),
            )?.connection
        } catch (e: Exception) {
            Ui.showMessage(project, "Unable to add connection, maybe the provider is not supported: ${e.message}")
            return
        } ?: return

        val existing = (0 until connectionModel.size).map { connectionModel.getElementAt(it) }.firstOrNull {
            it.connectionName == newConnection.connectionName && it.connectionString == newConnection.connectionString
        }
        if (existing == null) connectionModel.addElement(newConnection)
        connectionCombo.selectedItem = existing ?: newConnection
        updateState()
    }

    /**
     * RemoveDatabaseConnection_Executed. Rider: saved EF Core Power Tools connections are deleted from
     * the connection store (VS deletes the Server Explorer connection); Rider Database tool window data
     * sources are only removed from this list - they are managed in the Database tool window.
     */
    private fun removeDatabaseConnection() {
        val selected = selectedConnection ?: return
        try {
            if (selected.origin == ConnectionOrigin.Stored) {
                selected.connectionName?.let { name ->
                    // PasswordSafe access must not run on the EDT.
                    ApplicationManager.getApplication().executeOnPooledThread { ConnectionStore.instance.remove(name) }
                }
            }
            connectionModel.removeElement(selected)
        } catch (e: Exception) {
            Ui.showMessage(project, "Unable to remove connection: ${e.message}")
            return
        }
        connectionCombo.selectedItem = null
        updateState()
    }
}
