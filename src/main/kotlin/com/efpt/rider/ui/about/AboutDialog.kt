package com.efpt.rider.ui.about

import com.efpt.rider.tools.BundledTools
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import javax.swing.Action
import javax.swing.JComponent

/** Port of AboutExtensionModel (the version is read from the plugin descriptor - IExtensionVersionService). */
object AboutExtensionModel {
    /** The original EF Core Power Tools project this plugin is ported from. */
    const val SOURCE_CODE_URL = "https://github.com/ErikEJ/EFCorePowerTools"

    /** The Rider port. */
    const val PORT_SOURCE_CODE_URL = "https://github.com/MrSuttonmann/RiderEFCorePowerTools"

    val extensionVersion: String? get() = BundledTools.pluginVersion.takeIf { it.isNotEmpty() && it != "0.0.0" }
}

/** Port of AboutDialog.xaml + AboutViewModel (texts from AboutLocale). */
class AboutDialog(project: Project?) : DialogWrapper(project, false) {
    init {
        title = "About"
        isResizable = false
        init()
    }

    /** AboutViewModel.FormatVersion. */
    private val versionText: String
        get() = AboutExtensionModel.extensionVersion?.let { "Version $it" } ?: "Version N/A"

    override fun createCenterPanel(): JComponent = panel {
        row { text("<b>Power Tools for EF Core</b>") }
        row { label(versionText) }
        // Rider: also show the host IDE version (VS shows only the extension version).
        row { label(ApplicationInfo.getInstance().let { "${it.fullApplicationName} (build ${it.build.asStringWithoutProductCode()})" }) }
        // Rider: this is an unofficial port, so it credits the original project instead of repeating
        // the original author's first-person review request (VS AboutDialog).
        row { text("A Rider port of <b>EF Core Power Tools</b> by Erik Ejlskov Jensen (MIT License):") }
            .topGap(com.intellij.ui.dsl.builder.TopGap.MEDIUM)
        row { browserLink(AboutExtensionModel.SOURCE_CODE_URL, AboutExtensionModel.SOURCE_CODE_URL) }
        row { text("Rider port source code and issues:") }.topGap(com.intellij.ui.dsl.builder.TopGap.MEDIUM)
        row { browserLink(AboutExtensionModel.PORT_SOURCE_CODE_URL, AboutExtensionModel.PORT_SOURCE_CODE_URL) }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction.apply { putValue(Action.NAME, "OK") })
}
