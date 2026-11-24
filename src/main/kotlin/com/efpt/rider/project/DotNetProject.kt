package com.efpt.rider.project

import com.efpt.rider.json.DcJson
import com.efpt.rider.json.obj
import com.efpt.rider.model.ReverseEngineerOptions
import com.efpt.rider.model.NuGetPackage
import com.efpt.rider.tools.ProcessRunner
import com.efpt.rider.util.PathHelper
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * A .NET project in the solution - the Rider counterpart of the VS Community.Toolkit `Project`
 * plus EFCorePowerTools.Extensions.ProjectExtensions.
 *
 * Cheap checks (used for action visibility) parse the project file; precise values are evaluated
 * with `dotnet msbuild -getProperty` and must be read off the EDT.
 */
class DotNetProject(val ideProject: Project, val projectFile: Path) {
    val name: String get() = projectFile.nameWithoutExtension
    val fullPath: String get() = projectFile.toString()
    val directory: Path get() = projectFile.parent

    override fun equals(other: Any?) = other is DotNetProject && other.projectFile == projectFile
    override fun hashCode() = projectFile.hashCode()
    override fun toString() = name

    // ------------------------------------------------------------------ cheap, XML based checks

    private val xml: ProjectXml get() = ProjectXml.of(projectFile)

    /** C# SDK-style project that is not an MSBuild.Sdk.SqlProj project ("CSharp & CPS & !MSBuild.Sdk.SqlProj.BuildTSqlScript"). */
    val isCSharpProject: Boolean
        get() = projectFile.extension.equals("csproj", true) && xml.isSdkStyle && !xml.isMsBuildSqlProj

    /** .sqlproj, MSBuild.Sdk.SqlProj or Microsoft.Build.Sql project. */
    val isSqlDatabaseProject: Boolean
        get() = projectFile.extension.equals("sqlproj", true) || isMsBuildSqlProjOrMsBuildSqlProject

    val isMsBuildSqlProjOrMsBuildSqlProject: Boolean
        get() = xml.isMsBuildSqlProj || (projectFile.extension.equals("csproj", true) && xml.hasSqlServerVersion)

    /** Target framework monikers in short form (net8.0, netstandard2.0, ...). */
    val targetFrameworks: List<String> get() = xml.targetFrameworks

    fun isNet80OrHigher(): Boolean = isNet80() || isNet90() || isNet100()
    fun isNet80(): Boolean = frameworkCheck("8")
    fun isNet90(): Boolean = frameworkCheck("9")
    fun isNet100(): Boolean = frameworkCheck("10") || frameworkCheck("11")
    fun isNetStandard(): Boolean = targetFrameworks.any { it.startsWith("netstandard2.", true) }

    private fun frameworkCheck(version: String) = targetFrameworks.any {
        it.equals("net$version.0", true) || it.startsWith("net$version.0-", true)
    }

    /** ProjectExtensions.CanUseReverseEngineerAsync. */
    fun canUseReverseEngineer(): Boolean = isCSharpProject && (isNet80OrHigher() || isNetStandard())

    /** ProjectExtensions.GetEFCoreVersionHintAsync - major version. */
    fun efCoreVersionHint(): Int = when {
        isNet100() -> 10
        isNet90() -> 9
        isNet80() -> 8
        isNetStandard() -> 2
        else -> 3
    }

    // ------------------------------------------------------------------ config files

    /** ProjectExtensions.GetConfigFiles: efpt.*config.json files (excluding bin/obj), or the default path. */
    fun getConfigFiles(): List<String> {
        val result = mutableListOf<String>()
        if (directory.exists()) {
            Files.walkFileTree(directory, object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult =
                    if (dir != directory && dir.fileName.toString().lowercase() in SKIPPED_FOLDERS) java.nio.file.FileVisitResult.SKIP_SUBTREE
                    else java.nio.file.FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    val n = file.fileName.toString().lowercase()
                    if (n.startsWith("efpt.") && n.endsWith("config.json")) result += file.toString()
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException) = java.nio.file.FileVisitResult.CONTINUE
            })
        }
        if (result.isEmpty()) result += directory.resolve("efpt.config.json").toString()
        return result.sorted()
    }

    /** ProjectExtensions.GetCliConfigFile. */
    fun getCliConfigFile(): String? = directory.resolve(CLI_CONFIG_FILE_NAME).takeIf { it.exists() }?.toString()

    /** ProjectExtensions.GetRenamingPath. */
    fun getRenamingPath(optionsPath: String?, navigationsFile: Boolean = false): String {
        val folder = if (optionsPath.isNullOrEmpty()) directory.toString() else File(optionsPath).parent
        return File(folder, if (navigationsFile) "efpt.property-renaming.json" else RENAMING_FILE_NAME).path
    }

    // ------------------------------------------------------------------ MSBuild evaluation (off EDT)

    private val propertyCache = ConcurrentHashMap<String, String>()
    private var propertyStamp = 0L

    /** Evaluates MSBuild properties (GetAttributeAsync equivalent). Results are cached until the project file changes. */
    fun getProperties(vararg names: String): Map<String, String> {
        val stamp = runCatching { Files.getLastModifiedTime(projectFile).toMillis() }.getOrDefault(0L)
        if (stamp != propertyStamp) {
            propertyCache.clear()
            propertyStamp = stamp
        }
        val missing = names.filterNot { propertyCache.containsKey(it) }
        if (missing.isNotEmpty()) propertyCache.putAll(MsBuild.evaluate(projectFile, missing, targetFrameworkForEvaluation()))
        return names.associateWith { propertyCache[it] ?: "" }
    }

    fun getProperty(name: String): String? = getProperties(name)[name]?.takeIf { it.isNotEmpty() }

    /** Multi-targeting projects are evaluated for their first target framework (like VS' active framework). */
    private fun targetFrameworkForEvaluation(): String? =
        if (xml.hasSingleTargetFramework) null else targetFrameworks.firstOrNull()

    val rootNamespace: String? get() = getProperty("RootNamespace") ?: name

    /** ProjectExtensions.GetOutPutAssemblyPathAsync. */
    fun getOutputAssemblyPath(): String? {
        val target = getProperty("TargetPath")
        if (!target.isNullOrEmpty() && File(target).exists()) return target
        val assemblyName = getProperty("AssemblyName") ?: name
        val outputPath = getProperty("OutputPath") ?: return null
        val abs = PathHelper.getAbsPath(outputPath, fullPath)
        listOf("$assemblyName.exe", "$assemblyName.dll").forEach { if (File(abs, it).exists()) return File(abs, it).path }
        return null
    }

    /** ProjectExtensions.GetDacpacPathAsync. */
    fun getDacpacPath(): String? = getProperty("SqlTargetPath") ?: getProperty("TargetPath")

    val projectAssetsFile: String? get() = getProperty("ProjectAssetsFile")

    // ------------------------------------------------------------------ NuGet (project.assets.json)

    /** Package id -> version of all libraries in the restore graph (LockFile.Libraries). */
    fun getAssetLibraries(): Map<String, String> {
        val assets = projectAssetsFile?.let { Path.of(it) } ?: directory.resolve("obj/project.assets.json")
        if (!assets.exists()) return emptyMap()
        return try {
            val libs = DcJson.parse(assets.readText()).asJsonObject.obj("libraries") ?: return emptyMap()
            libs.keySet().associate { key -> key.substringBefore('/') to key.substringAfter('/', "") }
        } catch (e: Exception) {
            LOG.warn(e)
            emptyMap()
        }
    }

    /** NuGet package folders from project.assets.json (used as --additionalprobingpath). */
    fun getPackageFolders(): List<String> {
        val assets = projectAssetsFile?.let { Path.of(it) } ?: return emptyList()
        if (!assets.exists()) return emptyList()
        return runCatching { DcJson.parse(assets.readText()).asJsonObject.obj("packageFolders")?.keySet()?.toList() }.getOrNull() ?: emptyList()
    }

    fun isInstalled(packageId: String): Boolean = getAssetLibraries().containsKey(packageId)

    /** Marks each of [packages] as installed or not. */
    fun markInstalled(packages: List<NuGetPackage>) {
        val libs = getAssetLibraries()
        packages.forEach { it.installed = libs.containsKey(it.packageId) }
    }

    /** ContainsEfCoreDesignReferenceAsync: (has design package, EF Core version). */
    fun containsEfCoreDesignReference(): Pair<Boolean, String> = containsReference("Microsoft.EntityFrameworkCore.Design")

    fun containsEfSchemaCompareReference(): Pair<Boolean, String> = containsReference("EfCore.SchemaCompare")

    private fun containsReference(designPackage: String): Pair<Boolean, String> {
        val libs = getAssetLibraries()
        return libs.containsKey(designPackage) to (libs["Microsoft.EntityFrameworkCore"] ?: "")
    }

    /** Cheap check usable in action update: is the package referenced (directly or via assets)? */
    fun referencesPackageQuick(packageId: String): Boolean =
        xml.packageReferences.any { it.equals(packageId, true) } || run {
            val assets = directory.resolve("obj/project.assets.json")
            assets.exists() && runCatching { assets.readText().contains("\"$packageId/", ignoreCase = true) }.getOrDefault(false)
        }

    /** Installs a NuGet package with `dotnet add package` (NuGetHelper.InstallPackage equivalent). */
    fun installPackage(packageId: String, version: String?): ProcessOutputResult {
        val args = mutableListOf("add", fullPath, "package", packageId)
        if (!version.isNullOrEmpty()) args += listOf("--version", version)
        val out = ProcessRunner.execute(ProcessRunner.dotnet(*args.toTypedArray(), workingDirectory = directory))
        return ProcessOutputResult(out.exitCode == 0, out.stdout + out.stderr)
    }

    /** Builds the project with `dotnet build`. */
    fun build(): ProcessOutputResult {
        val out = ProcessRunner.execute(ProcessRunner.dotnet("build", fullPath, "-nologo", "-v:q", workingDirectory = directory))
        if (out.exitCode == 0) propertyCache.remove("TargetPath")
        return ProcessOutputResult(out.exitCode == 0, out.stdout + out.stderr)
    }

    /** Equivalent of project.GetNeededPackagesAsync(options). */
    fun getNeededPackages(options: ReverseEngineerOptions, discoverMultipleResultSets: Boolean): List<NuGetPackage> {
        val packages = Providers.getNeededPackages(
            options.databaseType,
            options.useSpatial,
            options.useNodaTime,
            options.useDateOnlyTimeOnly,
            options.useHierarchyId,
            discoverMultipleResultSets,
            options.tables?.any { it.objectType == com.efpt.rider.model.ObjectType.Procedure } ?: false,
            options.codeGenerationMode,
        )
        markInstalled(packages)
        return packages
    }

    companion object {
        private val LOG = logger<DotNetProject>()
        const val CLI_CONFIG_FILE_NAME = "efcpt-config.json"
        const val RENAMING_FILE_NAME = "efpt.renaming.json"
        private val SKIPPED_FOLDERS = setOf("bin", "obj", "node_modules", ".git", ".vs", ".idea")
    }
}

data class ProcessOutputResult(val success: Boolean, val output: String)

/** Lightweight, cached parse of a project file (and Directory.Build.props) for fast checks. */
internal class ProjectXml private constructor(
    val isSdkStyle: Boolean,
    val isMsBuildSqlProj: Boolean,
    val hasSqlServerVersion: Boolean,
    val targetFrameworks: List<String>,
    val hasSingleTargetFramework: Boolean,
    val packageReferences: List<String>,
) {
    companion object {
        private data class Entry(val stamp: Long, val value: ProjectXml)

        private val cache = ConcurrentHashMap<Path, Entry>()

        fun of(file: Path): ProjectXml {
            val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(-1L)
            cache[file]?.let { if (it.stamp == stamp) return it.value }
            val parsed = parse(file)
            cache[file] = Entry(stamp, parsed)
            return parsed
        }

        private fun parse(file: Path): ProjectXml {
            if (!file.exists()) return ProjectXml(false, false, false, emptyList(), true, emptyList())
            return try {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = false
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                val doc = factory.newDocumentBuilder().parse(file.toFile())
                val root = doc.documentElement
                val sdkAttr = root.getAttribute("Sdk") ?: ""
                val sdkElements = doc.getElementsByTagName("Sdk")
                val sdkNames = buildList {
                    add(sdkAttr)
                    for (i in 0 until sdkElements.length) add((sdkElements.item(i) as org.w3c.dom.Element).getAttribute("Name"))
                    val imports = doc.getElementsByTagName("Import")
                    for (i in 0 until imports.length) add((imports.item(i) as org.w3c.dom.Element).getAttribute("Sdk"))
                }.joinToString(";")
                val isSdk = sdkNames.isNotBlank()
                val isSqlSdk = sdkNames.contains("MSBuild.Sdk.SqlProj", true) || sdkNames.contains("Microsoft.Build.Sql", true)

                fun text(tag: String): String? {
                    val nodes = doc.getElementsByTagName(tag)
                    return if (nodes.length > 0) nodes.item(0).textContent?.trim() else null
                }

                var single = text("TargetFramework")
                var multi = text("TargetFrameworks")
                if (single.isNullOrEmpty() && multi.isNullOrEmpty()) {
                    // Fall back to Directory.Build.props up the tree
                    var dir = file.parent
                    while (dir != null) {
                        val props = dir.resolve("Directory.Build.props")
                        if (props.exists()) {
                            val pd = factory.newDocumentBuilder().parse(props.toFile())
                            val tf = pd.getElementsByTagName("TargetFramework")
                            val tfs = pd.getElementsByTagName("TargetFrameworks")
                            if (tf.length > 0) single = tf.item(0).textContent.trim()
                            if (tfs.length > 0) multi = tfs.item(0).textContent.trim()
                            break
                        }
                        dir = dir.parent
                    }
                }
                val tfms = when {
                    !multi.isNullOrEmpty() -> multi.split(';').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("$") }
                    !single.isNullOrEmpty() -> listOf(single)
                    else -> emptyList()
                }
                val refs = doc.getElementsByTagName("PackageReference")
                val pkgs = (0 until refs.length).mapNotNull { (refs.item(it) as org.w3c.dom.Element).getAttribute("Include").takeIf { s -> s.isNotEmpty() } }
                ProjectXml(isSdk, isSqlSdk, text("SqlServerVersion") != null, tfms, multi.isNullOrEmpty(), pkgs)
            } catch (_: Exception) {
                ProjectXml(false, false, false, emptyList(), true, emptyList())
            }
        }
    }
}
