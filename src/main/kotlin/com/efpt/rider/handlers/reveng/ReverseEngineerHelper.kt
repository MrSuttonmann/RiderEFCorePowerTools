package com.efpt.rider.handlers.reveng

import com.efpt.rider.connections.ConnectionStringBuilder
import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.NuGetPackage
import com.efpt.rider.model.ReverseEngineerOptions
import com.efpt.rider.model.ReverseEngineerResult
import com.efpt.rider.model.SerializationTableModel
import com.efpt.rider.tools.BundledTools
import com.efpt.rider.ui.CodeGenerationItem
import com.efpt.rider.ui.reveng.TemplateTypeItem
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Port of EFCorePowerTools.Helpers.ReverseEngineerHelper (+ RevEng.Common.DbContextNamer). */
object ReverseEngineerHelper {
    private val nl: String get() = System.lineSeparator()

    const val README_NAME = "PowerToolsReadMe.md"

    fun normalizeTables(tables: List<SerializationTableModel>, shouldFix: Boolean): List<SerializationTableModel> {
        val result = mutableListOf<SerializationTableModel>()
        for (table in tables) {
            if (shouldFix && !table.name.startsWith("[", ignoreCase = true)) {
                table.name = "[" + replaceFirst(table.name, ".", "].[") + "]"
            }
            result += table
        }
        return result
    }

    /**
     * DropTemplates: extracts the bundled handlebars / T4 template zip into the CodeTemplates folder
     * (when missing or empty) and checks the T4 template version. Returns a warning text or "".
     */
    fun dropTemplates(
        optionsPath: String,
        projectPath: String,
        codeGenerationMode: CodeGenerationMode,
        useHandlebars: Boolean,
        selectedOption: Int = 0,
    ): String {
        if (codeGenerationMode != CodeGenerationMode.EFCore10) {
            throw IllegalArgumentException("Unsupported code generation mode for templates: $codeGenerationMode")
        }
        val t4Version = if (selectedOption == 4) "1000_Split" else "1000"
        val zipName = if (useHandlebars) "CodeTemplates1000.zip" else "T4_$t4Version.zip"

        val userTemplateZip = File(optionsPath, "CodeTemplates.zip")
        val toDir = if (useHandlebars) File(optionsPath, "CodeTemplates") else File(projectPath, "CodeTemplates")
        var templateZip = BundledTools.tool(zipName)
        if (userTemplateZip.exists() && useHandlebars) templateZip = userTemplateZip.toPath()

        if (!toDir.isDirectory || isDirectoryEmpty(toDir.path)) {
            toDir.mkdirs()
            // ZipFile.ExtractToDirectory fails on existing files; the folder is empty here.
            BundledTools.unzip(templateZip, toDir.toPath(), overwrite = false)

            // Using POCO Templates
            if (selectedOption == 3) {
                val target = File(File(toDir, "EFCore"), "EntityType.t4")
                target.parentFile.mkdirs()
                // Rider: POCOEntityType.t4 ships as a plugin resource instead of next to the VSIX assembly.
                ReverseEngineerHelper::class.java.getResourceAsStream("/templates/POCOEntityType.t4")?.use {
                    Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } ?: throw IllegalStateException("POCOEntityType.t4 resource not found")
            }
        }

        if (!useHandlebars && toDir.isDirectory && selectedOption != 3) {
            val error = "The latest T4 template version could not be found, looking for 'Template version: $t4Version' in the T4 file - please update your T4 templates, for example by renaming the CodeTemplates folder."
            val check = "Template version: $t4Version"
            for (name in listOf("EntityType.t4", "DbContext.t4")) {
                val target = File(File(toDir, "EFCore"), name)
                if (target.exists()) {
                    val content = target.readText(StandardCharsets.UTF_8)
                    if (!content.contains(check, ignoreCase = true)) return error
                }
            }
        }
        return ""
    }

    /**
     * ReportRevEngErrors. The C# original appends a hint to EntityWarnings in place; here the
     * (possibly extended) warning list is returned alongside the text.
     */
    fun reportRevEngErrors(revEngResult: ReverseEngineerResult, missingProviderPackage: String?): Pair<String, List<String>> {
        val errors = StringBuilder()
        val warnings = revEngResult.entityWarnings.toMutableList()
        if (revEngResult.entityErrors.isEmpty()) {
            errors.append(RevEngLocale.ModelGeneratedSuccesfully + nl)
        } else {
            errors.append(RevEngLocale.CheckOutputWindowForErrors + nl)
        }
        if (warnings.isNotEmpty()) {
            if (warnings.any { it.contains("Could not find type mapping", ignoreCase = true) }) {
                warnings += "Consider enabling more type mappings via 'Advanced' options."
            }
            errors.append(RevEngLocale.CheckOutputWindowForWarnings + nl)
        }
        if (!missingProviderPackage.isNullOrEmpty()) {
            errors.append(nl)
            errors.append(RevEngLocale.PackageNotFoundInProject.format(missingProviderPackage))
        }
        return errors.toString() to warnings
    }

    /** True when [errors] is the plain success text of [reportRevEngErrors]. */
    fun isSuccessText(errors: String) = errors == RevEngLocale.ModelGeneratedSuccesfully + nl

    fun getReadMeText(options: ReverseEngineerOptions, content: String): String =
        content.replace("[ProviderName]", getProviderName(options.databaseType))
            .replace("[ConnectionString]", (options.connectionString ?: "").replace("\\", "\\\\"))
            .replace("[ContextName]", options.contextClassName ?: "")

    fun getReadMeText(options: ReverseEngineerOptions, content: String, packages: List<NuGetPackage>): String {
        val extraPackages = packages.filter { !it.isMainProviderPackage && it.useMethodName != null }.map { "Use${it.useMethodName}()" }
        val useText = if (extraPackages.isNotEmpty()) ",$nl           x => x." + extraPackages.joinToString(".") else ""
        return content.replace("[ProviderName]", getProviderName(options.databaseType))
            .replace("[ConnectionString]", (options.connectionString ?: "").replace("\\", "\\\\"))
            .replace("[UseList]", useText)
            .replace("[ContextName]", options.contextClassName ?: "")
    }

    fun addResultToFinalText(finalText: String, revEngResult: ReverseEngineerResult): String {
        if (!revEngResult.hasIssues) return finalText
        val warningText = StringBuilder()
        warningText.append("Some issues were discovered during reverse engineering, consider addressing them:").append(nl)
        warningText.append(nl)
        revEngResult.entityErrors.forEach { warningText.append(it).append(nl).append(nl) }
        revEngResult.entityWarnings.forEach { warningText.append(it).append(nl).append(nl) }
        return finalText + nl + warningText
    }

    fun isDirectoryEmpty(path: String): Boolean = File(path).list().isNullOrEmpty()

    /** CalculateAllowedVersions: EF Core 10 is the only offered version (upstream #3562). */
    fun calculateAllowedVersions(codeGenerationMode: CodeGenerationMode): Pair<CodeGenerationMode, List<CodeGenerationItem>> {
        val list = listOf(CodeGenerationItem(CodeGenerationMode.EFCore10.value, "EF Core 10"))
        var mode = codeGenerationMode
        if (list.none { it.key == mode.value }) mode = CodeGenerationMode.fromValue(list.first().key)
        return mode to list
    }

    fun calculateAllowedTemplates(codeGenerationMode: CodeGenerationMode): List<TemplateTypeItem> =
        if (codeGenerationMode == CodeGenerationMode.EFCore10) {
            listOf(
                TemplateTypeItem(2, "C# - T4"),
                TemplateTypeItem(4, "C# - T4 (Split DbContext)"),
                TemplateTypeItem(0, "C# - Handlebars"),
                TemplateTypeItem(1, "TypeScript - Handlebars"),
                TemplateTypeItem(3, "C# - T4 (POCO)"),
            )
        } else {
            emptyList()
        }

    /** RevEng.Common.DbContextNamer.GetDatabaseName. */
    fun getDatabaseName(connectionString: String?, dbType: DatabaseType): String {
        requireNotNull(connectionString) { "connectionString" }
        if (connectionString.endsWith(".dacpac", ignoreCase = true)) return File(connectionString).nameWithoutExtension
        val builder = ConnectionStringBuilder(connectionString)
        return builder["Initial Catalog"] ?: builder["Database"] ?: builder["Data Source"] ?: builder["DataSource"] ?: dbType.name
    }

    /** Loads the PowerToolsReadMe.md template (a plugin resource; VS reads it next to the assembly). */
    fun readMeTemplate(): String =
        ReverseEngineerHelper::class.java.getResourceAsStream("/$README_NAME")?.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
            ?: throw IllegalStateException("$README_NAME resource not found")

    private fun replaceFirst(text: String, search: String, replace: String): String {
        val pos = text.indexOf(search)
        if (pos < 0) return text
        return text.substring(0, pos) + replace + text.substring(pos + search.length)
    }

    private fun getProviderName(databaseType: DatabaseType): String = when (databaseType) {
        DatabaseType.Undefined -> "[ProviderName]"
        DatabaseType.SQLServer -> "SqlServer"
        DatabaseType.SQLite -> "Sqlite"
        DatabaseType.Npgsql -> "Npgsql"
        DatabaseType.Mysql -> "Mysql"
        DatabaseType.Oracle -> "Oracle"
        DatabaseType.SQLServerDacpac -> "SqlServer"
        DatabaseType.Firebird -> "Firebird"
        DatabaseType.Snowflake -> "Snowflake"
    }
}
