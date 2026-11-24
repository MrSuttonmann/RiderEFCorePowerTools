package com.efpt.rider.util

import com.efpt.rider.tools.ProcessRunner
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.xdebugger.XDebuggerManager
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Equivalents of VSHelper, VS.StatusBar, VS.Documents and EFCorePowerToolsPackage.LogError. */
object Ui {
    const val TITLE = "Power Tools for EF Core"
    private val LOG = logger<Ui>()

    // ---------------------------------------------------------------- message boxes (any thread)

    fun showError(project: Project?, message: String) = onEdt { Messages.showErrorDialog(project, message, TITLE) }

    fun showWarning(project: Project?, message: String) = onEdt { Messages.showWarningDialog(project, message, TITLE) }

    fun showMessage(project: Project?, message: String) = onEdt { Messages.showInfoMessage(project, message, TITLE) }

    /** Yes/No question; may be called from any thread (blocks until answered). */
    fun confirm(project: Project?, message: String): Boolean = invokeAndWait {
        Messages.showYesNoDialog(project, message, TITLE, Messages.getQuestionIcon()) == Messages.YES
    }

    // ---------------------------------------------------------------- notifications / status

    fun notify(project: Project?, content: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance().getNotificationGroup("Power Tools for EF Core")
            .createNotification(TITLE, content, type)
            .notify(project)
    }

    /** VS.StatusBar.ShowMessageAsync. */
    fun status(project: Project?, text: String) = onEdt {
        project?.let { WindowManager.getInstance().getStatusBar(it)?.info = text }
    }

    /**
     * EFCorePowerToolsPackage.LogError: logs [exception] (and [messages]) to the IDE log and an
     * error notification; with an exception, also shows "An error occurred".
     */
    fun logError(project: Project?, messages: List<String>, exception: Throwable?) {
        if (exception is ProcessCanceledException) return
        val text = buildString {
            messages.forEach { appendLine(it) }
            exception?.let { append(it.message ?: it.toString()) }
        }.trim()
        if (exception != null) LOG.warn(ProcessRunner.redact(text), exception) else LOG.warn(ProcessRunner.redact(text))
        notify(project, ProcessRunner.redact(text).replace("\n", "<br/>").ifEmpty { "An error occurred" }, NotificationType.ERROR)
        if (exception != null) showError(project, "An error occurred.\n\n" + ProcessRunner.redact(exception.message ?: exception.toString()))
    }

    // ---------------------------------------------------------------- documents

    fun refreshAndFind(path: String): VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path))

    /** VS.Documents.OpenAsync. */
    fun openFile(project: Project, path: String, focus: Boolean = true) = onEdt {
        refreshAndFind(path)?.let { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, it), focus) }
    }

    /** VS.Documents.OpenInPreviewTabAsync - Rider opens markdown with the preview editor when available. */
    fun openPreview(project: Project, path: String) = onEdt {
        refreshAndFind(path)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    /** Refresh the VFS for files/folders written by an external process (VS picks these up automatically). */
    fun refreshPaths(vararg paths: String) {
        val files = paths.mapNotNull { refreshAndFind(it) }
        if (files.isNotEmpty()) VfsUtil.markDirtyAndRefresh(true, true, true, *files.toTypedArray())
    }

    // ---------------------------------------------------------------- debugging

    /** VSHelper.IsDebugModeAsync. */
    fun isDebugging(project: Project): Boolean = XDebuggerManager.getInstance(project).currentSession != null

    // ---------------------------------------------------------------- threading

    fun onEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action() else app.invokeLater(action, ModalityState.any())
    }

    fun <T> invokeAndWait(action: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) return action()
        val ref = AtomicReference<T>()
        app.invokeAndWait({ ref.set(action()) }, ModalityState.any())
        return ref.get()
    }

    /**
     * Runs [work] in a cancellable background task with a progress indicator. Exceptions are
     * reported through [logError]. Dialogs may be shown from [work] with [invokeAndWait].
     */
    fun runBackground(project: Project, title: String, work: (ProgressIndicator) -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    work(indicator)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Throwable) {
                    logError(project, emptyList(), e)
                }
            }
        })
    }
}
