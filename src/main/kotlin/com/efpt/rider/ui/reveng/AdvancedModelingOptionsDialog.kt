package com.efpt.rider.ui.reveng

import com.efpt.rider.handlers.reveng.RevEngLocale
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.awt.Component
import javax.swing.JComponent

/**
 * Port of AdvancedModelingOptionsDialog / AdvancedModelingOptionsViewModel. Call on the EDT; returns a
 * copy of [presets] with the advanced options edited, or null when cancelled.
 */
object AdvancedModelingOptionsDialog {
    fun show(parent: Component, presets: ModelingOptionsModel): ModelingOptionsModel? {
        val model = presets.copy()
        val dialog = Dialog(parent, model)
        return if (dialog.showAndGet()) model else null
    }

    private class Dialog(parent: Component, private val m: ModelingOptionsModel) : DialogWrapper(parent, false) {
        init {
            title = RevEngLocale.Advanced
            init()
        }

        private lateinit var dialogPanel: com.intellij.openapi.ui.DialogPanel

        override fun doOKAction() {
            dialogPanel.apply()
            super.doOKAction()
        }

        override fun createCenterPanel(): JComponent = panel {
            group(RevEngLocale.CodeGeneration) {
                row { checkBox(RevEngLocale.UseManyToManyEntity).bindSelected(m::useManyToManyEntity) }
                row { checkBox(RevEngLocale.UseNullableReferenceTypes).bindSelected(m::useNullableReferences) }
                row { checkBox(RevEngLocale.RemoveSQLDefaultFromBoolColumns).bindSelected(m::useBoolPropertiesWithoutDefaultSql) }
                row { checkBox(RevEngLocale.RemoveNavigationsFromEntities).bindSelected(m::useNoNavigations) }
                row { checkBox(RevEngLocale.RemoveDefaultConstructor).bindSelected(m::useNoDefaultConstructor) }
                row { checkBox(RevEngLocale.AlwaysIncludeAllDatabaseObjects).bindSelected(m::useNoObjectFilter) }
            }
            group(RevEngLocale.FileLayout) {
                row { label(RevEngLocale.EntityTypesSubNamespace) }
                row { textField().align(AlignX.FILL).bindText({ m.modelNamespace ?: "" }, { m.modelNamespace = it }) }
                row { label(RevEngLocale.DbContextPath) }
                row { textField().align(AlignX.FILL).bindText({ m.outputContextPath ?: "" }, { m.outputContextPath = it }) }
                row { label(RevEngLocale.DbContextSubNamespace) }
                row { textField().align(AlignX.FILL).bindText({ m.contextNamespace ?: "" }, { m.contextNamespace = it }) }
                row { label(RevEngLocale.T4TemplatePath) }
                row { textField().align(AlignX.FILL).bindText({ m.t4TemplatePath ?: "" }, { m.t4TemplatePath = it }) }
                row { checkBox(RevEngLocale.SplitDbContextIntoConfigurationClasses).bindSelected(m::useDbContextSplitting) }
                row { checkBox(RevEngLocale.UseSchemaFolders).bindSelected(m::useSchemaFolders) }
                row { checkBox(RevEngLocale.UseSchemaNamespaces).bindSelected(m::useSchemaNamespaces) }
            }
            group(RevEngLocale.Mapping) {
                row { checkBox(RevEngLocale.MapSpatialTypes).bindSelected(m::mapSpatialTypes) }
                row { checkBox(RevEngLocale.MapHierarchyId).bindSelected(m::mapHierarchyId) }
                row { checkBox("Map DateOnly and TimeOnly").bindSelected(m::useDateOnlyTimeOnly) }
                row { checkBox(RevEngLocale.MapNodaTimeTypes).bindSelected(m::mapNodaTimeTypes) }
                row { checkBox(RevEngLocale.UseEF6Pluralizer).bindSelected(m::useEf6Pluralizer) }
            }
        }.also { dialogPanel = it }
    }
}
