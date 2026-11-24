package com.efpt.rider.ui.reveng

import com.efpt.rider.handlers.reveng.RevEngCore
import com.efpt.rider.handlers.reveng.RevEngLocale
import com.efpt.rider.handlers.reveng.ReverseEngineerHelper
import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.ReverseEngineerUserOptions
import com.efpt.rider.model.SerializationTableModel
import com.efpt.rider.project.DotNetProject
import com.efpt.rider.ui.tables.PickTablesPanel
import com.efpt.rider.util.Ui
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Port of the reverse engineering wizard (WizardDialogBox + Wiz1_PickServerDatabaseDialog, Wiz2_PickTablesDialog,
 * Wiz3_EfCoreModelDialog, Wiz4_StatusDialog with WizardDataViewModel and the RevEngWizardHandler BLL calls).
 *
 * Like the VS wizard the dialog is modeless; long running steps run as background tasks while the page shows
 * a busy indicator. Errors are shown on the status page (InvokeWithErrorHandlingAsync).
 */
class RevEngWizardDialog(
    private val project: DotNetProject,
    private val optionsPathArg: String?,
    private val uiHintArg: String?,
    private val onClosed: () -> Unit,
) : DialogWrapper(project.ideProject, null, true, IdeModalityType.MODELESS) {

    private val ide = project.ideProject

    // ------------------------------------------------------------------ pages
    private val cards = CardLayout()
    private val cardPanel = JPanel(cards)
    private var page = 1

    private val connectionPanel = DatabaseConnectionPanel(ide, wizardMode = true)
    private val configField = JBTextField().apply { isEditable = false }
    private val tablesPanel = PickTablesPanel(ide, disposable)
    private val modelPanel = ModelingOptionsPanel()
    private val statusArea = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }

    // ------------------------------------------------------------------ footer (Westwind status bar equivalent)
    private val busyIcon = AsyncProcessIcon("efpt-wizard").apply { isVisible = false }
    private val statusLabel = JBLabel("")

    // ------------------------------------------------------------------ state (WizardEventArgs / WizardDataViewModel)
    private var session: RevEngCore.Session? = null
    private var dbInfo: DatabaseConnectionModel? = null
    private var userOptions: ReverseEngineerUserOptions? = null
    private var page1Initialized = false
    private var page2Initialized = false
    private var page3Initialized = false
    private var loadedObjectsSourceKey: String? = null
    private var tablesLoaded = false
    private var busy = false
    private var errorShown = false
    private var generationDone = false

    private val previousAction = object : DialogWrapperAction(RevEngLocale.NavigationPrevious) {
        override fun doAction(e: ActionEvent?) = previous()
    }
    private val nextAction = object : DialogWrapperAction(RevEngLocale.NavigationNext) {
        override fun doAction(e: ActionEvent?) = next()
    }
    private val finishAction = object : DialogWrapperAction(RevEngLocale.NavigationFinish) {
        override fun doAction(e: ActionEvent?) = close(OK_EXIT_CODE)
    }

    init {
        title = RevEngLocale.ChooseDatabaseConnection
        cancelAction.putValue(Action.NAME, RevEngLocale.NavigationCancel)
        connectionPanel.onChange = { updateButtons() }
        init()
        updateButtons()
        loadPage1()
    }

    override fun createActions(): Array<Action> = arrayOf(previousAction, nextAction, finishAction, cancelAction)

    override fun createSouthAdditionalPanel(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        add(busyIcon)
        add(statusLabel)
    }

    override fun createCenterPanel(): JComponent {
        val page1 = panel {
            row { cell(connectionPanel.component).align(AlignX.FILL) }
            group("Config file") {
                row { cell(configField).align(AlignX.FILL) }
            }
        }
        val page3 = JBScrollPane(modelPanel.component).apply { border = JBUI.Borders.empty() }
        val page4 = panel {
            row { cell(JBScrollPane(statusArea)).align(AlignX.FILL).align(AlignY.FILL) }.resizableRow()
        }
        cardPanel.add(wrap(page1), "1")
        cardPanel.add(tablesPanel.component, "2")
        cardPanel.add(page3, "3")
        cardPanel.add(page4, "4")
        cardPanel.preferredSize = Dimension(JBUI.scale(760), JBUI.scale(600))
        return cardPanel
    }

    private fun wrap(c: JComponent) = JPanel(BorderLayout()).apply { add(c, BorderLayout.NORTH) }

    override fun getPreferredFocusedComponent(): JComponent? = connectionPanel.preferredFocus

    override fun dispose() {
        try {
            super.dispose()
        } finally {
            onClosed() // WizardIsRunning = false
        }
    }

    // ------------------------------------------------------------------ navigation

    private fun showPage(n: Int) {
        page = n
        cards.show(cardPanel, n.toString())
        title = when (n) {
            1 -> RevEngLocale.ChooseDatabaseConnection
            2 -> RevEngLocale.ChooseDatabaseObjects
            3 -> modelPanel.title.ifEmpty { RevEngLocale.LoadingOptions }
            else -> "Status"
        }
        updateButtons()
        when (n) {
            1 -> connectionPanel.preferredFocus.requestFocusInWindow()
            2 -> tablesPanel.preferredFocus?.requestFocusInWindow()
            3 -> modelPanel.preferredFocus.requestFocusInWindow()
        }
    }

    private fun updateButtons() {
        previousAction.isEnabled = !busy && when (page) {
            1 -> false
            4 -> !errorShown && generationDone
            else -> true
        }
        nextAction.isEnabled = !busy && when (page) {
            1 -> page1Initialized && connectionPanel.selected != null
            2 -> tablesLoaded
            3 -> page3Initialized
            else -> false
        }
        finishAction.isEnabled = !busy && page == 4 && (generationDone || errorShown)
        cancelAction.isEnabled = !busy || page != 4
    }

    /** BackButton_Click. */
    private fun previous() {
        if (busy) return
        when (page) {
            2 -> showPage(1)
            3 -> showPage(2)
            4 -> showPage(3)
        }
    }

    private fun next() {
        if (busy) return
        when (page) {
            1 -> { // Wiz1 NextButton_Click
                page1Initialized = true
                showPage(2)
                page2Visible()
            }
            2 -> { // Wiz2 NextButton_Click
                tablesPanel.validate()?.let {
                    Messages.showWarningDialog(cardPanel, it.message, Ui.TITLE)
                    return
                }
                page2Initialized = true
                showPage(3)
                page3Visible()
            }
            3 -> { // Wiz3 NextButton_Click -> GenerateFiles_Click + Wiz4
                // Rider: the VS wizard page lacks the EfCoreModelDialog validation; it is applied here as well.
                modelPanel.validate()?.let {
                    Messages.showWarningDialog(cardPanel, it.message, Ui.TITLE)
                    return
                }
                generate()
            }
        }
    }

    // ------------------------------------------------------------------ async helper

    private fun setBusy(value: Boolean, text: String = "") {
        busy = value
        busyIcon.isVisible = value
        if (value) busyIcon.resume() else busyIcon.suspend()
        statusLabel.text = text
        updateButtons()
    }

    private fun <T> runAsync(
        text: String,
        work: (ProgressIndicator) -> T,
        onFailure: (Throwable) -> Unit = { showError(it.message ?: it.toString(), it) },
        onSuccess: (T) -> Unit,
    ) {
        setBusy(true, text)
        ProgressManager.getInstance().run(object : Task.Backgroundable(ide, "EF Core Power Tools: $text", true) {
            private var result: Result<T>? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = text
                result = try {
                    Result.success(work(indicator))
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

            override fun onCancel() = onEdt { setBusy(false); close(CANCEL_EXIT_CODE) }

            override fun onFinished() = onEdt {
                setBusy(false)
                val r = result ?: return@onEdt
                r.onSuccess { onSuccess(it) }.onFailure { onFailure(it) }
            }
        })
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed) block() }, ModalityState.any())
    }

    /** InvokeWithErrorHandlingAsync failure: navigate to the status page and show the error. */
    private fun showError(message: String, exception: Throwable? = null) {
        if (exception != null) Ui.logError(ide, listOf(message), null)
        errorShown = true
        statusArea.text = message
        statusLabel.text = "Error occurred"
        showPage(4)
    }

    // ------------------------------------------------------------------ page 1

    /** Wiz1 OnPageVisibleAsync -> ReverseEngineerCodeFirstAsync(uiHint, wizardArgs) up to PickServerDatabaseComplete. */
    private fun loadPage1() {
        runAsync(RevEngLocale.GettingReadyToConnect, { _ ->
            val optionsPaths = project.getConfigFiles()
            var optionsPath = optionsPathArg ?: optionsPaths[0]
            if (optionsPathArg == null && optionsPaths.size > 1) {
                optionsPath = Ui.invokeAndWait { PickConfigDialog.show(ide, optionsPaths, project.fullPath) } ?: return@runAsync null
            }
            val s = RevEngCore.Session(project, optionsPath)
            val message = RevEngCore.load(s, uiHintArg)
            val choices = RevEngCore.connectionChoices(s)
            Triple(s, message, choices)
        }) { loaded ->
            if (loaded == null) {
                close(CANCEL_EXIT_CODE)
                return@runAsync
            }
            val (s, message, choices) = loaded
            session = s
            userOptions = s.userOptions
            configField.text = ConfigModel(s.optionsPath, project.fullPath).displayName
            message?.let { statusLabel.text = it }

            if (choices.allowedVersions.isEmpty()) {
                showError(".NET 7 and earlier is not supported.")
                return@runAsync
            }
            connectionPanel.publishConnections(choices.connections)
            connectionPanel.publishDefinitions(choices.definitions)
            choices.schemas?.let { connectionPanel.publishSchemas(it) }
            connectionPanel.publishCodeGenerationMode(choices.usedMode, choices.allowedVersions)
            choices.uiHint?.let { connectionPanel.uiHint = it }
            connectionPanel.loaded()

            // ChooseDataBaseConnectionAsync result (the wizard page returns its current values immediately)
            applyPage1ToOptions(s)
            if (s.newOptions) s.options.useDateOnlyTimeOnly = s.options.codeGenerationMode == CodeGenerationMode.EFCore8

            page1Initialized = true
            updateButtons()
        }
    }

    private fun applyPage1ToOptions(s: RevEngCore.Session) {
        val options = s.options
        val selected = connectionPanel.selected
        options.uiHint = connectionPanel.uiHint
        userOptions?.uiHint = connectionPanel.uiHint
        options.codeGenerationMode = connectionPanel.codeGenerationMode
        options.filterSchemas = connectionPanel.filterSchemas
        options.schemas = if (connectionPanel.filterSchemas) connectionPanel.schemas.toMutableList() else null
        options.dacpac = selected?.filePath
        if (selected != null) {
            options.connectionString = selected.connectionString
            options.databaseType = selected.databaseType
        }
    }

    // ------------------------------------------------------------------ page 2

    /** Wiz2 OnPageVisibleAsync: (re)load the database objects when the page 1 choices changed. */
    private fun page2Visible() {
        val s = session ?: return
        val sourceKey = connectionPanel.sourceKey()
        val sourceChanged = loadedObjectsSourceKey != null && loadedObjectsSourceKey != sourceKey
        if (tablesLoaded && !sourceChanged) {
            updateButtons()
            return
        }
        applyPage1ToOptions(s)
        if (tablesLoaded) {
            // Carry the current selection over to the reloaded object list
            s.options.tables = tablesPanel.result().objects.toMutableList()
        }
        tablesLoaded = false
        updateButtons()

        var errorMessage: String? = null
        runAsync(RevEngLocale.LoadingDatabaseObjects, { _ ->
            // GetDatabaseInfoAsync + LoadDataBaseObjectsAsync
            val info = RevEngCore.getDatabaseInfo(s) { errorMessage = it } ?: return@runAsync null
            val tables = RevEngCore.getTables(s.options, info)
            info to tables
        }) { loaded ->
            if (loaded == null) {
                showError(errorMessage ?: RevEngLocale.UnsupportedProvider)
                return@runAsync
            }
            val (info, tables) = loaded
            dbInfo = info
            val options = s.options
            val preselected = mutableListOf<SerializationTableModel>()
            if (!options.tables.isNullOrEmpty()) {
                preselected += ReverseEngineerHelper.normalizeTables(options.tables!!, info.databaseType == DatabaseType.SQLServer)
            }
            tablesPanel.load(tables, s.namingOptionsAndPath.first, preselected, options.databaseType != DatabaseType.SQLite)
            loadedObjectsSourceKey = sourceKey
            tablesLoaded = true
            updateButtons()
        }
    }

    // ------------------------------------------------------------------ page 3

    /** Wiz3 OnPageVisible: GetModelOptionsAsync (once). */
    private fun page3Visible() {
        val s = session ?: return
        readTablesFromPage2(s)
        if (page3Initialized) {
            updateButtons()
            return
        }
        runAsync(RevEngLocale.LoadingOptions, { _ ->
            val neededPackages = RevEngCore.neededPackages(project, s.options)
            s.options.installNuGetPackage = neededPackages.any { it.databaseTypes.contains(s.options.databaseType) && !it.installed }
        }) {
            val presets = RevEngCore.modelPresets(s.options, project.name)
            modelPanel.applyPresets(presets)
            modelPanel.publishTemplateTypes(s.options.selectedHandlebarsLanguage, ReverseEngineerHelper.calculateAllowedTemplates(s.options.codeGenerationMode))
            // Rider: the VS "HACK Work around for issue with web app project system on initial run" (no .user
            // file for new configurations) is not applied.
            page3Initialized = true
            title = modelPanel.title
            updateButtons()
            modelPanel.preferredFocus.requestFocusInWindow()
        }
    }

    /** IPickTablesDialog.ShowAndAwaitUserResponse in the wizard: copy the page 2 result into the options. */
    private fun readTablesFromPage2(s: RevEngCore.Session) {
        val result = tablesPanel.result()
        s.options.tables = result.objects.toMutableList()
        s.options.customReplacers = result.customReplacers.toList()
    }

    // ------------------------------------------------------------------ page 4

    /** GenerateFiles_Click + Wiz4_StatusDialog.OnPageVisible. */
    private fun generate() {
        val s = session ?: return
        val options = s.options
        val model = modelPanel.commit()
        readTablesFromPage2(s)
        // Rider: SaveOptionsAsync clears UiHint, so it is restored from page 1 for every (re)generation.
        options.uiHint = connectionPanel.uiHint
        if (userOptions == null) userOptions = ReverseEngineerUserOptions(options.uiHint)

        errorShown = false
        generationDone = false
        statusArea.text = ""
        showPage(4)

        runAsync(RevEngLocale.StatusbarGeneratingFiles, { indicator ->
            RevEngCore.applyModelResult(options, model)
            RevEngCore.saveOptions(s, userOptions, options.customReplacers to s.namingOptionsAndPath.second)
            RevEngCore.installNuGetPackages(project, false, options, false, allowNetStandard = true, indicator = indicator, showStatus = false)
            val neededPackages = RevEngCore.neededPackages(project, options)
            val missingProviderPackage = RevEngCore.missingProviderPackage(options, neededPackages)
            val status = RevEngCore.generateFiles(project, options, missingProviderPackage, false, neededPackages, isCalledByWizard = true, indicator = indicator)
            RevEngCore.runPostRun(ide, s.optionsPath)
            status
        }, onFailure = { e ->
            // Wiz4 RunGenerationAsync catch: show the error, allow Finish and Previous
            Ui.logError(ide, listOf(e.message ?: e.toString()), null)
            statusArea.text = "❌ ${e.message ?: e}"
            statusLabel.text = "Error occurred"
            generationDone = true
            updateButtons()
        }) { status ->
            statusArea.text = status
            statusLabel.text = ""
            generationDone = true
            updateButtons()
            toFront()
        }
    }
}
