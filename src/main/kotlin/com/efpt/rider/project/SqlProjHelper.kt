package com.efpt.rider.project

import com.intellij.openapi.project.Project
import java.io.File

/** Port of SqlProjHelper: database definitions (SQL projects / linked .dacpac files) in the solution. Call off the EDT. */
object SqlProjHelper {
    /** GetDacpacProjectsInActiveSolutionAsync: .sqlproj first, then SQL csproj projects, then linked .dacpac files; null if none. */
    fun getDacpacProjectsInActiveSolution(project: Project): List<String>? {
        val result = linkedSetOf<String>()
        for (item in SolutionProjects.all(project)) {
            if (!item.directory.toFile().isDirectory) continue
            if (item.isSqlDatabaseProject) {
                result += item.fullPath
                continue
            }
            // Linked .dacpac files referenced by the project file from outside the project folder
            runCatching {
                val text = item.projectFile.toFile().readText()
                Regex("""Include="([^"]+\.dacpac)"""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
                    val path = File(item.directory.toFile(), m.groupValues[1].replace('\\', File.separatorChar)).canonicalPath
                    if (!path.startsWith(item.directory.toString(), ignoreCase = true) && File(path).exists()) result += path
                }
            }
        }
        if (result.isEmpty()) return null
        return result.filter { it.endsWith(".sqlproj", true) } +
            result.filter { it.endsWith(".csproj", true) } +
            result.filter { it.endsWith(".dacpac", true) }
    }

    /**
     * BuildSqlProjectAsync: returns the .dacpac for a SQL project (building it first), or the path itself
     * for a .dacpac. Throws when the build fails.
     */
    fun buildSqlProject(project: Project, sqlprojPath: String, mustBuild: Boolean = true): String? {
        if (sqlprojPath.endsWith(".dacpac", true)) return sqlprojPath
        val sqlProject = SolutionProjects.byPath(project, sqlprojPath) ?: DotNetProject(project, File(sqlprojPath).toPath())
        if (mustBuild) {
            val build = sqlProject.build()
            if (!build.success) throw IllegalStateException("Dacpac build failed\n\n${build.output.takeLast(4000)}")
        }
        val dacpac = sqlProject.getDacpacPath()
        if (!dacpac.isNullOrEmpty()) return dacpac
        throw IllegalStateException("Dacpac build failed, please pick the file manually")
    }
}
