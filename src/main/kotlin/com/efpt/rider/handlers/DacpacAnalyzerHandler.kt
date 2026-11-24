package com.efpt.rider.handlers

import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.project.SqlProjHelper
import com.efpt.rider.tools.EfRevEngLauncher
import com.efpt.rider.util.Ui
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import java.io.File

/** Port of DacpacAnalyzerHandler - SQL Server rules analysis report for a SQL project or database. */
object DacpacAnalyzerHandler {
    private const val PROGRESS = "Generating DACPAC Analysis report..."
    private val LOG = logger<DacpacAnalyzerHandler>()

    fun generate(ideProject: com.intellij.openapi.project.Project, path: String, isConnectionString: Boolean) {
        if (ApplicationManager.getApplication().isDispatchThread) {
            Ui.runBackground(ideProject, PROGRESS) { indicator -> work(ideProject, path, isConnectionString, indicator) }
        } else {
            // Already on a background (progress) thread, e.g. the Database tool window action.
            work(ideProject, path, isConnectionString, ProgressManager.getInstance().progressIndicator)
        }
    }

    private fun work(ide: Project, path: String, isConnectionString: Boolean, indicator: ProgressIndicator?) {
        try {
            indicator?.isIndeterminate = false
            progress(ide, indicator, 1)

            val dacpacPath = if (isConnectionString) path else SqlProjHelper.buildSqlProject(ide, path) ?: ""

            progress(ide, indicator, 2)

            val reportPath = EfRevEngLauncher(CodeGenerationMode.EFCore8).getReportPath(dacpacPath, isConnectionString)

            progress(ide, indicator, 3)

            if (File(reportPath).exists()) {
                openReport(ide, reportPath)
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            Ui.logError(ide, emptyList(), e)
        } finally {
            Ui.status(ide, "")
        }
    }

    private fun progress(ide: Project, indicator: ProgressIndicator?, step: Int) {
        // VS.StatusBar.ShowProgressAsync(text, step, 3)
        Ui.status(ide, PROGRESS)
        indicator?.text = PROGRESS
        indicator?.fraction = step / 3.0
    }

    /**
     * VS opens the HTML report in the VS web browser (IVsWebBrowsingService), falling back to the default
     * browser. Rider: an editor tab with the built-in (JCEF) browser, falling back to the default browser.
     */
    private fun openReport(ide: Project, reportPath: String) {
        val html = runCatching { File(reportPath).readText(Charsets.UTF_8) }.getOrNull()
        Ui.onEdt {
            try {
                // HTMLEditorProvider falls back to a plain text view itself when JCEF is unavailable.
                checkNotNull(html) { "Unable to read $reportPath" }
                HTMLEditorProvider.openEditor(ide, File(reportPath).name, HTMLEditorProvider.Request.html(html))
                return@onEdt
            } catch (e: Exception) {
                LOG.warn("Unable to show the report in the IDE, opening the default browser", e)
            }
            BrowserUtil.browse(File(reportPath))
        }
    }
}
