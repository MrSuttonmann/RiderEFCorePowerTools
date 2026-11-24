package com.efpt.rider.tools

import com.efpt.rider.model.GenerationType
import com.efpt.rider.project.DotNetProject
import java.io.File
import java.nio.file.Path

/**
 * Port of EFCorePowerTools.Handlers.ProcessLauncher: runs the bundled efpt{80,90,100} tool inside
 * the user's built project (dotnet exec with the project's deps.json / runtimeconfig.json) to
 * inspect DbContext models.
 */
class EfptLauncher(private val project: DotNetProject) {

    /**
     * @param outputPath the project's built assembly.
     * @param startupOutputPath assembly of the startup project (used to resolve configuration); defaults to [outputPath].
     */
    fun getOutput(
        outputPath: String,
        generationType: GenerationType,
        contextName: String? = null,
        connectionString: String? = null,
        startupOutputPath: String? = null,
    ): String {
        val toolDir = dropNetCoreFiles()
        val assembly = fixExtension(outputPath)
        val startup = fixExtension(startupOutputPath ?: outputPath)

        val toolArgs = mutableListOf<String>()
        when (generationType) {
            GenerationType.Ddl -> toolArgs += "ddl"
            GenerationType.DbContextList -> toolArgs += "contextlist"
            GenerationType.DbContextCompare -> toolArgs += "schemacompare"
            GenerationType.Dgml, GenerationType.DebugView -> Unit
        }
        toolArgs += assembly
        toolArgs += startup
        if (generationType == GenerationType.DbContextCompare) {
            toolArgs += connectionString ?: ""
            toolArgs += contextName ?: ""
        }

        val assemblyFile = File(assembly)
        val fileRoot = File(assemblyFile.parentFile, assemblyFile.nameWithoutExtension).path
        val depsFile = "$fileRoot.deps.json"
        val runtimeConfig = "$fileRoot.runtimeconfig.json"

        val dotnetArgs = mutableListOf("exec", "--depsfile", depsFile)
        project.getPackageFolders().forEach { folder ->
            dotnetArgs += listOf("--additionalprobingpath", folder.trimEnd('\\', '/'))
        }
        if (File(runtimeConfig).exists()) {
            dotnetArgs += listOf("--runtimeconfig", runtimeConfig)
        } else {
            project.getProperty("RuntimeFrameworkVersion")?.let { dotnetArgs += listOf("--fx-version", it) }
        }
        dotnetArgs += toolDir.resolve("efpt.dll").toString()
        dotnetArgs += toolArgs

        val cl = ProcessRunner.dotnet(*dotnetArgs.toTypedArray(), workingDirectory = assemblyFile.parentFile.toPath())
        return ProcessRunner.run(cl)
    }

    /** Extracts the efpt build that matches the project's EF Core version. */
    private fun dropNetCoreFiles(): Path {
        val target = BundledTools.tempDir.resolve("efpt")
        val version = project.containsEfCoreDesignReference().second
        val zip = when {
            version.startsWith("8.") -> "efpt80.exe.zip"
            version.startsWith("9.") -> "efpt90.exe.zip"
            version.startsWith("10.") -> "efpt100.exe.zip"
            else -> throw IllegalStateException(
                "Unsupported or missing EF Core version '$version' in project ${project.name}. EF Core 8, 9 or 10 is required (Microsoft.EntityFrameworkCore).",
            )
        }
        return BundledTools.extractFresh(zip, target)
    }

    companion object {
        private fun fixExtension(path: String): String =
            if (path.endsWith(".exe", ignoreCase = true)) path.dropLast(4) + ".dll" else path

        /**
         * Port of ProcessLauncher.BuildModelResult: splits efpt output into (context name, content) pairs.
         */
        fun buildModelResult(modelInfo: String): List<Pair<String, String>> {
            val result = mutableListOf<Pair<String, String>>()
            val contexts = modelInfo.split(Regex("DbContext:\\r?\\n")).filter { it.isNotEmpty() }
            for (context in contexts) {
                if (context.startsWith("info:", true) || context.startsWith("dbug:", true) || context.startsWith("warn:", true)) continue
                if (!context.contains("DebugView:", ignoreCase = true)) continue
                val parts = context.split(Regex("DebugView:\\r?\\n"))
                result += parts[0].trim() to (if (parts.size > 1) parts[1].trim() else "")
            }
            return result
        }
    }
}
