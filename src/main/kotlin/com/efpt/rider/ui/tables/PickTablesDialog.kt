package com.efpt.rider.ui.tables

import com.efpt.rider.model.Schema
import com.efpt.rider.model.SerializationTableModel
import com.efpt.rider.model.TableModel
import com.intellij.icons.AllIcons
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ide.CommonActionsManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.BrowserLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ThreeStateCheckBox
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Timer
import javax.swing.event.DocumentEvent

/** Mirrors PickTablesDialogResult: selected objects + (possibly edited) renaming rules. */
data class PickTablesResult(val objects: List<SerializationTableModel>, val customReplacers: List<Schema>)

/**
 * Port of the PickTablesDialog object tree (PickTablesViewModel / ObjectTreeViewModel / *InformationViewModel):
 * hosted both by [PickTablesDialog] and by the reverse engineering wizard page 2.
 */
class PickTablesPanel(private val project: Project, parentDisposable: Disposable) {
    private val model = ObjectTreeModel()
    private val view = ObjectTreeView(model)
    private val tree get() = view.tree
    private val disposable = Disposer.newDisposable(parentDisposable, "PickTablesPanel")

    private var searchMode = SearchMode.Text
    private var searchGeneration = 0
    private var disposed = false
    private var updatingSelectAll = false
    private val selectionListeners = mutableListOf<() -> Unit>()

    /** TableSelectionThreeState check box (WPF IsThreeState=False: indeterminate -> checked when clicked). */
    private val selectAll = object : ThreeStateCheckBox() {
        override fun nextState(): State = if (state == State.SELECTED) State.NOT_SELECTED else State.SELECTED
    }

    private val searchField = SearchTextField(false).apply {
        toolTipText = PickTablesTexts.SEARCH_FOR_OBJECTS
        textEditor.toolTipText = PickTablesTexts.SEARCH_FOR_OBJECTS
    }

    private val searchTimer = Timer(300) { runSearch() }.apply { isRepeats = false }

    private val sqliteLink = BrowserLink(PickTablesTexts.INSTALL_SQLITE_TOOLBOX, PickTablesTexts.SQLITE_TOOLBOX_URL).apply {
        // Rider: same link as VS (the SQLite Toolbox is a Visual Studio extension).
        toolTipText = PickTablesTexts.SQLITE_EXPERIENCE
        isVisible = false
    }

    private val uncheckWarning = JBLabel(PickTablesTexts.UNCHECKING_FILE_REMOVED, AllIcons.General.Warning, JBLabel.LEFT).apply {
        isVisible = false
        border = JBUI.Borders.empty(4, 2)
    }

    private val rootPanel = JPanel(BorderLayout(0, JBUI.scale(4)))

    val component: JComponent get() = rootPanel

    init {
        Disposer.register(disposable) {
            disposed = true
            searchTimer.stop()
        }

        model.onSelectionChanged = {
            updateSelectAll()
            selectionListeners.forEach { it() }
        }
        view.onUserUnchecked = { uncheckWarning.isVisible = true }

        selectAll.isThirdStateEnabled = false
        selectAll.toolTipText = null
        selectAll.addActionListener {
            if (updatingSelectAll) return@addActionListener
            // HandleTableSelectionThreeStateChange: select / deselect all objects, then clear the search text.
            val value = selectAll.state == ThreeStateCheckBox.State.SELECTED
            view.userChange { model.setSelectionState(value) }
            searchField.text = ""
        }

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                searchTimer.restart()
            }
        })
        // Down arrow in the search box moves to the tree.
        searchField.textEditor.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "efptFocusTree")
        searchField.textEditor.actionMap.put("efptFocusTree", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (tree.selectionCount == 0 && tree.rowCount > 0) tree.setSelectionRow(0)
                tree.requestFocusInWindow()
            }
        })

        // Search mode popup (GlyphDown button + radio buttons in VS).
        val searchModeGroup = DefaultActionGroup().apply {
            templatePresentation.text = "Search Mode"
            templatePresentation.icon = AllIcons.General.Filter
            isPopup = true
            add(SearchModeAction(SearchMode.Text, PickTablesTexts.USE_TEXT_SEARCH))
            add(SearchModeAction(SearchMode.RegularExpression, PickTablesTexts.USE_REGULAR_EXPRESSION))
        }
        val expander = DefaultTreeExpander(tree)
        val actions = CommonActionsManager.getInstance()
        val toolbarGroup = DefaultActionGroup(
            searchModeGroup,
            actions.createExpandAllAction(expander, tree),
            actions.createCollapseAllAction(expander, tree),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("EFCorePowerTools.PickTables", toolbarGroup, true)
        toolbar.targetComponent = tree
        toolbar.component.isOpaque = false
        toolbar.component.border = JBUI.Borders.empty()

        // Rename context menu ("Rename  F2", wizard page) on tables and on columns of selected tables.
        val renameAction = object : AnAction(PickTablesTexts.RENAME, null, AllIcons.Actions.Edit) {
            init {
                shortcutSet = CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0))
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = view.canRename(tree.selectionPath?.lastPathComponent)
            }

            override fun actionPerformed(e: AnActionEvent) = view.startRename()
        }
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(renameAction), "EFCorePowerTools.PickTables.Popup")

        val searchRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            val left = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(selectAll, BorderLayout.WEST)
                add(JBLabel(PickTablesTexts.SEARCH), BorderLayout.CENTER)
            }
            add(left, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }

        val bottom = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(sqliteLink)
            add(uncheckWarning)
        }

        // Selecting all with a check box left of the tree: align it with the tree's check boxes as far as practical.
        selectAll.border = JBUI.Borders.emptyLeft(2)

        rootPanel.add(searchRow, BorderLayout.NORTH)
        rootPanel.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        rootPanel.add(bottom, BorderLayout.SOUTH)
        rootPanel.minimumSize = JBUI.size(420, 300)
        updateSelectAll()
    }

    /** AddTables + PreselectTables + SqliteToolboxInstall. May be called again to reload. */
    fun load(
        tables: List<TableModel>,
        customReplacers: List<Schema>?,
        preselected: List<SerializationTableModel>,
        sqliteToolboxInstalled: Boolean = true,
    ) {
        if (tree.isEditing) tree.cancelEditing()
        searchGeneration++ // drop pending search results computed for the previous objects
        model.batch {
            model.clear() // Wizard: "Initialize to known state (empty)"
            model.addObjects(tables, customReplacers)
            // ReverseEngineerHelper.NormalizeTables, as the VS handlers do before PreselectTables.
            model.selectObjects(ObjectTreeModel.normalizeTables(preselected, ObjectTreeModel.shouldNormalize(tables)))
            model.markChanged()
        }
        // Re-apply the current search to the new objects.
        model.search(searchField.text, searchMode)
        view.rebuild(expandTypes = true)
        sqliteLink.isVisible = !sqliteToolboxInstalled
        uncheckWarning.isVisible = false
        updateSelectAll()
    }

    /** Validation for OK/Next (e.g. nothing selected / invalid names); null when valid. */
    fun validate(): ValidationInfo? {
        if (view.isEditingWithEmptyName) {
            return ValidationInfo(PickTablesTexts.NEW_NAME_CANNOT_BE_EMPTY, tree)
        }
        if (!model.canOk()) {
            return ValidationInfo(PickTablesTexts.NOTHING_SELECTED, tree)
        }
        return null
    }

    fun result(): PickTablesResult {
        view.commitEditing(showMessage = false)
        return PickTablesResult(model.getSelectedObjects(), model.getRenamedObjects())
    }

    val preferredFocus: JComponent? get() = tree

    internal fun addSelectionListener(listener: () -> Unit) {
        selectionListeners.add(listener)
    }

    internal val canOk: Boolean get() = model.canOk()

    /** Commits a pending in-place rename (showing "New name cannot be empty" when needed). */
    internal fun commitEditing(): Boolean = view.commitEditing(showMessage = true)

    internal fun cancelEditing() {
        if (tree.isEditing) tree.cancelEditing()
    }

    private fun updateSelectAll() {
        updatingSelectAll = true
        try {
            selectAll.state = when (model.getSelectionState()) {
                true -> ThreeStateCheckBox.State.SELECTED
                false -> ThreeStateCheckBox.State.NOT_SELECTED
                null -> ThreeStateCheckBox.State.DONT_CARE
            }
        } finally {
            updatingSelectAll = false
        }
    }

    /** HandleSearchTextChange: debounced 300 ms, matching done off the EDT (regex can be slow). */
    private fun runSearch() {
        val generation = ++searchGeneration
        val text = searchField.text
        val mode = searchMode
        val items = model.objects
        val names = items.map { it.displayName }
        ApplicationManager.getApplication().executeOnPooledThread {
            val visibility = ObjectTreeModel.computeVisibility(names, text, mode) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (generation != searchGeneration || disposed) return@invokeLater
                if (model.objects.size != items.size) return@invokeLater // reloaded meanwhile
                model.applyVisibility(items, visibility)
                view.rebuild()
            }, ModalityState.any())
        }
    }

    private inner class SearchModeAction(private val mode: SearchMode, text: String) : ToggleAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent) = searchMode == mode

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state && searchMode != mode) {
                searchMode = mode
                searchTimer.restart()
            }
        }
    }
}

/** IPickTablesDialog. Call on the EDT; returns null when cancelled. */
object PickTablesDialog {
    fun show(
        project: Project,
        tables: List<TableModel>,
        customReplacers: List<Schema>?,
        preselected: List<SerializationTableModel>,
        sqliteToolboxInstalled: Boolean = true,
        title: String = PickTablesTexts.CHOOSE_DATABASE_OBJECTS,
    ): PickTablesResult? {
        val dialog = Wrapper(project, title)
        dialog.panel.load(tables, customReplacers, preselected, sqliteToolboxInstalled)
        dialog.updateOk()
        return if (dialog.showAndGet()) dialog.result else null
    }

    private class Wrapper(project: Project, title: String) : DialogWrapper(project, true) {
        val panel = PickTablesPanel(project, disposable)
        var result: PickTablesResult? = null

        init {
            this.title = title
            setOKButtonText("OK")
            setCancelButtonText("Cancel")
            panel.addSelectionListener { updateOk() }
            init()
        }

        /** OkCommand.CanExecute: VS silently disables OK until something is selected. */
        fun updateOk() {
            isOKActionEnabled = panel.canOk
        }

        override fun createCenterPanel(): JComponent = panel.component.apply {
            preferredSize = JBUI.size(460, 560)
        }

        override fun getPreferredFocusedComponent(): JComponent? = panel.preferredFocus

        override fun getDimensionServiceKey() = "EFCorePowerTools.PickTablesDialog"

        override fun doOKAction() {
            // DialogWindow_Closing: the dialog cannot close while a rename is pending.
            if (!panel.commitEditing() || !panel.canOk) return
            result = panel.result()
            super.doOKAction()
        }

        override fun doCancelAction() {
            panel.cancelEditing()
            super.doCancelAction()
        }
    }
}
