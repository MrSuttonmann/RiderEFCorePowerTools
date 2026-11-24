package com.efpt.rider.actions

import com.efpt.rider.project.DotNetProject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/** Port of PickProjectDialog: choose a project when more than one candidate exists. Call on the EDT. */
object ProjectPicker {
    fun pick(ideProject: Project, candidates: List<DotNetProject>, title: String = "Choose Project"): DotNetProject? = when (candidates.size) {
        0 -> null
        1 -> candidates[0]
        else -> PickProjectDialog(ideProject, candidates, title).let { if (it.showAndGet()) it.selected else null }
    }

    private class PickProjectDialog(ideProject: Project, candidates: List<DotNetProject>, title: String) : DialogWrapper(ideProject) {
        private val list = JBList(candidates).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectedIndex = 0
        }

        val selected: DotNetProject? get() = list.selectedValue

        init {
            this.title = title
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            row { label("Select the project to use:") }
            row { cell(JBScrollPane(list)).align(AlignX.FILL).align(AlignY.FILL) }.resizableRow()
        }.apply { preferredSize = java.awt.Dimension(420, 300) }
    }
}
