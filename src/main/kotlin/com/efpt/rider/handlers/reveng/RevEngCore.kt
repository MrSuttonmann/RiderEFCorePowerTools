package com.efpt.rider.handlers.reveng

import com.efpt.rider.connections.DataConnections
import com.efpt.rider.json.DcJson
import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.NuGetPackage
import com.efpt.rider.model.ObjectType
import com.efpt.rider.model.ReverseEngineerOptions
import com.efpt.rider.model.ReverseEngineerResult
import com.efpt.rider.model.ReverseEngineerUserOptions
import com.efpt.rider.model.Schema
import com.efpt.rider.model.TableModel
import com.efpt.rider.project.DotNetProject
import com.efpt.rider.project.SqlProjHelper
import com.efpt.rider.settings.EfptSettings
import com.efpt.rider.tools.BundledTools
import com.efpt.rider.tools.EfRevEngLauncher
import com.efpt.rider.ui.CodeGenerationItem
import com.efpt.rider.ui.PickServerDatabaseDialog
import com.efpt.rider.ui.PickServerDatabaseRequest
import com.efpt.rider.ui.reveng.ModelingOptionsModel
import com.efpt.rider.util.PathHelper
import com.efpt.rider.util.Ui
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** File and state handling shared by [ReverseEngineerHandler] and [RevEngWizardHandler]. */
internal object RevEngCore {
    private val LOG = logger<RevEngCore>()
    private const val CONFIG_FILE_NAME = DotNetProject.CLI_CONFIG_FILE_NAME

    // ------------------------------------------------------------------ option files

    /** ReverseEngineerOptionsExtensions.TryRead. */
    fun readOptions(optionsPath: String): ReverseEngineerOptions? {
        val file = File(optionsPath)
        if (!file.exists()) return null
        if (optionsPath.endsWith(CONFIG_FILE_NAME, ignoreCase = true)) return null
        if (File("$optionsPath.ignore").exists()) return null
        return try {
            ReverseEngineerOptions.fromJson(DcJson.readFile(file.toPath()).asJsonObject)
        } catch (e: Exception) {
            LOG.warn(e)
            throw IllegalArgumentException("Could not read options from file: $optionsPath")
        }
    }

    /** ReverseEngineerUserOptionsExtensions.TryRead. */
    fun readUserOptions(optionsPath: String, projectDirectory: String): ReverseEngineerUserOptions? {
        val file = File("$optionsPath.user")
        if (!file.exists()) return null
        return try {
            val options = ReverseEngineerUserOptions.fromJson(DcJson.readFile(file.toPath()).asJsonObject)
            if (!options.uiHint.isNullOrEmpty()) options.uiHint = PathHelper.getFullPathForSqlProj(options.uiHint!!, projectDirectory)
            options
        } catch (e: Exception) {
            LOG.warn(e)
            null
        }
    }

    /** ReverseEngineerUserOptionsExtensions.Write. */
    private fun writeUserOptions(options: ReverseEngineerUserOptions, projectDirectory: String): String {
        val hint = options.uiHint
        if (!hint.isNullOrEmpty() && PathHelper.isDefinitionPath(hint)) {
            options.uiHint = PathHelper.setRelativePathForSqlProj(hint, projectDirectory)
        }
        return DcJson.write(options.toJson())
    }

    /** CustomNameOptionsExtensions.TryRead: (renaming rules or null, renaming file path). */
    fun readRenaming(optionsCustomNamePath: String, optionsPath: String): Pair<List<Schema>?, String> {
        var path = optionsCustomNamePath
        if (!optionsPath.endsWith("efpt.config.json", ignoreCase = true)) {
            val specificName = File(optionsPath).name.replace(".config.", ".renaming.")
            val specificPath = File(File(optionsPath).parentFile, specificName)
            if (specificPath.exists()) path = specificPath.path
        }
        if (!File(path).exists()) return null to path
        if (File("$path.ignore").exists()) return null to path
        return Schema.listFromJson(String(File(path).readBytes(), StandardCharsets.UTF_8)) to path
    }

    // ------------------------------------------------------------------ session

    /** The state ReverseEngineerCodeFirstAsync keeps for one run (incl. the per-object settings restored on save). */
    class Session(val project: DotNetProject, val optionsPath: String) {
        lateinit var options: ReverseEngineerOptions
        var userOptions: ReverseEngineerUserOptions? = null
        var namingOptionsAndPath: Pair<List<Schema>?, String> = null to ""
        var newOptions = false
        var legacyDiscoveryObjects: List<String> = emptyList()
        var mappedTypes: Map<String, String> = emptyMap()
        var generateEmptyResultTypeObjects: List<String> = emptyList()

        val ide get() = project.ideProject
        val projectDirectory: String get() = project.directory.toString()
    }

    /**
     * The first part of ReverseEngineerCodeFirstAsync(project, optionsPath, ...): reads the config, .user and
     * renaming files. Returns the message about property renaming (or null). Call off the EDT.
     */
    fun load(session: Session, uiHint: String?): String? {
        val project = session.project
        val optionsPath = session.optionsPath
        var message: String? = null
        val renamingPath = project.getRenamingPath(optionsPath)
        val referenceRenamingPath = project.getRenamingPath(optionsPath, true)
        if (referenceRenamingPath.isNotEmpty() && File(referenceRenamingPath).exists()) {
            message = "Property renaming (experimental) is no longer available. See GitHub issue #2171."
        }

        session.namingOptionsAndPath = readRenaming(renamingPath, optionsPath)
        var options = readOptions(optionsPath)
        var userOptions = readUserOptions(optionsPath, session.projectDirectory)

        if (options == null) {
            options = ReverseEngineerOptions().apply {
                projectRootNamespace = project.getProperty("RootNamespace")
                outputPath = "Models"
            }
            session.newOptions = true
        }
        if (userOptions == null) {
            userOptions = ReverseEngineerUserOptions(uiHint ?: options.uiHint)
        }
        options.uiHint = uiHint ?: userOptions.uiHint

        val tables = options.tables
        session.legacyDiscoveryObjects = tables?.filter { it.useLegacyResultSetDiscovery }?.map { it.name } ?: emptyList()
        session.mappedTypes = tables?.filter { !it.mappedType.isNullOrEmpty() && it.objectType == ObjectType.Procedure }
            ?.associate { it.name to it.mappedType!! } ?: emptyMap()
        session.generateEmptyResultTypeObjects = tables?.filter { it.objectType == ObjectType.Procedure && it.generateEmptyResultType }
            ?.map { it.name } ?: emptyList()

        options.projectPath = session.projectDirectory
        options.optionsPath = File(optionsPath).parent

        session.options = options
        session.userOptions = userOptions
        return message
    }

    // ------------------------------------------------------------------ connections

    /** ChooseDataBaseConnectionByUiHintAsync. Call off the EDT. */
    fun chooseDataBaseConnectionByUiHint(session: Session): Boolean {
        val options = session.options
        val databaseList = DataConnections.getDataConnections(session.ide)
        databaseList.values.firstOrNull { it.connectionName == options.uiHint }?.let {
            options.connectionString = it.connectionString
            options.databaseType = it.databaseType
            return true
        }
        val dacpacList = SqlProjHelper.getDacpacProjectsInActiveSolution(session.ide)
        if (!dacpacList.isNullOrEmpty() && !options.uiHint.isNullOrEmpty()) {
            dacpacList.filter { it.isNotBlank() }.firstOrNull { it.equals(options.uiHint, ignoreCase = true) }?.let {
                options.dacpac = it
                return true
            }
        }
        return false
    }

    /** Inputs for the connection page / dialog (the PublishXxx calls of ChooseDataBaseConnectionAsync). Call off the EDT. */
    class ConnectionChoices(
        val connections: List<DatabaseConnectionModel>,
        val definitions: List<DatabaseConnectionModel>,
        val schemas: List<com.efpt.rider.model.SchemaInfo>?,
        val usedMode: com.efpt.rider.model.CodeGenerationMode,
        val allowedVersions: List<CodeGenerationItem>,
        val uiHint: String?,
    )

    fun connectionChoices(session: Session): ConnectionChoices {
        val options = session.options
        val connections = DataConnections.getDataConnections(session.ide).values.map { it.copy() }
        val definitions = SqlProjHelper.getDacpacProjectsInActiveSolution(session.ide)?.map {
            DatabaseConnectionModel(filePath = it, databaseType = DatabaseType.SQLServerDacpac, origin = com.efpt.rider.model.ConnectionOrigin.Definition)
        } ?: emptyList()
        val schemas = if (options.filterSchemas && !options.schemas.isNullOrEmpty()) options.schemas!!.toList() else null
        val (usedMode, allowedVersions) = ReverseEngineerHelper.calculateAllowedVersions(options.codeGenerationMode, session.project.efCoreVersionHint())
        return ConnectionChoices(connections, definitions, schemas, usedMode, allowedVersions, options.uiHint?.takeIf { it.isNotEmpty() })
    }

    /** ChooseDataBaseConnectionAsync (non-wizard): shows PickServerDatabaseDialog. Call off the EDT. */
    fun chooseDataBaseConnection(session: Session): Boolean {
        val options = session.options
        val choices = connectionChoices(session)
        if (choices.allowedVersions.isEmpty()) {
            Ui.showError(session.ide, ".NET 7 and earlier is not supported.")
            return false
        }
        val result = Ui.invokeAndWait {
            PickServerDatabaseDialog.show(
                session.ide,
                PickServerDatabaseRequest(
                    connections = choices.connections,
                    definitions = choices.definitions,
                    schemas = choices.schemas,
                    codeGenerationMode = choices.usedMode,
                    allowedVersions = choices.allowedVersions,
                    uiHint = choices.uiHint,
                    title = RevEngLocale.ChooseDatabaseConnection,
                ),
            )
        } ?: return false

        options.codeGenerationMode = result.codeGenerationMode
        options.filterSchemas = result.filterSchemas
        options.schemas = if (options.filterSchemas) result.schemas?.toMutableList() else null
        options.uiHint = result.uiHint
        options.dacpac = result.connection?.filePath
        result.connection?.let {
            options.connectionString = it.connectionString
            options.databaseType = it.databaseType
        }
        return true
    }

    /**
     * GetDatabaseInfoAsync: builds the SQL project when a .dacpac / SQL project is used. Returns null (after
     * showing a message, unless [errorSink] is given) when the provider is unsupported. Call off the EDT.
     */
    fun getDatabaseInfo(session: Session, errorSink: ((String) -> Unit)? = null): DatabaseConnectionModel? {
        val options = session.options
        val dbInfo = DatabaseConnectionModel()
        if (!options.connectionString.isNullOrEmpty()) {
            dbInfo.connectionString = options.connectionString
            dbInfo.databaseType = options.databaseType
        }
        if (!options.dacpac.isNullOrEmpty()) {
            dbInfo.databaseType = DatabaseType.SQLServerDacpac
            dbInfo.connectionString = "Data Source=(local);Initial Catalog=${File(options.dacpac!!).nameWithoutExtension};Integrated Security=true;"
            options.connectionString = dbInfo.connectionString
            options.databaseType = dbInfo.databaseType

            options.dacpac = SqlProjHelper.buildSqlProject(session.ide, options.dacpac!!, mustBuild = true)
            if (options.dacpac.isNullOrEmpty()) {
                if (errorSink != null) errorSink(RevEngLocale.UnableToBuildSelectedDatabaseProject) else Ui.showMessage(session.ide, RevEngLocale.UnableToBuildSelectedDatabaseProject)
                return null
            }
            dbInfo.filePath = options.dacpac
        }
        if (dbInfo.databaseType == DatabaseType.Undefined) {
            if (errorSink != null) errorSink(RevEngLocale.UnsupportedProvider) else Ui.showError(session.ide, RevEngLocale.UnsupportedProvider)
            return null
        }
        return dbInfo
    }

    /** GetDacpacTablesAsync / GetTablesAsync (TableListBuilder). Call off the EDT. */
    fun getTables(options: ReverseEngineerOptions, dbInfo: DatabaseConnectionModel): List<TableModel> {
        val schemas = options.schemas?.toList()
        val launcher = EfRevEngLauncher(options.codeGenerationMode)
        val mergeDacpacs = EfptSettings.instance.mergeDacpacs
        return if (!options.dacpac.isNullOrEmpty()) {
            launcher.getTables(options.dacpac!!, DatabaseType.SQLServerDacpac, schemas, mergeDacpacs)
        } else {
            val cs = dbInfo.connectionString
            require(!cs.isNullOrEmpty()) { "connectionString" }
            launcher.getTables(cs, dbInfo.databaseType, schemas, mergeDacpacs)
        }
    }

    // ------------------------------------------------------------------ modeling options

    /** The presets built by GetModelOptionsAsync (also sets the default context class name). */
    fun modelPresets(options: ReverseEngineerOptions, projectName: String): ModelingOptionsModel {
        val classBasis = ReverseEngineerHelper.getDatabaseName(options.connectionString, options.databaseType)
        if (options.contextClassName.isNullOrEmpty()) options.contextClassName = classBasis + "Context"
        return ModelingOptionsModel(
            installNuGetPackage = options.installNuGetPackage,
            modelName = options.contextClassName,
            projectName = projectName,
            namespace = options.projectRootNamespace,
            dacpacPath = options.dacpac,
            useDataAnnotations = !options.useFluentApiOnly,
            useDatabaseNames = options.useDatabaseNames,
            usePluralizer = options.useInflector,
            useDbContextSplitting = options.useDbContextSplitting,
            useHandlebars = options.useHandleBars || options.useT4 || options.useT4Split,
            selectedHandlebarsLanguage = options.selectedHandlebarsLanguage,
            includeConnectionString = options.includeConnectionString,
            outputPath = options.outputPath,
            outputContextPath = options.outputContextPath,
            useSchemaFolders = options.useSchemaFolders,
            modelNamespace = options.modelNamespace,
            contextNamespace = options.contextNamespace,
            selectedToBeGenerated = options.selectedToBeGenerated,
            useEf6Pluralizer = options.useLegacyPluralizer,
            mapSpatialTypes = options.useSpatial,
            mapHierarchyId = options.useHierarchyId,
            mapNodaTimeTypes = options.useNodaTime,
            useBoolPropertiesWithoutDefaultSql = options.useBoolPropertiesWithoutDefaultSql,
            useNullableReferences = options.useNullableReferences,
            useNoObjectFilter = options.useNoObjectFilter,
            useNoNavigations = options.useNoNavigations,
            useNoDefaultConstructor = options.useNoDefaultConstructor,
            useManyToManyEntity = options.useManyToManyEntity,
            useDateOnlyTimeOnly = options.useDateOnlyTimeOnly,
            useSchemaNamespaces = options.useSchemaNamespaces,
            t4TemplatePath = options.t4TemplatePath,
        )
    }

    /** GetModelOptionsPostDialog. */
    fun applyModelResult(options: ReverseEngineerOptions, m: ModelingOptionsModel) {
        val isHandleBarsLanguage = m.selectedHandlebarsLanguage == 0 || m.selectedHandlebarsLanguage == 1
        options.useHandleBars = m.useHandlebars && isHandleBarsLanguage
        options.selectedHandlebarsLanguage = m.selectedHandlebarsLanguage
        options.useT4 = m.useHandlebars && !isHandleBarsLanguage
        // Rider: VS only ever sets UseT4Split to true here (the wizard resets it first, the legacy dialog
        // never does, leaving a stale "split" flag after switching template type); it is recomputed here.
        options.useT4Split = m.useHandlebars && m.selectedHandlebarsLanguage == 4
        if (options.useT4Split) options.useT4 = false

        options.installNuGetPackage = m.installNuGetPackage
        options.useFluentApiOnly = !m.useDataAnnotations
        options.contextClassName = m.modelName
        options.outputPath = m.outputPath
        options.outputContextPath = m.outputContextPath
        options.contextNamespace = m.contextNamespace
        options.useSchemaFolders = m.useSchemaFolders
        options.modelNamespace = m.modelNamespace
        options.projectRootNamespace = m.namespace
        options.useDatabaseNames = m.useDatabaseNames
        options.useInflector = m.usePluralizer
        options.useLegacyPluralizer = m.useEf6Pluralizer
        options.useSpatial = m.mapSpatialTypes
        options.useHierarchyId = m.mapHierarchyId
        options.useNodaTime = m.mapNodaTimeTypes
        options.useDbContextSplitting = m.useDbContextSplitting
        options.includeConnectionString = m.includeConnectionString
        options.selectedToBeGenerated = m.selectedToBeGenerated
        options.useBoolPropertiesWithoutDefaultSql = m.useBoolPropertiesWithoutDefaultSql
        options.useNullableReferences = m.useNullableReferences
        options.useNoObjectFilter = m.useNoObjectFilter
        options.useNoNavigations = m.useNoNavigations
        options.useNoDefaultConstructor = m.useNoDefaultConstructor
        options.useManyToManyEntity = m.useManyToManyEntity
        options.useDateOnlyTimeOnly = m.useDateOnlyTimeOnly
        options.useSchemaNamespaces = m.useSchemaNamespaces
        options.t4TemplatePath = m.t4TemplatePath
    }

    fun neededPackages(project: DotNetProject, options: ReverseEngineerOptions): List<NuGetPackage> =
        project.getNeededPackages(options, EfptSettings.instance.discoverMultipleResultSets)

    // ------------------------------------------------------------------ save

    /** SaveOptionsAsync. Call off the EDT. */
    fun saveOptions(session: Session, userOptions: ReverseEngineerUserOptions?, renaming: Pair<List<Schema>?, String?>) {
        val optionsPath = session.optionsPath
        val options = session.options
        if (optionsPath.endsWith(CONFIG_FILE_NAME, ignoreCase = true)) return

        val optionsFile = File(optionsPath)
        if (optionsFile.exists() && !optionsFile.canWrite()) {
            Ui.showError(session.ide, "Unable to save options, the file is readonly: $optionsPath")
            return
        }

        if (!File("$optionsPath.ignore").exists()) {
            if (userOptions != null) {
                userOptions.uiHint = options.uiHint
                DcJson.writeFile(Path.of("$optionsPath.user"), writeUserOptions(userOptions, session.projectDirectory))
            }

            options.uiHint = null

            for (table in options.tables ?: emptyList()) {
                if (session.legacyDiscoveryObjects.contains(table.name)) table.useLegacyResultSetDiscovery = true
                session.mappedTypes[table.name]?.let { table.mappedType = it }
                if (table.objectType == ObjectType.Procedure && session.generateEmptyResultTypeObjects.contains(table.name)) {
                    table.generateEmptyResultType = true
                }
            }

            // Rider: the product version is the plugin version (VS uses the VSIX assembly file version).
            val productVersion = parseVersion(BundledTools.pluginVersion)
            if (productVersion != null) {
                val minimum = options.minimumProductVersion?.let { parseVersion(it) }
                if (options.minimumProductVersion == null || (minimum != null && compareVersions(minimum, productVersion) < 0)) {
                    options.minimumProductVersion = productVersion.take(3).let { v -> (v + List(3 - v.size) { 0 }).joinToString(".") }
                }
            }

            DcJson.writeFile(optionsFile.toPath(), options.write())
            Ui.refreshPaths(optionsPath)
        }

        val renamingRules = renaming.first
        val renamingPath = renaming.second
        if (renamingRules != null && renamingPath != null && !File("$renamingPath.ignore").exists() && renamingRules.isNotEmpty()) {
            val renamingFile = File(renamingPath)
            if (renamingFile.exists() && !renamingFile.canWrite()) {
                Ui.showError(session.ide, "Unable to save renaming options, the file is readonly: $renamingPath")
                return
            }
            DcJson.writeFile(renamingFile.toPath(), Schema.listToJson(renamingRules))
            Ui.refreshPaths(renamingPath)
        }
    }

    private fun parseVersion(v: String): List<Int>? =
        runCatching { v.trim().split('.').map { it.takeWhile(Char::isDigit).toInt() } }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = (a.getOrElse(i) { -1 }).compareTo(b.getOrElse(i) { -1 })
            if (c != 0) return c
        }
        return 0
    }

    // ------------------------------------------------------------------ NuGet

    /**
     * InstallNuGetPackagesAsync. [allowNetStandard]: the wizard handler also installs for .NET Standard projects.
     * Rider: packages are installed with `dotnet add package` and awaited (VS starts the process and moves on).
     */
    fun installNuGetPackages(
        project: DotNetProject,
        onlyGenerate: Boolean,
        options: ReverseEngineerOptions,
        forceEdit: Boolean,
        allowNetStandard: Boolean,
        indicator: ProgressIndicator?,
        showStatus: Boolean = true,
    ) {
        if (!options.installNuGetPackage || !(!onlyGenerate || forceEdit)) return
        if (!(project.isNet80OrHigher() || (allowNetStandard && project.isNetStandard()))) return
        val packagesToInstall = neededPackages(project, options).filter { it.databaseTypes.contains(options.databaseType) && !it.installed }
        if (packagesToInstall.isEmpty()) return
        if (showStatus) Ui.status(project.ideProject, RevEngLocale.InstallingEFCoreProviderPackage)
        indicator?.text = RevEngLocale.InstallingEFCoreProviderPackage
        for (pkg in packagesToInstall) {
            val result = project.installPackage(pkg.packageId, pkg.version)
            if (!result.success) Ui.logError(project.ideProject, listOf("Unable to install ${pkg.packageId} ${pkg.version}", result.output), null)
        }
    }

    /** missingProviderPackage calculation of ReverseEngineerCodeFirstAsync. */
    fun missingProviderPackage(options: ReverseEngineerOptions, neededPackages: List<NuGetPackage>): String? {
        val missing = neededPackages.firstOrNull { it.databaseTypes.contains(options.databaseType) && it.isMainProviderPackage && !it.installed }?.packageId
        return if (options.installNuGetPackage || options.selectedToBeGenerated == 2) null else missing
    }

    // ------------------------------------------------------------------ generate

    /**
     * GenerateFilesAsync. Returns the wizard status page text ("Successfully completed" or the issue list).
     * [isCalledByWizard] false shows the result as a warning dialog (ReverseEngineerHandler). Call off the EDT.
     */
    fun generateFiles(
        project: DotNetProject,
        options: ReverseEngineerOptions,
        missingProviderPackage: String?,
        onlyGenerate: Boolean,
        packages: List<NuGetPackage>,
        isCalledByWizard: Boolean,
        indicator: ProgressIndicator?,
    ): String {
        val ide = project.ideProject
        fun progress(step: Int) {
            indicator?.text = RevEngLocale.GeneratingCode
            if (indicator != null) {
                indicator.isIndeterminate = false
                indicator.fraction = step / 4.0
            }
            if (!isCalledByWizard || step == 4) Ui.status(ide, RevEngLocale.GeneratingCode)
        }

        progress(1)
        val start = System.currentTimeMillis()

        if (options.useHandleBars || ((options.useT4 || options.useT4Split) && options.t4TemplatePath.isNullOrEmpty())) {
            val result = ReverseEngineerHelper.dropTemplates(
                options.optionsPath ?: "",
                options.projectPath ?: "",
                options.codeGenerationMode,
                options.useHandleBars,
                options.selectedHandlebarsLanguage,
            )
            if (result.isNotEmpty()) Ui.invokeAndWait { com.intellij.openapi.ui.Messages.showWarningDialog(ide, result, Ui.TITLE) }
            Ui.refreshPaths(*listOfNotNull(options.optionsPath, options.projectPath).toTypedArray())
        }

        options.useNullableReferences = !project.isNetStandard() && options.useNullableReferences

        progress(2)
        val revEngResult = EfRevEngLauncher.launchExternalRunner(options, options.codeGenerationMode)
        progress(3)

        // Rider: files written by the external process must be refreshed before they can be opened.
        Ui.refreshPaths(*listOfNotNull(options.projectPath, options.optionsPath, revEngResult.contextFilePath).toTypedArray())

        if ((options.selectedToBeGenerated == 0 || options.selectedToBeGenerated == 1) &&
            EfptSettings.instance.openGeneratedDbContext && !onlyGenerate
        ) {
            showReadMe(project, options, packages, revEngResult)
        }

        progress(4)
        val elapsed = (System.currentTimeMillis() - start) / 1000
        val (errors, warnings) = ReverseEngineerHelper.reportRevEngErrors(revEngResult, missingProviderPackage)
        Ui.status(ide, RevEngLocale.ReverseEngineerCompleted.format("%02d:%02d".format(elapsed / 60, elapsed % 60)))

        val statusMessage = StringBuilder()
        if (!ReverseEngineerHelper.isSuccessText(errors)) {
            if (isCalledByWizard) {
                warnings.forEach { statusMessage.append("⚠️ ").append(it).append(System.lineSeparator()) }
                revEngResult.entityErrors.forEach { statusMessage.append("❌ ").append(it).append(System.lineSeparator()) }
            } else {
                Ui.showWarning(ide, errors)
            }
        } else if (isCalledByWizard) {
            statusMessage.append("✅ ").append(RevEngLocale.ModelGeneratedSuccesfully).append(System.lineSeparator())
        }

        if (revEngResult.entityErrors.isNotEmpty()) Ui.logError(ide, revEngResult.entityErrors, null)
        if (warnings.isNotEmpty()) Ui.logError(ide, warnings, null)

        val text = statusMessage.toString()
        return text.ifEmpty { "Successfully completed" }
    }

    private fun showReadMe(project: DotNetProject, options: ReverseEngineerOptions, packages: List<NuGetPackage>, revEngResult: ReverseEngineerResult) {
        val template = ReverseEngineerHelper.readMeTemplate()
        var finalText = if (packages.isNotEmpty()) {
            ReverseEngineerHelper.getReadMeText(options, template, packages)
        } else {
            ReverseEngineerHelper.getReadMeText(options, template)
        }
        val readmePath = File(BundledTools.tempDir.toFile(), ReverseEngineerHelper.README_NAME)
        finalText = ReverseEngineerHelper.addResultToFinalText(finalText, revEngResult)
        readmePath.writeText(finalText, StandardCharsets.UTF_8)
        Ui.refreshPaths(readmePath.path)

        val ide = project.ideProject
        val context = revEngResult.contextFilePath
        if (revEngResult.hasIssues) {
            if (!context.isNullOrEmpty()) Ui.openFile(ide, context)
            Ui.openPreview(ide, readmePath.path)
        } else {
            Ui.openPreview(ide, readmePath.path)
            if (!context.isNullOrEmpty()) Ui.openFile(ide, context)
        }
    }

    // ------------------------------------------------------------------ post-run hook

    /**
     * Runs "efpt.postrun.cmd" next to the config file (VS: Process.Start, not awaited).
     * Rider: on Windows the .cmd file is run through cmd.exe; on other operating systems an
     * "efpt.postrun.sh" file is run with sh instead (a .cmd file cannot run there).
     */
    fun runPostRun(ide: com.intellij.openapi.project.Project, optionsPath: String) {
        val folder = File(optionsPath).parentFile ?: return
        try {
            if (SystemInfo.isWindows) {
                val postRunFile = File(folder, "efpt.postrun.cmd")
                if (postRunFile.exists()) {
                    GeneralCommandLine("cmd.exe", "/c", postRunFile.path).withWorkingDirectory(folder.toPath()).createProcess()
                }
            } else {
                val postRunFile = File(folder, "efpt.postrun.sh")
                if (postRunFile.exists()) {
                    GeneralCommandLine("sh", postRunFile.path).withWorkingDirectory(folder.toPath()).createProcess()
                }
            }
        } catch (e: Exception) {
            Ui.logError(ide, listOf("Unable to run the post-run file in $folder"), e)
        }
    }
}
