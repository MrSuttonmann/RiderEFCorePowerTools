package com.efpt.rider.handlers.analysis

import com.efpt.rider.project.DotNetProject
import com.efpt.rider.util.Ui
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Shared helpers of the DbContext model inspection features (ModelAnalyzerHandler / CompareHandler):
 * EFCorePowerToolsPackage.LocateProjectAssemblyPathAsync and ProjectExtensions.GenerateFiles.
 * All functions must be called off the EDT.
 */
object AnalysisSupport {
    // SharedLocale (English)
    const val BUILD_FAILED = "Build failed. Unable to discover a DbContext class."
    const val UNABLE_TO_LOCATE_PROJECT_ASSEMBLY = "Unable to locate project assembly"
    const val SELECTED_PROJECT_TYPE_NO_TFM = "The selected project type has no TargetFrameworkMoniker"
    const val SUPPORTED_FRAMEWORK = "Only .NET Core 3.0+ projects are supported - TargetFrameworkMoniker"
    const val EF_CORE_VERSION_NOT_FOUND = "EF Core 3.1 or later not found in project"
    const val INSTALLING_EF_CORE_DESIGN_PACKAGE = "Installing EFCore.Design version {0}, please retry the command"
    const val INVALID_NAME = "Invalid name"

    /**
     * Port of EFCorePowerToolsPackage.LocateProjectAssemblyPathAsync: builds the project and returns
     * the output assembly path, or null (with a status bar message) when the build fails or the
     * assembly cannot be found.
     */
    fun locateProjectAssemblyPath(project: DotNetProject, indicator: ProgressIndicator?): String? {
        indicator?.text = "Building ${project.name}..."
        val build = project.build()
        if (!build.success) {
            Ui.status(project.ideProject, BUILD_FAILED)
            // Rider: VS shows the build errors in its Error List / Output window; `dotnet build` output
            // is surfaced in a notification instead so the failure is not silently lost in the status bar.
            Ui.notify(
                project.ideProject,
                BUILD_FAILED + "<br/><pre>" + escapeHtml(build.output.trim().lines().takeLast(15).joinToString("\n")) + "</pre>",
                NotificationType.WARNING,
            )
            return null
        }
        val path = project.getOutputAssemblyPath()
        if (path != null) return path
        Ui.status(project.ideProject, UNABLE_TO_LOCATE_PROJECT_ASSEMBLY)
        return null
    }

    /**
     * The TargetFrameworkMoniker / .NET 8+ checks shared by ModelAnalyzerHandler and CompareHandler.
     * Returns false (after showing the VS error) when the project is not supported.
     */
    fun checkTargetFramework(project: DotNetProject): Boolean {
        val tfm = project.getProperty("TargetFrameworkMoniker")
        if (tfm == null) {
            Ui.showError(project.ideProject, SELECTED_PROJECT_TYPE_NO_TFM)
            return false
        }
        if (!project.isNet80OrHigher()) {
            Ui.showError(project.ideProject, "$SUPPORTED_FRAMEWORK: $tfm")
            return false
        }
        return true
    }

    /** System.Version.TryParse equivalent: 2 to 4 numeric components, no pre-release suffix. */
    fun parseDotNetVersion(text: String?): List<Int>? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split('.')
        if (parts.size !in 2..4) return null
        val numbers = parts.map { p -> p.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
        return numbers
    }

    /** Path.GetInvalidPathChars / GetInvalidFileNameChars check (Windows rules, as in VS). */
    fun isInvalidFileName(name: String): Boolean =
        name.any { it.code < 32 || it in "\"<>|:*?\\/" }

    /**
     * Port of ProjectExtensions.GenerateFiles: writes one file per (name, content) pair. With
     * [addToProject] the files go to the project folder (SDK-style globbing includes them);
     * otherwise to the temp folder as read-only files. Returns the written paths.
     */
    fun generateFiles(project: DotNetProject, result: List<Pair<String, String>>, extension: String, addToProject: Boolean = false): List<String> {
        val list = mutableListOf<String>()
        for ((name, content) in result) {
            if (isInvalidFileName(name)) {
                Ui.showError(project.ideProject, "$INVALID_NAME: $name")
                return list
            }
            if (addToProject) {
                val itemPath = File(project.directory.toFile(), name + extension)
                writeUtf8WithBom(itemPath, content)
                list += itemPath.path
            } else {
                val filePath = File(tempDir(), name + extension)
                if (filePath.exists()) filePath.setWritable(true)
                writeUtf8WithBom(filePath, content)
                filePath.setReadOnly()
                list += filePath.path
            }
        }
        if (list.isNotEmpty()) Ui.refreshPaths(*list.toTypedArray())
        return list
    }

    /** Path.GetTempPath(). */
    fun tempDir(): File = File(System.getProperty("java.io.tmpdir"))

    /** File.WriteAllText(path, text, Encoding.UTF8) - UTF-8 with BOM. */
    fun writeUtf8WithBom(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.outputStream().use {
            it.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            it.write(text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    /** string.Format with positional {0} arguments. */
    fun format(template: String, vararg args: Any?): String {
        var result = template
        args.forEachIndexed { i, a -> result = result.replace("{$i}", a?.toString() ?: "") }
        return result
    }

    private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
