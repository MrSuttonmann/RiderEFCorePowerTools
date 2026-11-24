package com.efpt.rider.actions

import com.efpt.rider.connections.DataConnections
import com.efpt.rider.handlers.AboutHandler
import com.efpt.rider.handlers.CliHandler
import com.efpt.rider.handlers.DabBuilderHandler
import com.efpt.rider.handlers.DacpacAnalyzerHandler
import com.efpt.rider.handlers.DatabaseDiagramHandler
import com.efpt.rider.handlers.DgmlNugetHandler
import com.efpt.rider.handlers.ErDiagramHandler
import com.efpt.rider.handlers.ModelAnalyzerHandler
import com.efpt.rider.handlers.compare.CompareHandler
import com.efpt.rider.handlers.reveng.RevEngWizardHandler
import com.efpt.rider.handlers.reveng.ReverseEngineerHandler
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.GenerationType
import com.efpt.rider.project.DotNetProject
import com.efpt.rider.project.SolutionProjects
import com.efpt.rider.settings.EfptConfigurable
import com.efpt.rider.util.Ui
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.diagnostic.logger

private val LOG = logger<ProjectAction>()

// Ports of EFCorePowerToolsPackage's OnXxxBeforeQueryStatus / OnXxxInvokeHandler pairs.

/** Base for commands on a single selected project (OnProjectContextMenuInvokeHandler). */
abstract class ProjectAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    final override fun update(e: AnActionEvent) {
        val project = SolutionProjects.active(e)
        e.presentation.isEnabledAndVisible = e.project != null && project != null && isVisible(project)
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = SolutionProjects.active(e) ?: return
        LOG.info("${javaClass.simpleName} invoked on ${project.fullPath} (place: ${e.place})")
        try {
            perform(project, e)
        } catch (ex: Exception) {
            Ui.logError(e.project, emptyList(), ex)
        }
    }

    protected open fun isVisible(project: DotNetProject): Boolean = true
    protected abstract fun perform(project: DotNetProject, e: AnActionEvent)
}

/** Visible for .NET 10+ projects referencing Microsoft.EntityFrameworkCore. */
abstract class EfModelAction : ProjectAction() {
    override fun isVisible(project: DotNetProject) =
        project.isNet10OrHigher() && project.referencesPackageQuick("Microsoft.EntityFrameworkCore")
}

class EfptProjectGroup : DefaultActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = SolutionProjects.active(e) != null
    }
}

class ReverseEngineerAction : ProjectAction() {
    override fun isVisible(project: DotNetProject) = project.canUseReverseEngineer()
    override fun perform(project: DotNetProject, e: AnActionEvent) = RevEngWizardHandler.launch(project)
}

class ReverseEngineerLegacyAction : ProjectAction() {
    override fun isVisible(project: DotNetProject) = project.canUseReverseEngineer()
    override fun perform(project: DotNetProject, e: AnActionEvent) = ReverseEngineerHandler.reverseEngineerCodeFirst(project)
}

class ReverseEngineerRefreshAction : ProjectAction() {
    override fun isVisible(project: DotNetProject) = project.canUseReverseEngineer() && project.getConfigFiles().size == 1
    override fun perform(project: DotNetProject, e: AnActionEvent) {
        val configs = project.getConfigFiles()
        if (configs.size != 1) return
        ReverseEngineerHandler.reverseEngineerCodeFirst(project, configs[0], onlyGenerate = true)
    }
}

class DabBuilderAction : ProjectAction() {
    override fun perform(project: DotNetProject, e: AnActionEvent) = DabBuilderHandler.buildDabConfig(project)
}

class DgmlBuildAction : EfModelAction() {
    override fun perform(project: DotNetProject, e: AnActionEvent) = ModelAnalyzerHandler.generate(project, GenerationType.Dgml)
}

class SqlBuildAction : EfModelAction() {
    override fun perform(project: DotNetProject, e: AnActionEvent) = ModelAnalyzerHandler.generate(project, GenerationType.Ddl)
}

class DebugViewBuildAction : EfModelAction() {
    override fun perform(project: DotNetProject, e: AnActionEvent) = ModelAnalyzerHandler.generate(project, GenerationType.DebugView)
}

class DgmlNugetAction : EfModelAction() {
    override fun perform(project: DotNetProject, e: AnActionEvent) = DgmlNugetHandler.installDgmlNuget(project)
}

class DbCompareAction : EfModelAction() {
    override fun perform(project: DotNetProject, e: AnActionEvent) = CompareHandler.handleComparison(project)
}

class DatabaseDiagramAction : ProjectAction() {
    override fun perform(project: DotNetProject, e: AnActionEvent) {
        val connectionName = if (project.isSqlDatabaseProject) project.fullPath else null
        DatabaseDiagramHandler.generate(project, connectionName)
    }
}

class ErDiagramAction : ProjectAction() {
    override fun perform(project: DotNetProject, e: AnActionEvent) = ErDiagramHandler.buildErDiagram(project, "")
}

class OptionsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, EfptConfigurable::class.java)
    }
}

class AboutAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = AboutHandler.showDialog(e.project)
}

// ------------------------------------------------------------------ SQL database projects (OnSqlProjectMenuBeforeQueryStatus)

class SqlprojCreateAction : ProjectAction() {
    override fun isVisible(project: DotNetProject): Boolean {
        if (!project.isSqlDatabaseProject) return false
        val ide = project.ideProject
        return SolutionProjects.all(ide).any { it.isCSharpProject && !it.directory.resolve("efpt.config.json").toFile().exists() }
    }

    override fun perform(project: DotNetProject, e: AnActionEvent) = ReverseEngineerHandler.createFromSqlProject(project)
}

class SqlprojAnalyzeAction : ProjectAction() {
    override fun isVisible(project: DotNetProject) = project.isSqlDatabaseProject
    override fun perform(project: DotNetProject, e: AnActionEvent) =
        DacpacAnalyzerHandler.generate(project.ideProject, project.fullPath, isConnectionString = false)
}

// ------------------------------------------------------------------ config files (OnReverseEngineerConfigFileMenuBeforeQueryStatus)

object ConfigFiles {
    fun isConfigFile(name: String?) = name != null &&
        ((name.startsWith("efpt.", true) && name.endsWith(".config.json", true)) || name.equals("efcpt-config.json", true))

    fun isCliConfigFile(name: String?) = name != null && name.equals("efcpt-config.json", true)

    fun isDabConfigFile(name: String?) = name != null && name.equals("dab-config.json", true)
}

abstract class ConfigFileAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = SolutionProjects.selectedFile(e)
        val ideProject = e.project
        e.presentation.isEnabledAndVisible = file != null && ideProject != null && isVisible(file, SolutionProjects.containing(ideProject, file))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val ideProject = e.project ?: return
        val file = SolutionProjects.selectedFile(e) ?: return
        val project = SolutionProjects.containing(ideProject, file) ?: return
        try {
            perform(project, file)
        } catch (ex: Exception) {
            Ui.logError(ideProject, emptyList(), ex)
        }
    }

    protected open fun isVisible(file: VirtualFile, project: DotNetProject?): Boolean =
        project != null && ConfigFiles.isConfigFile(file.name) && project.canUseReverseEngineer()

    protected abstract fun perform(project: DotNetProject, file: VirtualFile)
}

class ConfigEditAction : ConfigFileAction() {
    override fun perform(project: DotNetProject, file: VirtualFile) {
        if (ConfigFiles.isCliConfigFile(file.name)) CliHandler.editConfig(project)
        else ReverseEngineerHandler.reverseEngineerCodeFirst(project, file.path, onlyGenerate = false)
    }
}

class ConfigRefreshAction : ConfigFileAction() {
    override fun perform(project: DotNetProject, file: VirtualFile) {
        if (ConfigFiles.isCliConfigFile(file.name)) CliHandler.runCli(project)
        else ReverseEngineerHandler.reverseEngineerCodeFirst(project, file.path, onlyGenerate = true)
    }
}

class DabStartAction : ConfigFileAction() {
    override fun isVisible(file: VirtualFile, project: DotNetProject?) = ConfigFiles.isDabConfigFile(file.name)
    override fun perform(project: DotNetProject, file: VirtualFile) = DabBuilderHandler.launchDab(project.ideProject, file.path)
}

// ------------------------------------------------------------------ Database tool window (Server Explorer equivalents)

abstract class DataSourceAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val type = DataConnections.selectedDataSourceType(e)
        e.presentation.isEnabledAndVisible = e.project != null && type != null && type != DatabaseType.Undefined && isVisible(type, e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val name = DataConnections.selectedDataSourceName(e) ?: return
        try {
            perform(name, e)
        } catch (ex: Exception) {
            Ui.logError(e.project, emptyList(), ex)
        }
    }

    protected open fun isVisible(type: DatabaseType, e: AnActionEvent) = true
    protected abstract fun perform(connectionName: String, e: AnActionEvent)

    /** The C# project targeted by Database tool window commands: the project selected in Solution Explorer, or picked. */
    protected fun targetProject(e: AnActionEvent): DotNetProject? {
        val ide = e.project ?: return null
        val candidates = SolutionProjects.all(ide).filter { it.canUseReverseEngineer() }
        return ProjectPicker.pick(ide, candidates)
    }
}

class DataSourceReverseEngineerAction : DataSourceAction() {
    override fun perform(connectionName: String, e: AnActionEvent) {
        val project = targetProject(e) ?: return
        ReverseEngineerHandler.reverseEngineerCodeFirst(project, uiHint = connectionName)
    }
}

class DataSourceErDiagramAction : DataSourceAction() {
    override fun perform(connectionName: String, e: AnActionEvent) {
        val project = targetProject(e) ?: return
        ErDiagramHandler.buildErDiagram(project, connectionName)
    }
}

class DataSourceAnalyzeAction : DataSourceAction() {
    override fun isVisible(type: DatabaseType, e: AnActionEvent) = type == DatabaseType.SQLServer
    override fun perform(connectionName: String, e: AnActionEvent) {
        val ide = e.project ?: return
        Ui.runBackground(ide, "Analyzing database") {
            val connection = DataConnections.getDataConnections(ide)[connectionName]
                ?: throw IllegalStateException("Connection '$connectionName' not found")
            DacpacAnalyzerHandler.generate(ide, connection.connectionString ?: "", isConnectionString = true)
        }
    }
}
