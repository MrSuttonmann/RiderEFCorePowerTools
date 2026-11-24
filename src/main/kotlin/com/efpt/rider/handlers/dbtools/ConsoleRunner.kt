package com.efpt.rider.handlers.dbtools

import com.efpt.rider.tools.ProcessRunner
import com.efpt.rider.util.Ui
import com.intellij.execution.ExecutionException
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Path

/**
 * Rider replacement for the `cmd.exe /k "cd /d <folder> && <tool> ..."` windows the VS extension opens:
 * runs the tool in a Run tool window tab (stop / rerun buttons, live output).
 */
internal object ConsoleRunner {
    /**
     * Starts [commandLine] off the EDT and shows it in a console tab titled [title].
     * [onStartFailure] returns extra lines printed when the executable cannot be started (e.g. install hints).
     */
    fun run(
        project: Project,
        title: String,
        commandLine: GeneralCommandLine,
        onStartFailure: (() -> List<String>)? = null,
        rerun: (() -> Unit)? = null,
    ) {
        Ui.runBackground(project, title) {
            val header = "> " + ProcessRunner.redact(commandLine.commandLineString) +
                (commandLine.workingDirectory?.let { "   (in $it)" } ?: "")
            val handler = try {
                KillableColoredProcessHandler(commandLine)
            } catch (e: ExecutionException) {
                val lines = listOf(e.message ?: e.toString()) + (onStartFailure?.invoke() ?: emptyList())
                Ui.showError(project, lines.joinToString("\n"))
                return@runBackground
            }
            ProcessTerminatedListener.attach(handler)
            Ui.onEdt {
                val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
                console.print(header + "\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                val executor = RunContentExecutor(project, handler)
                    .withTitle(title)
                    .withConsole(console)
                    .withActivateToolWindow(true)
                    .withFocusToolWindow(true)
                if (rerun != null) executor.withRerun { rerun() }
                executor.run()
            }
        }
    }

    /**
     * Resolves a .NET global tool executable: PATH first (like cmd.exe would), then ~/.dotnet/tools
     * (Rider may have been started before the tool was installed, so its PATH can be stale).
     */
    fun resolveTool(name: String): String {
        val candidates = if (SystemInfo.isWindows) listOf("$name.exe", "$name.cmd", "$name.bat") else listOf(name)
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar).filter { it.isNotBlank() }
        for (dir in pathDirs) {
            for (c in candidates) {
                val f = File(dir, c)
                if (f.isFile) return f.absolutePath
            }
        }
        val toolsDir = Path.of(System.getProperty("user.home"), ".dotnet", "tools").toFile()
        for (c in candidates) {
            val f = File(toolsDir, c)
            if (f.isFile) return f.absolutePath
        }
        return name
    }
}
