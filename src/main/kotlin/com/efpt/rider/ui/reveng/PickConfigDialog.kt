package com.efpt.rider.ui.reveng

import com.efpt.rider.handlers.reveng.RevEngLocale
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.io.File
import javax.swing.JComponent

/** Mirrors Common.Models.ConfigModel. */
data class ConfigModel(val configPath: String, val projectPath: String?) {
    /**
     * ConfigModel.DisplayName. Rider: shown relative to the project folder (the VS code strips the
     * project *file* path, which in practice leaves the full path).
     */
    val displayName: String
        get() {
            if (projectPath.isNullOrEmpty()) return File(configPath).name
            val dir = File(projectPath).let { if (it.isDirectory) it else it.parentFile }?.path ?: return configPath
            return if (configPath.startsWith(dir, ignoreCase = true)) configPath.substring(dir.length) else configPath
        }

    override fun toString() = displayName
}

/** Port of PickConfigDialog / PickConfigViewModel. Call on the EDT; returns the chosen config path or null. */
object PickConfigDialog {
    fun show(project: Project, configPaths: List<String>, projectPath: String): String? {
        val dialog = Dialog(project, configPaths.map { ConfigModel(it, projectPath) })
        return if (dialog.showAndGet()) dialog.selected?.configPath else null
    }

    private class Dialog(project: Project, configs: List<ConfigModel>) : DialogWrapper(project) {
        private val model = CollectionComboBoxModel(configs.toMutableList(), configs.firstOrNull()) // Loaded_Executed: select first
        private val combo = ComboBox(model).apply {
            renderer = object : com.intellij.ui.ColoredListCellRenderer<ConfigModel>() {
                override fun customizeCellRenderer(list: javax.swing.JList<out ConfigModel>, value: ConfigModel?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append(value?.displayName ?: "")
                }
            }
        }

        val selected: ConfigModel? get() = model.selected

        init {
            title = RevEngLocale.ChooseConfigurationFile
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            row { cell(combo).align(AlignX.FILL).resizableColumn() }
        }.apply { preferredSize = java.awt.Dimension(480, preferredSize.height) }

        override fun getPreferredFocusedComponent() = combo

        override fun doValidate(): ValidationInfo? = if (selected == null) ValidationInfo("", combo) else null
    }
}
