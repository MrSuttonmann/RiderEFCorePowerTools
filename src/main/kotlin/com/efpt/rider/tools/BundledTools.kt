package com.efpt.rider.tools

import com.intellij.openapi.application.PathManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import java.util.zip.ZipInputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Locates and unpacks the .NET runners shipped in the plugin's "tools" folder (same zips as the VSIX). */
@OptIn(ExperimentalPathApi::class)
object BundledTools {
    /** Plugin folder: the plugin jar lives in <plugin>/lib (public API; PluginManagerCore.getPlugin is internal). */
    private val pluginJar: Path? by lazy { PathManager.getJarForClass(BundledTools::class.java) }

    private val pluginDir: Path? get() = pluginJar?.parent?.takeIf { it.name == "lib" }?.parent

    /** Version from the plugin.xml in this plugin's jar (patched with the build version). */
    val pluginVersion: String by lazy {
        runCatching {
            JarFile(pluginJar!!.toFile()).use { jar ->
                val xml = jar.getInputStream(jar.getJarEntry("META-INF/plugin.xml")).reader().readText()
                Regex("""<version>\s*([^<\s]+)\s*</version>""").find(xml)!!.groupValues[1]
            }
        }.getOrDefault("0.0.0")
    }

    val toolsDir: Path
        get() = pluginDir?.resolve("tools")
            ?: throw IllegalStateException("Unable to locate the Power Tools for EF Core plugin folder")

    fun tool(fileName: String): Path {
        val path = toolsDir.resolve(fileName)
        if (!path.exists()) throw IllegalStateException("Bundled tool not found: $path")
        return path
    }

    val tempDir: Path get() = Path.of(System.getProperty("java.io.tmpdir"))

    /**
     * Extracts [zips] into a version-stamped folder under the temp directory (once), deleting stale
     * versions that share [rootPrefix]. Mirrors EfRevEngLauncher.DropNetCoreFiles.
     */
    @Synchronized
    fun extractVersioned(rootPrefix: String, zips: List<String>): Path {
        val target = tempDir.resolve(rootPrefix + pluginVersion)
        val marker = target.resolve(".efpt-extracted")
        if (marker.exists()) return target

        if (target.exists()) target.deleteRecursively()
        Files.createDirectories(target)
        zips.forEach { unzip(tool(it), target) }
        Files.writeString(marker, pluginVersion)

        try {
            tempDir.listDirectoryEntries("$rootPrefix*")
                .filter { it.isDirectory() && it.name != target.name }
                .forEach { runCatching { it.deleteRecursively() } }
        } catch (_: IOException) {
            // Ignore
        }
        return target
    }

    /** Extracts [zip] into a fresh [target] folder (deleting existing content). */
    @Synchronized
    fun extractFresh(zip: String, target: Path): Path {
        if (target.exists()) target.deleteRecursively()
        Files.createDirectories(target)
        unzip(tool(zip), target)
        return target
    }

    fun unzip(zip: Path, target: Path, overwrite: Boolean = true) {
        val root = target.toAbsolutePath().normalize()
        ZipInputStream(Files.newInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = root.resolve(entry.name.replace('\\', '/')).normalize()
                if (!out.startsWith(root)) throw IOException("Zip entry outside target: ${entry.name}")
                if (entry.isDirectory) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    if (overwrite || !out.exists()) {
                        Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }
}
