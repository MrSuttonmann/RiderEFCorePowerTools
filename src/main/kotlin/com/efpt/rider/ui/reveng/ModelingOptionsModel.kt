package com.efpt.rider.ui.reveng

/** Mirrors Contracts.ViewModels.TemplateTypeItem: Key = SelectedHandlebarsLanguage value, Value = caption. */
data class TemplateTypeItem(val key: Int, val value: String) {
    override fun toString() = value
}

/** Mirrors Common.Models.ModelingOptionsModel (the EfCoreModelDialog / AdvancedModelingOptionsDialog payload). */
data class ModelingOptionsModel(
    var installNuGetPackage: Boolean = false,
    var selectedToBeGenerated: Int = 0,
    var selectedHandlebarsLanguage: Int = 0,
    var includeConnectionString: Boolean = false,
    var useHandlebars: Boolean = false,
    var usePluralizer: Boolean = false,
    var useDatabaseNames: Boolean = false,
    var namespace: String? = null,
    var outputPath: String? = null,
    var outputContextPath: String? = null,
    var useSchemaFolders: Boolean = false,
    var modelNamespace: String? = null,
    var contextNamespace: String? = null,
    var modelName: String? = null,
    var useDataAnnotations: Boolean = false,
    var useDbContextSplitting: Boolean = false,
    var projectName: String? = null,
    var dacpacPath: String? = null,
    var mapSpatialTypes: Boolean = false,
    var mapHierarchyId: Boolean = false,
    var mapNodaTimeTypes: Boolean = false,
    var useEf6Pluralizer: Boolean = false,
    var useBoolPropertiesWithoutDefaultSql: Boolean = false,
    var useNoDefaultConstructor: Boolean = false,
    var useNoNavigations: Boolean = false,
    var useNullableReferences: Boolean = false,
    var useNoObjectFilter: Boolean = false,
    var useManyToManyEntity: Boolean = false,
    var useDateOnlyTimeOnly: Boolean = false,
    var useSchemaNamespaces: Boolean = false,
    var t4TemplatePath: String? = null,
) {
    /** The "Advanced" subset copied back by ModelingOptionsViewModel.Advanced_Executed. */
    fun applyAdvanced(a: ModelingOptionsModel) {
        useDbContextSplitting = a.useDbContextSplitting
        mapSpatialTypes = a.mapSpatialTypes
        mapHierarchyId = a.mapHierarchyId
        mapNodaTimeTypes = a.mapNodaTimeTypes
        useEf6Pluralizer = a.useEf6Pluralizer
        useBoolPropertiesWithoutDefaultSql = a.useBoolPropertiesWithoutDefaultSql
        useNoDefaultConstructor = a.useNoDefaultConstructor
        useNullableReferences = a.useNullableReferences
        useNoObjectFilter = a.useNoObjectFilter
        useNoNavigations = a.useNoNavigations
        useSchemaFolders = a.useSchemaFolders
        useManyToManyEntity = a.useManyToManyEntity
        useDateOnlyTimeOnly = a.useDateOnlyTimeOnly
        contextNamespace = a.contextNamespace
        outputContextPath = a.outputContextPath
        modelNamespace = a.modelNamespace
        useSchemaNamespaces = a.useSchemaNamespaces
        t4TemplatePath = a.t4TemplatePath
    }
}
