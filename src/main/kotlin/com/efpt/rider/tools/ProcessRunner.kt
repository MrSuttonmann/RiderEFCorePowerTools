package com.efpt.rider.tools

import com.efpt.rider.settings.EfptSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Runs external processes. Must not be called on the EDT. Port of ProcessLauncher.RunProcessAsync. */
object ProcessRunner {
    private val LOG = logger<ProcessRunner>()

    fun dotnet(vararg args: String, workingDirectory: Path? = null): GeneralCommandLine =
        GeneralCommandLine(EfptSettings.dotnet())
            .withParameters(*args)
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en")
            .withEnvironment("DOTNET_NOLOGO", "1")
            .withEnvironment("DOTNET_SKIP_FIRST_TIME_EXPERIENCE", "1")
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .also { cl -> workingDirectory?.let { cl.withWorkingDirectory(it) } }

    /** Executes and returns the full process output. Honors cancellation of the current progress indicator. */
    fun execute(commandLine: GeneralCommandLine, indicator: ProgressIndicator? = ProgressManager.getInstance().progressIndicator): ProcessOutput {
        LOG.info("Running: ${redact(commandLine.commandLineString)}")
        val handler = CapturingProcessHandler(commandLine)
        val output = if (indicator != null) handler.runProcessWithProgressIndicator(indicator) else handler.runProcess()
        if (output.isCancelled) throw com.intellij.openapi.progress.ProcessCanceledException()
        return output
    }

    /**
     * Same contract as the VS extension: returns stdout, or "Error:" + newline + stderr when stdout is empty.
     */
    fun run(commandLine: GeneralCommandLine, indicator: ProgressIndicator? = ProgressManager.getInstance().progressIndicator): String {
        val output = execute(commandLine, indicator)
        val stdout = output.stdout
        if (stdout.isEmpty() && output.stderr.isNotEmpty()) {
            return "Error:" + System.lineSeparator() + output.stderr
        }
        return stdout
    }

    private val secretPattern = Regex("(?i)(password|pwd)=([^;\"]*)")

    /** Keeps passwords out of the IDE log. */
    fun redact(text: String): String = secretPattern.replace(text) { "${it.groupValues[1]}=***" }

    /** Returns the installed shared runtimes, `dotnet --list-runtimes`. */
    fun listRuntimes(): List<String> = try {
        run(dotnet("--list-runtimes")).lines().map { it.trim() }.filter { it.isNotEmpty() }
    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        LOG.warn(e)
        emptyList()
    }

    fun isRuntimeInstalled(version: String): Boolean =
        listRuntimes().any { it.startsWith("Microsoft.NETCore.App $version.", ignoreCase = true) }
}
