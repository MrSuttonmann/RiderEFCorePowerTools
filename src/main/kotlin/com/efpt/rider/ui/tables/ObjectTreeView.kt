package com.efpt.rider.ui.tables

import com.efpt.rider.util.Ui
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import com.intellij.ui.LayeredIcon
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBTextField
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ThreeStateCheckBox
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.EventObject
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.AbstractAction
import javax.swing.AbstractCellEditor
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreeCellEditor
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

internal object ObjectTreeIcons {
    private val tableWithoutKey: Icon = LayeredIcon.layeredIcon(arrayOf(AllIcons.Nodes.DataTables, AllIcons.Nodes.WarningMark))
    val primaryKey: Icon = LayeredIcon.layeredIcon(arrayOf(AllIcons.Nodes.DataColumn, AllIcons.Modifiers.Key))
    val column: Icon get() = AllIcons.Nodes.DataColumn
    val schema: Icon get() = AllIcons.Nodes.DataSchema

    fun of(icon: ObjectTypeIcon): Icon = when (icon) {
        ObjectTypeIcon.Table -> AllIcons.Nodes.DataTables
        ObjectTypeIcon.TableWithoutKey -> tableWithoutKey
        ObjectTypeIcon.View -> AllIcons.Actions.Preview
        ObjectTypeIcon.Procedure -> AllIcons.Nodes.Method
        ObjectTypeIcon.ScalarFunction -> AllIcons.Nodes.Function
    }

    fun of(node: Any?): Icon? = when (node) {
        is TypeItem -> of(node.objectTypeIcon)
        is SchemaItem -> schema
        is TableItem -> of(node.objectTypeIcon)
        is ColumnItem -> if (node.isPrimaryKey) primaryKey else column
        else -> null
    }
}

/**
 * Swing TreeModel over [ObjectTreeModel]: Types > Schemas > Objects > Columns. Objects hidden by the search
 * (IsVisible = false) are left out, as are schemas and types without visible objects (the WPF Collapsed triggers).
 */
internal class ObjectTreeSwingModel(private val model: ObjectTreeModel) : TreeModel {
    private val rootNode = Any()
    private val listeners = CopyOnWriteArrayList<TreeModelListener>()
    private var visibleTypes: List<TypeItem> = emptyList()
    private val visibleSchemas = HashMap<TypeItem, List<SchemaItem>>()
    private val visibleObjects = HashMap<SchemaItem, List<TableItem>>()

    fun rebuild() {
        visibleSchemas.clear()
        visibleObjects.clear()
        val types = mutableListOf<TypeItem>()
        for (type in model.types) {
            val schemas = mutableListOf<SchemaItem>()
            for (schema in type.schemas) {
                val objects = schema.objects.filter { it.isVisible }
                if (objects.isNotEmpty()) {
                    visibleObjects[schema] = objects
                    schemas.add(schema)
                }
            }
            if (schemas.isNotEmpty()) {
                visibleSchemas[type] = schemas
                types.add(type)
            }
        }
        visibleTypes = types
        val e = TreeModelEvent(this, arrayOf(rootNode))
        listeners.forEach { it.treeStructureChanged(e) }
    }

    fun pathTo(node: Any): TreePath = when (node) {
        is TypeItem -> TreePath(arrayOf(rootNode, node))
        is SchemaItem -> TreePath(arrayOf(rootNode, visibleTypes.first { visibleSchemas[it]?.contains(node) == true }, node))
        is TableItem -> pathTo(node.parent).pathByAddingChild(node)
        is ColumnItem -> pathTo(node.table).pathByAddingChild(node)
        else -> TreePath(rootNode)
    }

    fun nodeChanged(path: TreePath) {
        val parent = path.parentPath ?: return
        val index = getIndexOfChild(parent.lastPathComponent, path.lastPathComponent)
        if (index < 0) return
        val e = TreeModelEvent(this, parent, intArrayOf(index), arrayOf(path.lastPathComponent))
        listeners.forEach { it.treeNodesChanged(e) }
    }

    override fun getRoot(): Any = rootNode

    private fun children(parent: Any?): List<Any> = when (parent) {
        rootNode -> visibleTypes
        is TypeItem -> visibleSchemas[parent].orEmpty()
        is SchemaItem -> visibleObjects[parent].orEmpty()
        is TableItem -> parent.columns
        else -> emptyList()
    }

    override fun getChild(parent: Any?, index: Int): Any = children(parent)[index]

    override fun getChildCount(parent: Any?): Int = children(parent).size

    override fun isLeaf(node: Any?): Boolean = node is ColumnItem || (node is TableItem && node.columns.isEmpty())

    override fun valueForPathChanged(path: TreePath?, newValue: Any?) = Unit

    override fun getIndexOfChild(parent: Any?, child: Any?): Int = children(parent).indexOfFirst { it === child }

    override fun addTreeModelListener(l: TreeModelListener) {
        listeners.add(l)
    }

    override fun removeTreeModelListener(l: TreeModelListener) {
        listeners.remove(l)
    }
}

/** Renders a node as [tri-state check box][icon][name], like the WPF HierarchicalDataTemplates. */
internal class ObjectTreeRenderer : TreeCellRenderer {
    val checkBox = ThreeStateCheckBox().apply {
        isOpaque = false
        border = JBUI.Borders.emptyRight(2)
    }
    private val label = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBUI.insets(0, 0, 0, 2)
    }
    private val panel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(checkBox, BorderLayout.WEST)
        add(label, BorderLayout.CENTER)
    }

    val checkBoxWidth: Int get() = checkBox.preferredSize.width

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        label.clear()
        label.foreground = RenderingUtil.getForeground(tree, selected)
        label.icon = ObjectTreeIcons.of(value)
        checkBox.isEnabled = true
        panel.toolTipText = null

        var attributes = SimpleTextAttributes.REGULAR_ATTRIBUTES
        when (value) {
            is TypeItem -> {
                checkBox.state = stateOf(value.isSelected)
                label.append(value.text, attributes)
            }

            is SchemaItem -> {
                checkBox.state = stateOf(value.isSelected)
                label.append(value.name ?: "", attributes)
            }

            is TableItem -> {
                checkBox.state = stateOf(value.isSelected)
                label.append(value.displayName, attributes)
            }

            is ColumnItem -> {
                checkBox.state = stateOf(value.isSelected)
                checkBox.isEnabled = value.isEnabled
                if (value.isPrimaryKey) {
                    attributes = SimpleTextAttributes.GRAYED_ATTRIBUTES
                    panel.toolTipText = PickTablesTexts.CANNOT_UNCHECK_PRIMARY_KEY
                }
                label.append(value.displayName, attributes)
            }
        }
        return panel
    }

    private fun stateOf(value: Boolean?) = when (value) {
        true -> ThreeStateCheckBox.State.SELECTED
        false -> ThreeStateCheckBox.State.NOT_SELECTED
        null -> ThreeStateCheckBox.State.DONT_CARE
    }
}

/**
 * The object tree (PickTablesDialog TreeView): check boxes, Space to toggle, F2 / context menu to rename
 * tables and columns in place (TreeTextRenamer_KeyUp / ConfirmEdit / CancelEdit).
 */
internal class ObjectTreeView(private val model: ObjectTreeModel) {
    val swingModel = ObjectTreeSwingModel(model)
    private val renderer = ObjectTreeRenderer()
    private val editor = RenameEditor()

    /** Raised after the user unchecked a schema / table (CheckBox_Unchecked => "UncheckingFileRemoved" warning). */
    var onUserUnchecked: (() -> Unit)? = null

    val tree: Tree = object : Tree(swingModel) {
        override fun isPathEditable(path: TreePath?): Boolean = canRename(path?.lastPathComponent)
    }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = renderer
        tree.cellEditor = editor
        tree.isEditable = true
        tree.invokesStopCellEditing = true
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1) return
                val path = tree.getClosestPathForLocation(e.x, e.y) ?: return
                val bounds = tree.getPathBounds(path) ?: return
                if (e.y < bounds.y || e.y >= bounds.y + bounds.height) return
                if (e.x >= bounds.x && e.x < bounds.x + renderer.checkBoxWidth) {
                    if (tree.isEditing && !tree.stopEditing()) return
                    toggleByClick(path.lastPathComponent)
                    e.consume()
                }
            }
        })

        val inputMap = tree.getInputMap(JComponent.WHEN_FOCUSED)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "efptToggle")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "efptRename")
        tree.actionMap.put("efptToggle", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                toggleBySpace(tree.selectionPath?.lastPathComponent)
            }
        })
        tree.actionMap.put("efptRename", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                startRename()
            }
        })
    }

    fun canRename(node: Any?): Boolean = node is TableItem || (node is ColumnItem && node.isTableSelected)

    /** Tree_KeyUp F2 / TreeNodeContextMenuItem_Click. */
    fun startRename() {
        val path = tree.selectionPath ?: return
        if (canRename(path.lastPathComponent)) {
            tree.startEditingAtPath(path)
        }
    }

    /** Rebuilds the visible structure (after load or search), keeping expanded nodes and the selection. */
    fun rebuild(expandTypes: Boolean = false) {
        if (tree.isEditing) {
            editor.commit(showMessage = false)
            tree.cancelEditing()
        }
        val expanded = tree.getExpandedDescendants(TreePath(swingModel.getRoot()))?.toList().orEmpty()
        val selected = tree.selectionPaths?.toList().orEmpty()
        swingModel.rebuild()
        for (p in expanded) {
            if (isVisiblePath(p)) tree.expandPath(p)
        }
        if (expandTypes) {
            for (i in 0 until swingModel.getChildCount(swingModel.getRoot())) {
                tree.expandPath(TreePath(arrayOf(swingModel.getRoot(), swingModel.getChild(swingModel.getRoot(), i))))
            }
        }
        val stillVisible = selected.filter { isVisiblePath(it) }
        if (stillVisible.isNotEmpty()) tree.selectionPaths = stillVisible.toTypedArray()
    }

    private fun isVisiblePath(path: TreePath): Boolean {
        var parent: Any? = null
        for (component in path.path) {
            if (parent != null && swingModel.getIndexOfChild(parent, component) < 0) return false
            parent = component
        }
        return true
    }

    /** Commits a pending rename. Returns false when it cannot be committed (empty new name). */
    fun commitEditing(showMessage: Boolean): Boolean {
        if (!tree.isEditing) return true
        if (showMessage) return tree.stopEditing()
        val committed = editor.commit(showMessage = false)
        tree.cancelEditing()
        return committed
    }

    val isEditingWithEmptyName: Boolean get() = tree.isEditing && editor.currentText.isBlank()

    private fun toggleByClick(node: Any?) {
        // WPF two-state check box: an indeterminate (null) box becomes checked when clicked.
        when (node) {
            is TypeItem -> userChange { node.setSelected(node.isSelected != true) }
            is SchemaItem -> userChange { node.setSelected(node.isSelected != true) }
            is TableItem -> userChange { node.setSelected(!node.isSelected) }
            is ColumnItem -> userChange { node.setSelectedCommand(!node.isSelected) }
        }
    }

    private fun toggleBySpace(node: Any?) {
        // vm.SetSelectedCommand.Execute(vm.IsSelected == null ? false : !vm.IsSelected)
        when (node) {
            is TypeItem -> userChange { node.setSelected(node.isSelected?.not() ?: false) }
            is SchemaItem -> userChange { node.setSelected(node.isSelected?.not() ?: false) }
            is TableItem -> userChange { node.setSelected(!node.isSelected) }
            is ColumnItem -> userChange { node.setSelectedCommand(!node.isSelected) }
        }
    }

    /** Runs a user-initiated selection change and raises [onUserUnchecked] when objects got unchecked. */
    fun userChange(action: () -> Unit) {
        val before = model.objects.count { it.isSelected }
        model.batch(action)
        if (model.objects.count { it.isSelected } < before) {
            onUserUnchecked?.invoke()
        }
        tree.repaint()
    }

    private inner class RenameEditor : AbstractCellEditor(), TreeCellEditor {
        private val textField = JBTextField()
        private val iconLabel = JLabel()
        private val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(iconLabel, BorderLayout.WEST)
            add(textField, BorderLayout.CENTER)
        }
        private var item: Any? = null
        private var showingMessage = false

        val currentText: String get() = textField.text

        init {
            iconLabel.border = JBUI.Borders.empty(0, renderer.checkBoxWidth, 0, 3)
            textField.addActionListener { tree.stopEditing() } // Key.Return => ConfirmEditCommand
            textField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "efptCancelEdit")
            textField.actionMap.put("efptCancelEdit", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    // Key.Escape => CancelEditCommand: NewName = Name
                    when (val node = item) {
                        is TableItem -> node.cancelEdit()
                        is ColumnItem -> node.cancelEdit()
                    }
                    tree.cancelEditing()
                    refresh(node = item)
                }
            })
            textField.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) {
                    // LostFocus => ConfirmEditCommand
                    if (e.isTemporary || showingMessage) return
                    SwingUtilities.invokeLater { if (tree.isEditing && !showingMessage) tree.stopEditing() }
                }
            })
        }

        override fun getTreeCellEditorComponent(
            tree: JTree,
            value: Any?,
            isSelected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
        ): Component {
            item = value
            iconLabel.icon = ObjectTreeIcons.of(value)
            textField.text = when (value) {
                is TableItem -> value.newName
                is ColumnItem -> value.newName
                else -> ""
            }
            textField.selectAll()
            return panel
        }

        override fun getCellEditorValue(): Any = textField.text

        override fun isCellEditable(e: EventObject?): Boolean = e == null || e !is MouseEvent

        override fun stopCellEditing(): Boolean {
            if (!commit(showMessage = true)) return false
            fireEditingStopped()
            refresh(item)
            return true
        }

        override fun cancelCellEditing() {
            fireEditingCanceled()
        }

        /** ConfirmEditCommand: an empty name shows "New name cannot be empty" and keeps editing. */
        fun commit(showMessage: Boolean): Boolean {
            val text = textField.text
            if (text.isBlank()) {
                if (showMessage && !showingMessage) {
                    showingMessage = true
                    try {
                        Messages.showInfoMessage(tree, PickTablesTexts.NEW_NAME_CANNOT_BE_EMPTY, Ui.TITLE)
                    } finally {
                        showingMessage = false
                    }
                    textField.requestFocusInWindow()
                }
                return false
            }
            when (val node = item) {
                is TableItem -> node.newName = text
                is ColumnItem -> node.newName = text
            }
            return true
        }

        private fun refresh(node: Any?) {
            if (node == null) return
            SwingUtilities.invokeLater {
                runCatching { swingModel.nodeChanged(swingModel.pathTo(node)) }
                tree.repaint()
            }
        }
    }
}
