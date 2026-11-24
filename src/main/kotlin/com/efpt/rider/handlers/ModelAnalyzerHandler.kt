package com.efpt.rider.handlers

import com.efpt.rider.handlers.analysis.AnalysisSupport
import com.efpt.rider.handlers.analysis.DgmlBuilder
import com.efpt.rider.model.GenerationType
import com.efpt.rider.project.DotNetProject
import com.efpt.rider.tools.EfptLauncher
import com.efpt.rider.ui.dgml.DgmlPreview
import com.efpt.rider.util.Ui
import java.io.File

/** Port of ModelAnalyzerHandler - DbContext DGML diagram, DDL SQL and DebugView. */
object ModelAnalyzerHandler {
    // ModelAnalyzerLocale (English)
    private const val CURRENT_EF_CORE_VERSION =
        "You are using EF Core version {0}, notice that previews have limited support. You can try to manually install Microsoft.EntityFrameworkCore.Design preview."
    private const val UNABLE_TO_COLLECT_MODEL_INFORMATION = "Unable to collect model information"

    /** Builds [project] then runs efpt for [generationType] (CmdIdDgmlBuild / CmdIdSqlBuild / CmdIdDebugViewBuild). */
    fun generate(project: DotNetProject, generationType: GenerationType) {
        val title = when (generationType) {
            GenerationType.Dgml -> "Generating DbContext diagram"
            GenerationType.Ddl -> "Generating DbContext DDL SQL"
            else -> "Generating DbContext DebugView"
        }
        Ui.runBackground(project.ideProject, title) { indicator ->
            // EFCorePowerToolsPackage.OnProjectContextMenuInvokeHandler: build + locate the assembly first.
            val outputPath = AnalysisSupport.locateProjectAssemblyPath(project, indicator) ?: return@runBackground
            indicator.text = title + "..."
            generate(outputPath, project, generationType)
        }
    }

    /** ModelAnalyzerHandler.GenerateAsync (off the EDT). Exceptions propagate to Ui.runBackground -> LogError. */
    private fun generate(outputPath: String, project: DotNetProject, generationType: GenerationType) {
        require(outputPath.isNotEmpty()) { "outputPath" }

        if (!AnalysisSupport.checkTargetFramework(project)) return

        val (hasDesign, efVersion) = project.containsEfCoreDesignReference()
        if (efVersion.isEmpty()) {
            Ui.showError(project.ideProject, AnalysisSupport.EF_CORE_VERSION_NOT_FOUND)
            return
        }

        if (AnalysisSupport.parseDotNetVersion(efVersion) == null) {
            Ui.showError(project.ideProject, AnalysisSupport.format(CURRENT_EF_CORE_VERSION, efVersion))
            if (!hasDesign) return
        }

        if (!hasDesign) {
            // Rider: VS starts `dotnet add package` fire-and-forget; here it runs (in this background task) before the message.
            val install = project.installPackage("Microsoft.EntityFrameworkCore.Design", efVersion)
            if (!install.success) Ui.logError(project.ideProject, listOf(install.output), null)
            Ui.showError(project.ideProject, AnalysisSupport.format(AnalysisSupport.INSTALLING_EF_CORE_DESIGN_PACKAGE, efVersion))
            return
        }

        val processResult = EfptLauncher(project).getOutput(outputPath, generationType)

        if (processResult.isEmpty()) throw IllegalStateException(UNABLE_TO_COLLECT_MODEL_INFORMATION)
        if (processResult.contains("Error:", ignoreCase = true)) throw IllegalStateException(processResult)

        val modelResult = EfptLauncher.buildModelResult(processResult)

        when (generationType) {
            GenerationType.Dgml -> generateDgml(modelResult, project)
            GenerationType.Ddl -> AnalysisSupport.generateFiles(project, modelResult, ".sql", addToProject = true)
                .forEach { Ui.openFile(project.ideProject, it) }
            GenerationType.DebugView -> AnalysisSupport.generateFiles(project, modelResult, ".txt")
                .forEach { Ui.openFile(project.ideProject, it) }
            else -> Unit
        }
    }

    private fun generateDgml(modelResult: List<Pair<String, String>>, project: DotNetProject) {
        var target: File? = null

        for ((contextName, debugView) in modelResult) {
            val dgmlText = DgmlBuilder.build(debugView, contextName)

            if (AnalysisSupport.isInvalidFileName(contextName)) {
                Ui.showError(project.ideProject, "${AnalysisSupport.INVALID_NAME}: $contextName")
                return
            }

            val path = File(AnalysisSupport.tempDir(), "$contextName.dgml")
            AnalysisSupport.writeUtf8WithBom(path, dgmlText)

            target = File(project.directory.toFile(), path.name)
            path.copyTo(target, overwrite = true)
        }

        if (target != null) {
            Ui.refreshPaths(target.path)
            // Rider: VS opens the .dgml in its DGML designer; Rider has none, see DgmlPreview.
            DgmlPreview.open(project.ideProject, target.path)
        }
    }
}
