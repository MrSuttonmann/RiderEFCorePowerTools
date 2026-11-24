package com.efpt.rider.ui.dgml

import com.efpt.rider.util.Ui
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Rider has no DGML editor (Visual Studio's DGML viewer). Shows a .dgml file in the closest Rider
 * equivalent: a rendered diagram next to the raw .dgml file. Safe to call from any thread.
 *
 * Rider: the DGML is parsed (Nodes / Links / Categories / Styles) and rendered by [DgmlSvgRenderer]
 * into a self-contained SVG (own layered layout, DGML category colours, legend, per-node tooltips),
 * written to the temp folder and opened in the IDE's built-in image viewer (zoomable). The same SVG is
 * wrapped in a standalone HTML page with pan/zoom that can be opened in the browser from the
 * notification - useful for large models. The raw .dgml stays where it was written and is opened as
 * XML as well.
 *
 * Why not Mermaid in Markdown: Rider renders ```mermaid blocks only when the optional Mermaid
 * extension of the Markdown plugin is installed/enabled (downloaded on demand), Mermaid's own layout
 * degrades badly beyond a few dozen entities, and it cannot show the DGML colours/rows faithfully.
 * The SVG approach needs no network, CDN, extension or JCEF and scales to large models (row and
 * size caps keep the image usable).
 */
object DgmlPreview {
    private val LOG = logger<DgmlPreview>()

    fun open(project: Project, dgmlPath: String) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) app.executeOnPooledThread { openInternal(project, dgmlPath) }
        else openInternal(project, dgmlPath)
    }

    private fun openInternal(project: Project, dgmlPath: String) {
        val dgml = File(dgmlPath)
        Ui.refreshPaths(dgml.path)
        // The original DGML (XML) - kept available like the VS document.
        Ui.openFile(project, dgml.path, focus = false)

        val rendered = try {
            val document = DgmlDocument.parse(dgml)
            val renderer = DgmlSvgRenderer(document)
            val svg = renderer.render()
            val dir = File(System.getProperty("java.io.tmpdir"), "efpt-dgml" + File.separator + Integer.toHexString(dgml.absolutePath.lowercase().hashCode()))
            dir.mkdirs()
            val base = dgml.nameWithoutExtension
            val svgFile = File(dir, "$base.svg")
            svgFile.writeText(svg, StandardCharsets.UTF_8)
            val htmlFile = File(dir, "$base.html")
            htmlFile.writeText(html(base, svg), StandardCharsets.UTF_8)
            Triple(svgFile, htmlFile, renderer.boxCount)
        } catch (e: Exception) {
            LOG.warn("Unable to render $dgmlPath", e)
            Ui.notify(project, "Unable to render the DGML diagram (${e.message}). The .dgml file was opened as XML.", NotificationType.WARNING)
            return
        }

        val (svgFile, htmlFile, count) = rendered
        // Default editor of .svg = the IDE image viewer (with its zoom controls), not the XML text editor.
        Ui.openPreview(project, svgFile.path)

        NotificationGroupManager.getInstance().getNotificationGroup("Power Tools for EF Core")
            .createNotification(
                Ui.TITLE,
                "Diagram of ${dgml.name} ($count nodes). Rider has no DGML viewer: the diagram was rendered to SVG; the .dgml file is at ${dgml.path}",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple("Open in browser") { BrowserUtil.browse(htmlFile) })
            .addAction(NotificationAction.createSimple("Open .dgml") { Ui.openFile(project, dgml.path) })
            .notify(project)
    }

    /** Standalone page around the SVG with mouse-wheel zoom and drag-to-pan (no external resources). */
    private fun html(title: String, svg: String): String {
        val inline = svg.substringAfter("?>").trim()
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>${DgmlSvgRenderer.esc(title)}</title>
<style>
  html, body { margin: 0; height: 100%; background: #f3f3f3; font-family: Segoe UI, Arial, sans-serif; }
  #bar { position: fixed; top: 8px; right: 12px; z-index: 2; background: #fff; border: 1px solid #ccc; border-radius: 4px; padding: 4px 8px; }
  #bar button { margin: 0 2px; }
  #view { width: 100%; height: 100%; overflow: hidden; cursor: grab; }
  #view svg { transform-origin: 0 0; }
</style>
</head>
<body>
<div id="bar"><button id="zin">+</button><button id="zout">&minus;</button><button id="fit">Fit</button><button id="one">100%</button></div>
<div id="view">
$inline
</div>
<script>
(function () {
  var view = document.getElementById('view');
  var svg = view.querySelector('svg');
  var vb = svg.viewBox.baseVal;
  svg.setAttribute('width', vb.width);
  svg.setAttribute('height', vb.height);
  var s = 1, tx = 0, ty = 0;
  function apply() { svg.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + s + ')'; }
  function zoomAt(f, cx, cy) { var ns = Math.min(8, Math.max(0.02, s * f)); tx = cx - (cx - tx) * ns / s; ty = cy - (cy - ty) * ns / s; s = ns; apply(); }
  function fit() { s = Math.min(view.clientWidth / vb.width, view.clientHeight / vb.height, 1); tx = 0; ty = 0; apply(); }
  view.addEventListener('wheel', function (e) { e.preventDefault(); zoomAt(e.deltaY < 0 ? 1.15 : 1 / 1.15, e.clientX, e.clientY); }, { passive: false });
  var drag = null;
  view.addEventListener('mousedown', function (e) { drag = { x: e.clientX - tx, y: e.clientY - ty }; view.style.cursor = 'grabbing'; });
  window.addEventListener('mouseup', function () { drag = null; view.style.cursor = 'grab'; });
  window.addEventListener('mousemove', function (e) { if (drag) { tx = e.clientX - drag.x; ty = e.clientY - drag.y; apply(); } });
  document.getElementById('zin').onclick = function () { zoomAt(1.25, view.clientWidth / 2, view.clientHeight / 2); };
  document.getElementById('zout').onclick = function () { zoomAt(0.8, view.clientWidth / 2, view.clientHeight / 2); };
  document.getElementById('fit').onclick = fit;
  document.getElementById('one').onclick = function () { s = 1; tx = 0; ty = 0; apply(); };
  fit();
})();
</script>
</body>
</html>
"""
    }
}
