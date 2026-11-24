package com.efpt.rider.ui.reveng

import com.efpt.rider.handlers.reveng.RevEngLocale
import com.efpt.rider.model.SchemaInfo
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Port of PickSchemasDialog / PickSchemasViewModel: edit the list of schemas used to filter database objects.
 * Call on the EDT; returns the schemas (empty names removed) or null when cancelled.
 */
object PickSchemasDialog {
    fun show(parent: Component, schemas: List<SchemaInfo>): List<SchemaInfo>? {
        val dialog = Dialog(parent, schemas)
        return if (dialog.showAndGet()) dialog.result() else null
    }

    private class Dialog(parent: Component, schemas: List<SchemaInfo>) : DialogWrapper(parent, false) {
        private val nameColumn = object : ColumnInfo<SchemaInfo, String>("Schema") {
            override fun valueOf(item: SchemaInfo) = item.name ?: ""
            override fun isCellEditable(item: SchemaInfo) = true
            override fun setValue(item: SchemaInfo, value: String?) {
                item.name = value
            }
        }
        private val tableModel = ListTableModel(arrayOf<ColumnInfo<*, *>>(nameColumn), schemas.map { SchemaInfo(it.name) }.toMutableList())
        private val table = JBTable(tableModel).apply { tableHeader.isVisible = false; setShowGrid(false) }
        private var isRemovedData = false

        init {
            title = RevEngLocale.ChooseSchemas
            init()
            tableModel.addTableModelListener { initValidation() }
        }

        override fun createCenterPanel(): JComponent =
            ToolbarDecorator.createDecorator(table)
                .setAddAction {
                    // Add_Executed: add an empty record and select it for editing
                    stopEditing()
                    tableModel.addRow(SchemaInfo())
                    val row = tableModel.rowCount - 1
                    table.selectionModel.setSelectionInterval(row, row)
                    table.editCellAt(row, 0)
                    table.editorComponent?.requestFocusInWindow()
                }
                .setRemoveAction {
                    stopEditing()
                    val row = table.selectedRow
                    if (row >= 0) {
                        tableModel.removeRow(row)
                        isRemovedData = true
                        initValidation()
                    }
                }
                .disableUpDownActions()
                .createPanel().apply { preferredSize = Dimension(320, 260) }

        private fun stopEditing() {
            if (table.isEditing) table.cellEditor?.stopCellEditing()
        }

        override fun doOKAction() {
            stopEditing()
            super.doOKAction()
        }

        override fun doValidate() =
            // Ok_CanExecute: Schemas.Any() || isRemovedData
            if (tableModel.rowCount == 0 && !isRemovedData) com.intellij.openapi.ui.ValidationInfo("Add at least one schema") else null


        /** Ok_Executed: remove empty schemas. */
        fun result(): List<SchemaInfo> = tableModel.items.filter { !it.name.isNullOrEmpty() }.map { SchemaInfo(it.name) }
    }
}
