package com.efpt.rider.tools

import com.efpt.rider.json.DcJson
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.ObjectType
import com.efpt.rider.model.ReverseEngineerCommandOptions
import com.efpt.rider.model.SerializationTableModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end contract test against the real efreveng runner (opt-in).
 * Set EFPT_E2E_RUNNER to a folder with efreveng80.dll extracted and EFPT_E2E_DB to a SQLite database
 * containing tables Artist and Album and view AlbumView.
 */
class EfRevEngContractTest {
    private val runner = System.getenv("EFPT_E2E_RUNNER")
    private val db = System.getenv("EFPT_E2E_DB")

    private fun run(vararg args: String): String {
        val p = ProcessBuilder(listOf("dotnet", File(runner, "efreveng80.dll").path) + args).redirectErrorStream(false).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return out
    }

    @Test
    fun `discover tables and generate code through the efreveng protocol`() {
        assumeTrue("EFPT_E2E_RUNNER / EFPT_E2E_DB not set", runner != null && db != null)
        val connection = "Data Source=$db"

        val discovered = ResultDeserializer.buildTableResult(run("False", "4", connection))
        assertEquals(setOf("Album", "Artist", "AlbumView"), discovered.map { it.name }.toSet())
        assertTrue(discovered.first { it.name == "Album" }.columns!!.first { it.name == "AlbumId" }.isPrimaryKey)

        val project = File(db).parentFile.resolve("proj").apply { mkdirs() }
        val options = ReverseEngineerCommandOptions().apply {
            databaseType = DatabaseType.SQLite
            connectionString = connection
            projectPath = project.path
            optionsPath = project.path
            outputPath = "Models"
            projectRootNamespace = "E2E"
            contextClassName = "ChinookContext"
            useInflector = true
            useNullableReferences = true
            useDateOnlyTimeOnly = true
            selectedHandlebarsLanguage = 2
            tables = listOf(
                SerializationTableModel("Album", ObjectType.Table),
                SerializationTableModel("Artist", ObjectType.Table),
                SerializationTableModel("AlbumView", ObjectType.View),
            )
        }
        val json = File.createTempFile("efpt", ".json")
        DcJson.writeFile(json.toPath(), options.write())
        val result = ResultDeserializer.buildResult(run(json.path))

        assertTrue("errors: ${result.entityErrors}", result.entityErrors.isEmpty())
        assertTrue(File(result.contextFilePath!!).readText().contains("class ChinookContext"))
        assertEquals(setOf("Album.cs", "Artist.cs", "AlbumView.cs"), result.entityTypeFilePaths.map { File(it).name }.toSet())
    }
}
