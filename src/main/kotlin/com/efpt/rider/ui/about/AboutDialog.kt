package com.efpt.rider.ui.about

import com.efpt.rider.tools.BundledTools
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import javax.swing.Action
import javax.swing.JComponent

/** Port of AboutExtensionModel (the version is read from the plugin descriptor - IExtensionVersionService). */
object AboutExtensionModel {
    const val SOURCE_CODE_URL = "https://github.com/ErikEJ/EFCorePowerTools"
    const val MARKETPLACE_URL = "https://marketplace.visualstudio.com/items?itemName=ErikEJ.EFCorePowerTools&ssr=false#review-details"

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
        row { text("<b>EF Core Power Tools</b> for Rider") }
        row { label(versionText) }
        // Rider: also show the host IDE version (VS shows only the extension version).
        row { label(ApplicationInfo.getInstance().let { "${it.fullApplicationName} (build ${it.build.asStringWithoutProductCode()})" }) }
        row { text("For support, feedback and full source code, go to:") }.topGap(com.intellij.ui.dsl.builder.TopGap.MEDIUM)
        row { browserLink(AboutExtensionModel.SOURCE_CODE_URL, AboutExtensionModel.SOURCE_CODE_URL) }
        row { text("This add-in is FREE, but I would be grateful for a review:") }.topGap(com.intellij.ui.dsl.builder.TopGap.MEDIUM)
        row { link("Visual Studio Marketplace") { BrowserUtil.browse(AboutExtensionModel.MARKETPLACE_URL) } }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction.apply { putValue(Action.NAME, "OK") })
}
