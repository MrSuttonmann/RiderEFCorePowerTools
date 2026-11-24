package com.efpt.rider.project

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.jetbrains.rider.projectView.workspace.ProjectModelEntity
import com.jetbrains.rider.projectView.workspace.containingProjectEntity
import com.jetbrains.rider.projectView.workspace.findProjects
import com.jetbrains.rider.projectView.workspace.getFile
import com.jetbrains.rider.projectView.workspace.getProjectModelEntities
import com.jetbrains.rider.projectView.workspace.getProjectModelEntity
import com.jetbrains.rider.projectView.workspace.isProject
import java.nio.file.Path

/** Access to the solution's projects and the Solution Explorer selection (VS.Solutions equivalent). */
object SolutionProjects {

    /** All loaded projects in the solution (VS.Solutions.GetAllProjectsAsync). */
    fun all(project: Project): List<DotNetProject> =
        WorkspaceModel.getInstance(project).findProjects()
            .mapNotNull { entityToProject(project, it) }
            .distinctBy { it.projectFile }

    /** The selected item's containing project (VS.Solutions.GetActiveProjectAsync). */
    fun active(e: AnActionEvent): DotNetProject? {
        val project = e.project ?: return null
        val entity = e.dataContext.getProjectModelEntity(true)?.let { if (it.isProject()) it else it.containingProjectEntity() }
        entity?.let { entityToProject(project, it) }?.let { return it }
        // Fallback: an editor / file context
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return containing(project, file)
    }

    /** Number of selected Solution Explorer items (VS.Solutions.GetActiveItemsAsync().Count()). */
    fun selectionCount(dataContext: DataContext): Int = dataContext.getProjectModelEntities(true).size

    /** The selected file, if exactly one file is selected (VS.Solutions.GetActiveItemAsync). */
    fun selectedFile(e: AnActionEvent): VirtualFile? {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (files != null && files.size != 1) return null
        return e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeUnless { it.isDirectory }
    }

    /** The project that contains [file] (FindProject(item)). */
    fun containing(project: Project, file: VirtualFile): DotNetProject? {
        val path = Path.of(file.path)
        return all(project)
            .filter { path.startsWith(it.directory) }
            .maxByOrNull { it.directory.nameCount }
    }

    fun byPath(project: Project, projectFile: String): DotNetProject? =
        all(project).firstOrNull { it.projectFile.toString().equals(Path.of(projectFile).toString(), ignoreCase = true) }

    private fun entityToProject(project: Project, entity: ProjectModelEntity): DotNetProject? {
        if (!entity.isProject()) return null
        val path = entity.getFile()?.toPath()
            ?: entity.url?.presentableUrl?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: return null
        if (!path.fileName.toString().contains('.')) return null
        return DotNetProject(project, path)
    }
}
