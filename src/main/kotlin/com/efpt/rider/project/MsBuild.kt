package com.efpt.rider.project

import com.efpt.rider.json.DcJson
import com.efpt.rider.json.obj
import com.efpt.rider.tools.ProcessRunner
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Path

/** Evaluates MSBuild properties through `dotnet msbuild -getProperty` (requires .NET SDK 8+). */
object MsBuild {
    private val LOG = logger<MsBuild>()

    fun evaluate(projectFile: Path, properties: List<String>, targetFramework: String? = null): Map<String, String> {
        if (properties.isEmpty()) return emptyMap()
        val args = mutableListOf("msbuild", projectFile.toString(), "-nologo")
        properties.forEach { args += "-getProperty:$it" }
        if (targetFramework != null) args += "-p:TargetFramework=$targetFramework"
        val output = ProcessRunner.execute(ProcessRunner.dotnet(*args.toTypedArray(), workingDirectory = projectFile.parent))
        if (output.exitCode != 0) {
            LOG.warn("dotnet msbuild failed for $projectFile: ${output.stderr}${output.stdout}")
            return emptyMap()
        }
        val text = output.stdout.trim()
        if (properties.size == 1) return mapOf(properties[0] to text)
        return try {
            val props = DcJson.parse(text).asJsonObject.obj("Properties") ?: return emptyMap()
            properties.associateWith { props.get(it)?.takeUnless { e -> e.isJsonNull }?.asString ?: "" }
        } catch (e: Exception) {
            LOG.warn("Unable to parse msbuild output: $text", e)
            emptyMap()
        }
    }
}
