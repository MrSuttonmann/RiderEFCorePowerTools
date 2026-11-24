package com.efpt.rider.tools

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.io.FileUtil
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Locates and unpacks the .NET runners shipped in the plugin's "tools" folder (same zips as the VSIX). */
object BundledTools {
    const val PLUGIN_ID = "com.efpt.rider"

    private val plugin get() = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))

    val pluginVersion: String get() = plugin?.version ?: "0.0.0"

    val toolsDir: Path
        get() = plugin?.pluginPath?.resolve("tools")
            ?: throw IllegalStateException("Unable to locate the EF Core Power Tools plugin folder")

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

        if (target.exists()) FileUtil.delete(target)
        Files.createDirectories(target)
        zips.forEach { unzip(tool(it), target) }
        Files.writeString(marker, pluginVersion)

        try {
            tempDir.listDirectoryEntries("$rootPrefix*")
                .filter { it.isDirectory() && it.name != target.name }
                .forEach { runCatching { FileUtil.delete(it) } }
        } catch (_: IOException) {
            // Ignore
        }
        return target
    }

    /** Extracts [zip] into a fresh [target] folder (deleting existing content). */
    @Synchronized
    fun extractFresh(zip: String, target: Path): Path {
        if (target.exists()) FileUtil.delete(target)
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
