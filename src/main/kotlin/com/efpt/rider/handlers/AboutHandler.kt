package com.efpt.rider.handlers

import com.efpt.rider.ui.about.AboutDialog
import com.efpt.rider.util.Ui

/** Port of AboutHandler / AboutDialog. */
object AboutHandler {
    fun showDialog(ideProject: com.intellij.openapi.project.Project?) {
        try {
            Ui.invokeAndWait { AboutDialog(ideProject).show() }
        } catch (e: Exception) {
            Ui.logError(ideProject, emptyList(), e)
        }
    }
}
