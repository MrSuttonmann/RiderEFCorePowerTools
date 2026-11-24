package com.efpt.rider.handlers

import com.efpt.rider.handlers.analysis.AnalysisSupport
import com.efpt.rider.project.DotNetProject
import com.efpt.rider.util.Ui
import java.io.File
import java.util.UUID

/** Port of DgmlNugetHandler - "Add AsDgml() extension". */
object DgmlNugetHandler {
    // DgmlLocale (English)
    private const val INSTALLING_PACKAGE = "Installing DbContext Dgml extension package"
    private const val PACKAGE_INSTALLED = "Dgml package installed"

    fun installDgmlNuget(project: DotNetProject) {
        Ui.runBackground(project.ideProject, INSTALLING_PACKAGE) { indicator ->
            indicator.text = INSTALLING_PACKAGE
            Ui.status(project.ideProject, INSTALLING_PACKAGE)
            // Rider: VS starts `dotnet add package` fire-and-forget and ignores failures; here it is awaited
            // and a failure is reported instead of claiming success.
            val result = project.installPackage("ErikEJ.EntityFrameworkCore.DgmlBuilder", null)
            if (!result.success) {
                Ui.logError(project.ideProject, listOf(result.output), null)
                return@runBackground
            }
            Ui.status(project.ideProject, PACKAGE_INSTALLED)

            // Path.GetTempPath() + Path.GetRandomFileName() + ".txt"
            val path = File(AnalysisSupport.tempDir(), UUID.randomUUID().toString().replace("-", "").take(12) + ".txt")
            AnalysisSupport.writeUtf8WithBom(path, README)
            Ui.openPreview(project.ideProject, path.path)
        }
    }

    /** EFCorePowerTools.DgmlBuilder.readme.txt (embedded resource of the VS extension). */
    private val README: String = """** ErikEJ.EntityFrameworkCore.DgmlBuilder Readme **

To use the extension method to generate a DGML file of your DbContext model,
use code similar to this:

	using Microsoft.EntityFrameworkCore;


    using (var myContext = new MyDbContext())
    {
        System.IO.File.WriteAllText(System.IO.Path.GetTempFileName() + ".dgml",
			myContext.AsDgml(),
			System.Text.Encoding.UTF8);
    }""".replace("\n", System.lineSeparator())
}
