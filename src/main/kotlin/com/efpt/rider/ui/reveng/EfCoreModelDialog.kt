package com.efpt.rider.ui.reveng

import com.efpt.rider.handlers.reveng.RevEngLocale
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent

/**
 * The EfCoreModelDialog / Wiz3_EfCoreModelDialog content (ModelingOptionsViewModel / WizardDataViewModel page 3).
 * Edits [model] in place; call [commit] to copy the UI state into the model.
 */
class ModelingOptionsPanel(val model: ModelingOptionsModel = ModelingOptionsModel()) {
    private val modelNameField = JBTextField()
    private val namespaceField = JBTextField()
    private val outputPathField = JBTextField()
    private val generationModeCombo = ComboBox(
        DefaultComboBoxModel(arrayOf(RevEngLocale.EntityTypesAndContext, RevEngLocale.DbContextOnly, RevEngLocale.EntityTypesOnly)),
    )
    private val pluralizerCheck = JBCheckBox(RevEngLocale.PluralizeSingularizeObjectNames)
    private val databaseNamesCheck = JBCheckBox(RevEngLocale.UseNamesFromDatabase)
    private val dataAnnotationsCheck = JBCheckBox(RevEngLocale.UseDataAnnotations)
    private val handlebarsCheck = JBCheckBox(RevEngLocale.CustomizeUsingHandlebars)
    private val templateTypeModel = CollectionComboBoxModel<TemplateTypeItem>()
    private val templateTypeCombo = ComboBox(templateTypeModel)
    private val includeConnectionStringCheck = JBCheckBox(RevEngLocale.IncludeConnectionStringGeneratedCode)
    private val installNuGetCheck = JBCheckBox(RevEngLocale.InstallEFCoreProviderPackageInProject)
    private val advancedButton = JButton(RevEngLocale.Advanced)

    /** Title (GenerateEFCoreModelInProject) - set by [applyPresets]. */
    var title: String = ""
        private set

    /** MayIncludeConnectionString. */
    private var mayIncludeConnectionString = true

    /** Wizard: the Advanced button lives in the wizard's button bar, so it can be hidden here. */
    var showAdvancedButton = true

    val preferredFocus: JComponent get() = modelNameField

    init {
        generationModeCombo.addActionListener {
            // Model_PropertyChanged(SelectedToBeGenerated): EntityTypes only => do not install the provider package
            if (installNuGetCheck.isSelected && generationModeCombo.selectedIndex == 2) installNuGetCheck.isSelected = false
        }
        advancedButton.addActionListener { showAdvanced() }
    }

    val component: JComponent by lazy {
        panel {
            row { label(RevEngLocale.ContextName) }
            row { cell(modelNameField).align(AlignX.FILL) }
            row { label(RevEngLocale.Namespace) }
            row { cell(namespaceField).align(AlignX.FILL) }
            row { label(RevEngLocale.EntityTypesPath) }
            row { cell(outputPathField).align(AlignX.FILL) }
            row(RevEngLocale.WhatToGenerate) { cell(generationModeCombo) }
            group(RevEngLocale.Naming) {
                row { cell(pluralizerCheck) }
                row { cell(databaseNamesCheck) }
            }
            row { cell(dataAnnotationsCheck) }
            row {
                cell(handlebarsCheck)
                cell(templateTypeCombo).align(AlignX.RIGHT)
            }
            row { cell(includeConnectionStringCheck) }
            row { cell(installNuGetCheck) }
            row {
                cell(ActionLink(RevEngLocale.Help) { BrowserUtil.browse(RevEngLocale.HelpUrl) }).applyToComponent { toolTipText = RevEngLocale.UserGuide }
                cell(ActionLink(RevEngLocale.Rate) { BrowserUtil.browse(RevEngLocale.RateUrl) }).applyToComponent { toolTipText = RevEngLocale.RateTip }
                if (showAdvancedButton) cell(advancedButton).align(AlignX.RIGHT)
            }
        }
    }

    /** PublishTemplateTypes: fills the template list (once) and selects [selected]. */
    fun publishTemplateTypes(selected: Int, templates: List<TemplateTypeItem>) {
        if (templateTypeModel.size == 0) {
            templateTypeModel.add(templates)
            templateTypeModel.selectedItem = templates.firstOrNull { it.key == selected }
        }
    }

    /** ApplyPresets. */
    fun applyPresets(presets: ModelingOptionsModel) {
        model.installNuGetPackage = presets.installNuGetPackage
        model.selectedToBeGenerated = presets.selectedToBeGenerated
        model.selectedHandlebarsLanguage = presets.selectedHandlebarsLanguage
        model.includeConnectionString = presets.includeConnectionString
        model.useHandlebars = presets.useHandlebars
        model.usePluralizer = presets.usePluralizer
        model.useDatabaseNames = presets.useDatabaseNames
        model.namespace = presets.namespace
        model.outputPath = presets.outputPath
        model.outputContextPath = presets.outputContextPath
        model.useSchemaFolders = presets.useSchemaFolders
        model.modelNamespace = presets.modelNamespace
        model.contextNamespace = presets.contextNamespace
        model.modelName = presets.modelName
        model.useDataAnnotations = presets.useDataAnnotations
        model.useDbContextSplitting = presets.useDbContextSplitting
        model.projectName = presets.projectName
        model.dacpacPath = presets.dacpacPath
        model.mapSpatialTypes = presets.mapSpatialTypes
        model.mapHierarchyId = presets.mapHierarchyId
        model.mapNodaTimeTypes = presets.mapNodaTimeTypes
        model.useEf6Pluralizer = presets.useEf6Pluralizer
        model.useBoolPropertiesWithoutDefaultSql = presets.useBoolPropertiesWithoutDefaultSql
        model.useNoDefaultConstructor = presets.useNoDefaultConstructor
        model.useNoNavigations = presets.useNoNavigations
        model.useNullableReferences = presets.useNullableReferences
        model.useNoObjectFilter = presets.useNoObjectFilter
        model.useManyToManyEntity = presets.useManyToManyEntity
        model.useDateOnlyTimeOnly = presets.useDateOnlyTimeOnly
        model.useSchemaNamespaces = presets.useSchemaNamespaces
        model.t4TemplatePath = presets.t4TemplatePath

        // Model_PropertyChanged(DacpacPath)
        if (!model.dacpacPath.isNullOrBlank()) {
            mayIncludeConnectionString = false
            model.includeConnectionString = false
        } else {
            mayIncludeConnectionString = true
        }
        // Model_PropertyChanged(SelectedToBeGenerated)
        if (model.installNuGetPackage && model.selectedToBeGenerated == 2) model.installNuGetPackage = false

        title = RevEngLocale.GenerateEFCoreModelInProject.format(model.projectName)
        reset()
    }

    /** Model -> UI. */
    fun reset() {
        modelNameField.text = model.modelName ?: ""
        namespaceField.text = model.namespace ?: ""
        outputPathField.text = model.outputPath ?: ""
        generationModeCombo.selectedIndex = model.selectedToBeGenerated.coerceIn(0, 2)
        pluralizerCheck.isSelected = model.usePluralizer
        databaseNamesCheck.isSelected = model.useDatabaseNames
        dataAnnotationsCheck.isSelected = model.useDataAnnotations
        handlebarsCheck.isSelected = model.useHandlebars
        includeConnectionStringCheck.isSelected = model.includeConnectionString
        includeConnectionStringCheck.isEnabled = mayIncludeConnectionString
        installNuGetCheck.isSelected = model.installNuGetPackage
    }

    /** UI -> model (the WPF two-way bindings). */
    fun commit(): ModelingOptionsModel {
        model.modelName = modelNameField.text
        model.namespace = namespaceField.text
        model.outputPath = outputPathField.text
        model.selectedToBeGenerated = generationModeCombo.selectedIndex
        model.usePluralizer = pluralizerCheck.isSelected
        model.useDatabaseNames = databaseNamesCheck.isSelected
        model.useDataAnnotations = dataAnnotationsCheck.isSelected
        model.useHandlebars = handlebarsCheck.isSelected
        templateTypeModel.selected?.let { model.selectedHandlebarsLanguage = it.key } // SelectedTemplateType setter
        model.includeConnectionString = includeConnectionStringCheck.isSelected
        model.installNuGetPackage = installNuGetCheck.isSelected
        return model
    }

    /** ModelingOptionsViewModel.Ok_Executed validation. */
    fun validate(): ValidationInfo? {
        commit()
        val individualNamespacesSet = !model.contextNamespace.isNullOrEmpty() && !model.modelNamespace.isNullOrEmpty()
        if (model.namespace.isNullOrBlank() && !individualNamespacesSet) return ValidationInfo(RevEngLocale.NamespaceRequired, namespaceField)
        if (model.modelName.isNullOrBlank()) return ValidationInfo(RevEngLocale.ContextNameRequired, modelNameField)
        return null
    }

    /** Advanced_Executed. */
    fun showAdvanced() {
        commit()
        val result = AdvancedModelingOptionsDialog.show(component, model) ?: return
        model.applyAdvanced(result)
    }
}

/** Port of EfCoreModelDialog (IModelingOptionsDialog). Call on the EDT; returns the edited options or null. */
object EfCoreModelDialog {
    fun show(project: Project, presets: ModelingOptionsModel, templateTypes: List<TemplateTypeItem>, selectedTemplateType: Int): ModelingOptionsModel? {
        val optionsPanel = ModelingOptionsPanel()
        optionsPanel.applyPresets(presets)
        optionsPanel.publishTemplateTypes(selectedTemplateType, templateTypes)
        val dialog = Dialog(project, optionsPanel)
        return if (dialog.showAndGet()) optionsPanel.commit() else null
    }

    private class Dialog(project: Project, private val optionsPanel: ModelingOptionsPanel) : DialogWrapper(project) {
        init {
            title = optionsPanel.title
            init()
        }

        override fun createCenterPanel(): JComponent = optionsPanel.component

        override fun getPreferredFocusedComponent() = optionsPanel.preferredFocus

        override fun doValidate() = optionsPanel.validate()
    }
}
