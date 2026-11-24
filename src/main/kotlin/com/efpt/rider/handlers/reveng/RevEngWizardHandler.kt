package com.efpt.rider.handlers.reveng

import com.efpt.rider.project.DotNetProject
import com.efpt.rider.ui.reveng.RevEngWizardDialog
import com.efpt.rider.util.Ui

/** Port of RevEngWizardHandler - the multi-page "Reverse Engineer" wizard (the default command in VS). */
object RevEngWizardHandler {
    /** RevEngWizardHandler.WizardIsRunning: only one wizard at a time. */
    @Volatile
    private var running: RevEngWizardDialog? = null

    val wizardIsRunning: Boolean get() = running != null

    /**
     * ReverseEngineerCodeFirstLaunchWizardAsync. [optionsPath] null = pick/choose config for [project];
     * [uiHint] preselects a connection (e.g. invoked from the Database tool window).
     */
    fun launch(project: DotNetProject, optionsPath: String? = null, uiHint: String? = null) {
        val ide = project.ideProject
        if (Ui.isDebugging(ide)) {
            Ui.showError(ide, RevEngLocale.CannotGenerateCodeWhileDebugging)
            return
        }
        running?.let {
            // Rider: VS silently ignores the command while a wizard is open; the open wizard is brought to front.
            if (!it.isDisposed) {
                it.toFront()
                return
            }
        }
        try {
            lateinit var dialog: RevEngWizardDialog
            dialog = RevEngWizardDialog(project, optionsPath, uiHint) { if (running === dialog) running = null }
            running = dialog
            dialog.show()
        } catch (e: Exception) {
            running = null
            Ui.logError(ide, emptyList(), e)
        }
    }
}
