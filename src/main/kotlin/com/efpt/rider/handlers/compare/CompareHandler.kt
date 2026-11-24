package com.efpt.rider.handlers.compare

import com.efpt.rider.connections.DataConnections
import com.efpt.rider.handlers.analysis.AnalysisSupport
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.GenerationType
import com.efpt.rider.project.DotNetProject
import com.efpt.rider.tools.EfptLauncher
import com.efpt.rider.ui.compare.CompareOptionsDialog
import com.efpt.rider.ui.compare.CompareResultDialog
import com.efpt.rider.util.Ui

/** Port of CompareHandler - compare DbContext model to a database. */
object CompareHandler {
    // CompareLocale (English)
    internal const val CANNOT_COMPARE_WHILE_DEBUGGING = "Cannot compare context to database while debugging"
    internal const val CANNOT_SUPPORT_VERSION =
        "Cannot support version {0}, notice that previews have limited support. You can try to manually install Microsoft.EntityFrameworkCore.Design preview."
    internal const val VERSION_SUPPORTED = "Only EF Core 6 or later is supported."
    internal const val INSTALLING_EF_CORE_SCHEMA_COMPARE = "Installing EFCore.SchemaCompare, please retry the command"
    internal const val LOADING_DATA = "Loading data"
    internal const val NO_CONTEXTS_SELECTED = "No contexts selected"
    internal const val COMPARING_DATABASE_WITH_CONTEXTS = "Comparing database with context(s)..."
    internal const val COMPARE_COMPLETED_IN = "Compare completed in {0}"
    internal const val CONTEXT_DATABASE_MATCH = "Context(s) and database structure match"
    internal const val UNABLE_TO_COLLECT_DBCONTEXT_INFORMATION = "Unable to collect DbContext information"
    internal const val UNABLE_TO_COLLECT_SCHEMA_COMPARE_INFORMATION = "Unable to collect SchemaCompare information"

    /** Builds [project] then runs the comparison UI flow. */
    fun handleComparison(project: DotNetProject) {
        val ideProject = project.ideProject
        // Rider: VS builds first and checks the debugger afterwards; checking first avoids a pointless
        // build (which usually fails on locked binaries while debugging anyway). Same message.
        if (Ui.isDebugging(ideProject)) {
            Ui.showError(ideProject, CANNOT_COMPARE_WHILE_DEBUGGING)
            return
        }

        Ui.runBackground(ideProject, "Comparing DbContext with database") { indicator ->
            val outputPath = AnalysisSupport.locateProjectAssemblyPath(project, indicator) ?: return@runBackground

            if (!AnalysisSupport.checkTargetFramework(project)) return@runBackground

            val (hasSchemaCompare, efVersion) = project.containsEfSchemaCompareReference()
            if (!hasSchemaCompare) {
                val version = AnalysisSupport.parseDotNetVersion(efVersion)
                if (version == null) {
                    Ui.showError(ideProject, AnalysisSupport.format(CANNOT_SUPPORT_VERSION, efVersion))
                    return@runBackground
                }
                val major = version[0]
                if (major !in 6..9) {
                    Ui.showError(ideProject, VERSION_SUPPORTED)
                    return@runBackground
                }
                indicator.text = INSTALLING_EF_CORE_SCHEMA_COMPARE
                // Rider: VS runs these `dotnet add package` commands fire-and-forget (and concurrently);
                // they are run one after the other here so they do not race on the project file.
                val installs = when (major) {
                    6 -> listOf("EfCore.SchemaCompare" to "6.0.0")
                    7 -> listOf("EfCore.SchemaCompare" to "7.0.0", "Microsoft.EntityFrameworkCore.Design" to "7.0.0")
                    8 -> listOf("EfCore.SchemaCompare" to "8.2.0", "Microsoft.EntityFrameworkCore.Design" to "8.0.0")
                    else -> listOf("EfCore.SchemaCompare" to "8.2.0", "Microsoft.EntityFrameworkCore.Design" to "9.0.0")
                }
                for ((id, ver) in installs) {
                    val r = project.installPackage(id, ver)
                    if (!r.success) Ui.logError(ideProject, listOf(r.output), null)
                }
                Ui.showError(ideProject, INSTALLING_EF_CORE_SCHEMA_COMPARE)
                return@runBackground
            }

            Ui.status(ideProject, LOADING_DATA)
            indicator.text = LOADING_DATA

            // Rider: VS only lists Server Explorer data connections; .dacpac / SQL project definitions
            // cannot be compared against a live model, so they are filtered out defensively.
            val databaseList = DataConnections.getDataConnections(ideProject).values
                .filter { it.databaseType != DatabaseType.SQLServerDacpac }
                .map { it.copy() }
            val contextTypes = getDbContextTypes(outputPath, project)

            Ui.status(ideProject, "")

            val selection = Ui.invokeAndWait {
                val dialog = CompareOptionsDialog(ideProject, databaseList, contextTypes)
                if (dialog.showAndGet()) dialog.getSelection() else null
            } ?: return@runBackground

            if (selection.contextTypes.isEmpty()) {
                Ui.showError(ideProject, NO_CONTEXTS_SELECTED)
                return@runBackground
            }
            val connection = selection.connection ?: return@runBackground

            Ui.status(ideProject, COMPARING_DATABASE_WITH_CONTEXTS)
            indicator.text = COMPARING_DATABASE_WITH_CONTEXTS

            val started = System.nanoTime()
            val comparisonResult = getComparisonResult(outputPath, project, connection.connectionString ?: "", selection.contextTypes)
            val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000L

            // TimeSpan.ToString("h\\:mm\\:ss")
            val elapsed = String.format("%d:%02d:%02d", (elapsedSeconds / 3600) % 24, (elapsedSeconds / 60) % 60, elapsedSeconds % 60)
            Ui.status(ideProject, AnalysisSupport.format(COMPARE_COMPLETED_IN, elapsed))

            if (comparisonResult.isNotEmpty()) {
                Ui.invokeAndWait { CompareResultDialog(ideProject, comparisonResult).show() }
            } else {
                Ui.showMessage(ideProject, CONTEXT_DATABASE_MATCH)
            }

            Ui.status(ideProject, "")
        }
    }

    private fun getDbContextTypes(outputPath: String, project: DotNetProject): List<String> {
        val processResult = EfptLauncher(project).getOutput(outputPath, GenerationType.DbContextList)
        if (processResult.isEmpty()) throw IllegalStateException(UNABLE_TO_COLLECT_DBCONTEXT_INFORMATION)
        if (processResult.contains("Error:", ignoreCase = true)) throw IllegalStateException(processResult)
        return EfptLauncher.buildModelResult(processResult).map { it.first }
    }

    private fun getComparisonResult(
        outputPath: String,
        project: DotNetProject,
        connectionString: String,
        contextNames: List<String>,
    ): List<CompareLogModel> {
        val processResult = EfptLauncher(project).getOutput(
            outputPath,
            GenerationType.DbContextCompare,
            contextName = contextNames.joinToString(","),
            connectionString = connectionString,
        )
        if (processResult.isEmpty()) throw IllegalStateException(UNABLE_TO_COLLECT_SCHEMA_COMPARE_INFORMATION)
        if (processResult.contains("Error:", ignoreCase = true)) throw IllegalStateException(processResult)
        val modelResults = EfptLauncher.buildModelResult(processResult)
        if (modelResults.isEmpty()) throw IllegalStateException(UNABLE_TO_COLLECT_SCHEMA_COMPARE_INFORMATION)
        return CompareLogModel.listFromJson(modelResults[0].second)
    }
}
