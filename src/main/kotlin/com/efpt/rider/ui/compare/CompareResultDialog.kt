package com.efpt.rider.ui.compare

import com.efpt.rider.handlers.compare.CompareAttributes
import com.efpt.rider.handlers.compare.CompareLogModel
import com.efpt.rider.handlers.compare.CompareState
import com.efpt.rider.handlers.compare.CompareType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.ui.dualView.TreeTableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** Port of CompareLogItemViewModel (Visible / Checked / HasChildren are represented by the tree's expansion state). */
class CompareLogItem(
    val level: Int,
    val type: CompareType,
    val state: CompareState,
    val name: String?,
    val attribute: CompareAttributes,
    val expected: String?,
    val found: String?,
) {
    override fun toString() = name ?: ""
}

/**
 * Port of CompareResultDialog (+ CompareResultViewModel). Rider: the VS DataGrid emulates a tree with
 * per-row toggle buttons and indentation; a native TreeTable is used here with the same three levels
 * (context / entity / property), the same columns and the same "Show differences only" filtering and
 * initial expansion behaviour. Call on the EDT.
 */
class CompareResultDialog(project: Project, logs: List<CompareLogModel>) : DialogWrapper(project) {

    private val completeLogs = mutableListOf<CompareLogItem>()
    private val filteredLogs = mutableListOf<CompareLogItem>()

    private val root = DefaultMutableTreeNode()
    private val columns: Array<ColumnInfo<*, *>> = arrayOf(
        TreeColumnInfo("Name"),
        textColumn("Type") { it.type.label },
        textColumn("State") { it.state.label },
        textColumn("Attribute") { it.attribute.label },
        textColumn("Expected") { it.expected },
        textColumn("Found") { it.found },
    )
    private val model = ListTreeTableModelOnColumns(root, columns)
    private val table = TreeTableView(model).apply {
        setRootVisible(false)
        tree.showsRootHandles = true
        setShowGrid(false)
        setShowHorizontalLines(true)
    }
    private val showDifferencesOnly = JBCheckBox("Show differences only", true)

    init {
        title = "Comparison result"
        addComparisonResult(logs)
        // Initially (AddComparisonResult): the filtered logs, only level 0 visible.
        fill(filteredLogs, expandLevel0 = false)
        showDifferencesOnly.addActionListener {
            // ShowDifferencesOnly setter: level 0 checked (expanded), levels 0 and 1 visible.
            fill(if (showDifferencesOnly.isSelected) filteredLogs else completeLogs, expandLevel0 = true)
        }
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(showDifferencesOnly, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        minimumSize = Dimension(JBUI.scale(400), JBUI.scale(300))
        preferredSize = Dimension(JBUI.scale(900), JBUI.scale(550))
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun getDimensionServiceKey() = "EFCorePowerTools.CompareResultDialog"

    /** CompareResultViewModel.AddComparisonResult. */
    private fun addComparisonResult(logs: List<CompareLogModel>) {
        fun create(log: CompareLogModel, level: Int) = CompareLogItem(
            level = level,
            type = log.type,
            state = log.state,
            name = log.name,
            attribute = log.attribute,
            expected = if (log.state == CompareState.Ok) null else log.expected,
            found = log.found,
        )

        for (ctx in logs) {
            completeLogs += create(ctx, 0)
            for (entity in ctx.subLogs) {
                completeLogs += create(entity, 1)
                for (property in entity.subLogs) completeLogs += create(property, 2)
            }
        }

        fun isOk(l: CompareLogModel) = l.state == CompareState.Ok

        // Filtered clone: drop OK properties (with only OK children) and fully OK contexts.
        val cloned = logs
            .filterNot { c -> isOk(c) && c.subLogs.all { s -> isOk(s) && s.subLogs.all { b -> isOk(b) } } }
            .map { ctx ->
                ctx to ctx.subLogs.map { entity ->
                    entity to entity.subLogs.filterNot { c -> isOk(c) && c.subLogs.all { s -> isOk(s) } }
                }
            }

        for ((ctx, entities) in cloned) {
            filteredLogs += create(ctx, 0)
            for ((entity, children) in entities.filter { it.second.isNotEmpty() }) {
                filteredLogs += create(entity, 1)
                for (property in children) filteredLogs += create(property, 2)
            }
        }
    }

    private fun fill(items: List<CompareLogItem>, expandLevel0: Boolean) {
        root.removeAllChildren()
        var level0: DefaultMutableTreeNode? = null
        var level1: DefaultMutableTreeNode? = null
        for (item in items) {
            val node = DefaultMutableTreeNode(item)
            when (item.level) {
                0 -> { root.add(node); level0 = node; level1 = null }
                1 -> { (level0 ?: root).add(node); level1 = node }
                else -> (level1 ?: level0 ?: root).add(node)
            }
        }
        model.reload()
        if (expandLevel0) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i) as DefaultMutableTreeNode
                table.tree.expandPath(TreePath(arrayOf<Any>(root, child)))
            }
        }
    }

    private fun textColumn(name: String, value: (CompareLogItem) -> String?): ColumnInfo<DefaultMutableTreeNode, String> =
        object : ColumnInfo<DefaultMutableTreeNode, String>(name) {
            override fun valueOf(item: DefaultMutableTreeNode?): String =
                (item?.userObject as? CompareLogItem)?.let(value) ?: ""
        }
}
