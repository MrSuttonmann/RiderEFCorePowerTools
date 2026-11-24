package com.efpt.rider.ui.dgml

import java.awt.Font
import java.awt.FontMetrics
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Renders a [DgmlDocument] as a self-contained SVG "entity diagram":
 *
 * * nodes that contain leaf nodes (DGML groups such as EntityType / Table) become boxes whose rows are
 *   their contained leaf nodes (properties / columns), coloured with the DGML category styles;
 * * container-of-containers (Model / Database / Schema) become the diagram title and box subtitles;
 * * all other links (foreign keys) are drawn as curves between boxes, anchored on the rows they
 *   reference when possible (DGML From/To or row-level link endpoints);
 * * boxes are laid out in layers following the link direction (LeftToRight like the DGML
 *   GraphDirection), ordered with a barycenter heuristic; unconnected boxes go to a grid below.
 *
 * Only plain SVG presentation attributes are used (no CSS, markers or scripts) so the image renders
 * in the IDE's SVG viewer as well as in any browser.
 */
class DgmlSvgRenderer(private val doc: DgmlDocument) {

    private class Row(val id: String, val label: String, val color: String?, val tooltip: String)

    private class Box(val id: String, val title: String, val subtitle: String?, val color: String?, val tooltip: String) {
        val rows = mutableListOf<Row>()
        var hiddenRows = 0
        var collapsedRows = false
        var w = 0.0
        var h = 0.0
        var x = 0.0
        var y = 0.0
        var layer = 0
        var order = 0.0
        val right get() = x + w
    }

    private class Edge(val from: Box, val fromRow: Int?, val to: Box, val toRow: Int?, var label: String?)

    private val fm: FontMetrics = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().let {
        it.getFontMetrics(Font("SansSerif", Font.PLAIN, FONT_SIZE))
    }
    private val fmBold: FontMetrics = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().let {
        it.getFontMetrics(Font("SansSerif", Font.BOLD, FONT_SIZE + 1))
    }

    private val boxes = mutableListOf<Box>()
    private val boxById = HashMap<String, Box>()
    private val rowOwner = HashMap<String, Pair<Box, Int>>()
    private val edges = mutableListOf<Edge>()
    private var title: String = "Diagram"
    private val usedCategories = LinkedHashSet<String>()

    /**
     * Renders the SVG. [maxDimension] and [maxPixels] cap the intrinsic image size (the drawing is scaled via
     * the viewBox, not cropped) so that raster-based viewers do not allocate huge bitmaps for large models.
     */
    fun render(maxDimension: Int = 8000, maxPixels: Double = 36_000_000.0): String {
        buildModel()
        layout()
        return draw(maxDimension, maxPixels)
    }

    val boxCount: Int get() = boxes.size

    // ------------------------------------------------------------------------------------- model

    private fun buildModel() {
        val nodes = doc.nodes
        // Implicit nodes referenced only by links (DGML creates them on the fly).
        for (l in doc.links) {
            for (id in listOf(l.source, l.target)) {
                if (id.isNotEmpty() && id !in nodes) nodes[id] = DgmlNode(id, mutableMapOf(), mutableListOf())
            }
        }

        val children = LinkedHashMap<String, MutableList<String>>()
        val parent = HashMap<String, String>()
        for (l in doc.links.filter { it.isContainment }) {
            if (l.source == l.target || l.target in parent) continue
            parent[l.target] = l.source
            children.getOrPut(l.source) { mutableListOf() }.let { if (l.target !in it) it += l.target }
        }

        fun isLeaf(id: String) = children[id].isNullOrEmpty()
        fun isBox(id: String) =
            (!isLeaf(id) && children[id]!!.any { isLeaf(it) }) || (isLeaf(id) && id !in parent)

        val totalRows = nodes.keys.count { isLeaf(it) && it in parent }
        val collapse = totalRows > MAX_TOTAL_ROWS

        val frames = nodes.keys.filter { !isLeaf(it) && !isBox(it) }
        title = frames.firstOrNull { it !in parent }?.let { nodes[it]!!.label }
            ?: nodes.values.firstOrNull()?.label ?: "Diagram"

        for ((id, node) in nodes) {
            if (!isBox(id)) continue
            val p = parent[id]
            // Subtitle: the containing group when it is not the root of the graph (e.g. the schema of a table).
            val subtitle = p?.takeIf { it in parent || isBox(it) }?.let { nodes[it]?.label }
            node.category?.let { usedCategories += it }
            val box = Box(id, node.label, subtitle, color(node), tooltip(node))
            val leafChildren = children[id].orEmpty().filter { isLeaf(it) }
            box.collapsedRows = collapse
            leafChildren.forEachIndexed { index, childId ->
                val child = nodes[childId] ?: return@forEachIndexed
                child.category?.let { usedCategories += it }
                if (!collapse && index < MAX_ROWS_PER_BOX) {
                    box.rows += Row(childId, child.label, color(child), tooltip(child))
                    rowOwner[childId] = box to box.rows.lastIndex
                } else {
                    box.hiddenRows++
                    rowOwner[childId] = box to -1
                }
            }
            boxes += box
            boxById[id] = box
        }

        fun endpoint(id: String, hint: String?): Pair<Box, Int?>? {
            hint?.let { h -> rowOwner[h]?.let { (b, r) -> if (b.id == id || boxById[id] == null) return b to r.takeIf { it >= 0 } } }
            boxById[id]?.let { return it to null }
            rowOwner[id]?.let { (b, r) -> return b to r.takeIf { it >= 0 } }
            return null
        }

        val seen = HashMap<String, Edge>()
        for (l in doc.links) {
            if (l.isContainment) continue
            val from = endpoint(l.source, l.attributes["From"]) ?: continue
            val to = endpoint(l.target, l.attributes["To"]) ?: continue
            val key = "${from.first.id}\u0000${from.second}\u0000${to.first.id}\u0000${to.second}"
            val label = l.label
            val existing = seen[key]
            if (existing != null) {
                if (label != null && existing.label?.split(", ")?.contains(label) != true) {
                    existing.label = listOfNotNull(existing.label, label).joinToString(", ")
                }
                continue
            }
            val e = Edge(from.first, from.second, to.first, to.second, label)
            seen[key] = e
            edges += e
        }

        for (b in boxes) measure(b)
    }

    private fun color(node: DgmlNode): String? =
        node.attributes["Background"]?.let { toCss(it) }
            ?: node.categories.firstNotNullOfOrNull { c -> doc.categoryBackgrounds[c]?.let { toCss(it) } }

    private fun tooltip(node: DgmlNode): String {
        val skip = setOf("Id", "Label", "Group", "Category")
        val parts = mutableListOf(node.label)
        node.category?.let { parts += "Category: $it" }
        node.attributes.filter { (k, v) -> k !in skip && v.isNotBlank() }.forEach { (k, v) -> parts += "$k: $v" }
        val text = parts.joinToString("\n")
        return if (text.length > 600) text.take(600) + "…" else text
    }

    private fun textWidth(text: String, bold: Boolean = false) =
        (if (bold) fmBold else fm).stringWidth(text) * 1.08 + 2

    private fun measure(b: Box) {
        var w = max(textWidth(b.title, true), b.subtitle?.let { textWidth("«$it»") } ?: 0.0) + 2 * PAD
        for (r in b.rows) w = max(w, textWidth(r.label) + 2 * PAD + SWATCH + 6)
        if (b.hiddenRows > 0) w = max(w, textWidth(moreText(b)) + 2 * PAD)
        b.w = ceil(min(max(w, MIN_BOX_WIDTH), MAX_BOX_WIDTH))
        val header = headerHeight(b)
        val rowsCount = b.rows.size + (if (b.hiddenRows > 0) 1 else 0)
        b.h = header + rowsCount * ROW_HEIGHT + (if (rowsCount > 0) 6 else 0)
    }

    private fun headerHeight(b: Box): Double = (if (b.subtitle != null) HEADER_HEIGHT + 14 else HEADER_HEIGHT).toDouble()

    private fun moreText(b: Box) =
        if (b.collapsedRows) "${b.hiddenRows} members (model too large to list)" else "… ${b.hiddenRows} more"

    // ------------------------------------------------------------------------------------- layout

    private var contentTop = 0.0

    private fun layout() {
        val connected = boxes.filter { b -> edges.any { (it.from === b || it.to === b) && it.from !== it.to } }.toMutableList()
        val isolated = boxes.filter { it !in connected }

        // Successors following the DGML direction (source left of target), back edges removed by DFS.
        val succ = HashMap<Box, MutableSet<Box>>()
        val pred = HashMap<Box, MutableSet<Box>>()
        for (e in edges) if (e.from !== e.to) {
            succ.getOrPut(e.from) { LinkedHashSet() } += e.to
        }
        val state = HashMap<Box, Int>()
        val dag = HashMap<Box, MutableSet<Box>>()
        for (start in connected) {
            if (state[start] != null) continue
            // Iterative DFS
            val stack = ArrayDeque<Pair<Box, Iterator<Box>>>()
            state[start] = 1
            stack.addLast(start to (succ[start]?.toList() ?: emptyList()).iterator())
            while (stack.isNotEmpty()) {
                val (v, it) = stack.last()
                if (it.hasNext()) {
                    val w = it.next()
                    when (state[w]) {
                        null -> {
                            dag.getOrPut(v) { LinkedHashSet() } += w
                            state[w] = 1
                            stack.addLast(w to (succ[w]?.toList() ?: emptyList()).iterator())
                        }
                        2 -> dag.getOrPut(v) { LinkedHashSet() } += w
                        else -> Unit // back edge: ignored for ranking
                    }
                } else {
                    state[v] = 2
                    stack.removeLast()
                }
            }
        }
        for ((v, ws) in dag) for (w in ws) pred.getOrPut(w) { LinkedHashSet() } += v

        // Longest-path layering (Kahn).
        val indeg = HashMap<Box, Int>()
        connected.forEach { indeg[it] = pred[it]?.size ?: 0 }
        val queue = ArrayDeque(connected.filter { indeg[it] == 0 })
        connected.forEach { it.layer = 0 }
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (w in dag[v].orEmpty()) {
                w.layer = max(w.layer, v.layer + 1)
                indeg[w] = indeg[w]!! - 1
                if (indeg[w] == 0) queue.addLast(w)
            }
        }
        // Pull sources next to their first successor to shorten edges.
        for (v in connected) {
            if (pred[v].isNullOrEmpty()) {
                val m = dag[v]?.minOfOrNull { it.layer }
                if (m != null && m - 1 > v.layer) v.layer = m - 1
            }
        }

        val layerCount = (connected.maxOfOrNull { it.layer } ?: -1) + 1
        val layers = List(layerCount) { mutableListOf<Box>() }
        connected.forEach { layers[it.layer] += it }
        layers.forEach { l -> l.forEachIndexed { i, b -> b.order = i.toDouble() } }

        // Barycenter ordering sweeps using all edges (undirected neighbours in adjacent layers).
        val neighbours = HashMap<Box, MutableList<Box>>()
        for (e in edges) if (e.from !== e.to) {
            neighbours.getOrPut(e.from) { mutableListOf() } += e.to
            neighbours.getOrPut(e.to) { mutableListOf() } += e.from
        }
        repeat(6) { sweep ->
            val range = if (sweep % 2 == 0) (1 until layerCount) else (layerCount - 2 downTo 0)
            for (li in range) {
                val ref = if (sweep % 2 == 0) li - 1 else li + 1
                for (b in layers[li]) {
                    val ns = neighbours[b].orEmpty().filter { it.layer == ref }
                    if (ns.isNotEmpty()) b.order = ns.sumOf { it.order } / ns.size
                }
                layers[li].sortBy { it.order }
                layers[li].forEachIndexed { i, b -> b.order = i.toDouble() }
            }
        }

        contentTop = TOP_MARGIN + legendHeight()

        // Split very tall layers into several sub-columns.
        val totalHeight = connected.sumOf { it.h + V_GAP }
        val maxColumnHeight = max(2400.0, sqrt(max(totalHeight, 1.0) * 1200.0))
        val columns = mutableListOf<List<Box>>()
        for (layer in layers) {
            var current = mutableListOf<Box>()
            var height = 0.0
            for (b in layer) {
                if (current.isNotEmpty() && height + b.h > maxColumnHeight) {
                    columns += current
                    current = mutableListOf()
                    height = 0.0
                }
                current += b
                height += b.h + V_GAP
            }
            if (current.isNotEmpty()) columns += current
        }

        val tallest = columns.maxOfOrNull { c -> c.sumOf { it.h + V_GAP } - V_GAP } ?: 0.0
        var x = MARGIN
        for (column in columns) {
            val colWidth = column.maxOf { it.w }
            val colHeight = column.sumOf { it.h + V_GAP } - V_GAP
            var y = contentTop + (tallest - colHeight) / 2
            for (b in column) {
                b.x = x + (colWidth - b.w) / 2
                b.y = y
                y += b.h + V_GAP
            }
            x += colWidth + H_GAP
        }

        // Unconnected boxes: grid below the connected part.
        if (isolated.isNotEmpty()) {
            val top = if (columns.isEmpty()) contentTop else contentTop + tallest + 2 * V_GAP + 20
            val mainWidth = max(x - MARGIN - H_GAP, 0.0)
            val avgWidth = isolated.sumOf { it.w } / isolated.size + 40
            val perRow = max(1, max(ceil(sqrt(isolated.size.toDouble())).toInt(), (mainWidth / avgWidth).toInt()))
            var gy = top
            isolated.chunked(perRow).forEach { rowBoxes ->
                var gx = MARGIN
                for (b in rowBoxes) {
                    b.x = gx
                    b.y = gy
                    gx += b.w + 40
                }
                gy += rowBoxes.maxOf { it.h } + V_GAP
            }
        }
    }

    private fun legendHeight(): Double = if (legendEntries().isEmpty()) 0.0 else 30.0

    private fun legendEntries(): List<Pair<String, String>> =
        usedCategories.mapNotNull { c -> doc.categoryBackgrounds[c]?.let { toCss(it) }?.let { (doc.categoryLabels[c] ?: c) to it } }

    // ------------------------------------------------------------------------------------- drawing

    private fun draw(maxDimension: Int, maxPixels: Double): String {
        val legendWidth = legendEntries().sumOf { 16 + textWidth(it.first) + 18 }
        val titleWidth = textWidth(title, true) * 1.4
        val width = ceil(maxOf((boxes.maxOfOrNull { it.right } ?: 300.0) + MARGIN + 60, MARGIN * 2 + legendWidth, MARGIN * 2 + titleWidth))
            .coerceAtLeast(400.0)
        val height = ceil((boxes.maxOfOrNull { it.y + it.h } ?: 100.0) + MARGIN).coerceAtLeast(120.0)
        val scale = minOf(1.0, maxDimension / max(width, height), sqrt(maxPixels / (width * height)))
        val sb = StringBuilder(64 * 1024)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" width=\"${fmt(width * scale)}\" height=\"${fmt(height * scale)}\" viewBox=\"0 0 ${fmt(width)} ${fmt(height)}\" font-family=\"Segoe UI, Arial, Helvetica, sans-serif\" font-size=\"$FONT_SIZE\">\n")
        sb.append("<rect x=\"0\" y=\"0\" width=\"${fmt(width)}\" height=\"${fmt(height)}\" fill=\"#FFFFFF\"/>\n")
        sb.append("<text x=\"${fmt(MARGIN)}\" y=\"${fmt(MARGIN + 14)}\" font-size=\"18\" font-weight=\"bold\" fill=\"#222222\">${esc(title)}</text>\n")

        // Legend
        var lx = MARGIN
        val ly = MARGIN + 30
        for ((label, colour) in legendEntries()) {
            sb.append("<rect x=\"${fmt(lx)}\" y=\"${fmt(ly)}\" width=\"12\" height=\"12\" fill=\"$colour\" stroke=\"#555555\" stroke-width=\"0.5\"/>")
            sb.append("<text x=\"${fmt(lx + 16)}\" y=\"${fmt(ly + 10)}\" fill=\"#333333\">${esc(label)}</text>\n")
            lx += 16 + textWidth(label) + 18
        }

        // Edges below boxes.
        sb.append("<g fill=\"none\" stroke=\"#6A7A8C\" stroke-width=\"1.3\">\n")
        val labels = StringBuilder()
        for (e in edges) drawEdge(sb, labels, e)
        sb.append("</g>\n")

        for (b in boxes) drawBox(sb, b)

        sb.append("<g>\n").append(labels).append("</g>\n")
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun anchorY(b: Box, row: Int?): Double =
        if (row != null && row >= 0) b.y + headerHeight(b) + 3 + row * ROW_HEIGHT + ROW_HEIGHT / 2
        else b.y + headerHeight(b) / 2

    private fun drawEdge(sb: StringBuilder, labels: StringBuilder, e: Edge) {
        val a = e.from
        val b = e.to
        val y1 = anchorY(a, e.fromRow)
        val y2 = anchorY(b, e.toRow)
        val path: String
        val endX: Double
        val endDir: Int // +1: arrow points right (enters left side), -1: points left
        val mid: Pair<Double, Double>
        if (a === b) {
            val x = a.right
            path = "M ${fmt(x)} ${fmt(y1)} C ${fmt(x + 40)} ${fmt(y1 - 10)}, ${fmt(x + 40)} ${fmt(y2 + 10)}, ${fmt(x + 6)} ${fmt(y2)}"
            endX = x
            endDir = -1
            mid = (x + 32) to (y1 + y2) / 2
        } else {
            val (x1, d1, x2, d2) = when {
                b.x >= a.right -> Quad(a.right, 1, b.x, -1)
                b.right <= a.x -> Quad(a.x, -1, b.right, 1)
                else -> Quad(a.right, 1, b.right, 1)
            }
            val dx = max(40.0, abs(x2 - x1) / 2)
            val tip = x2 + d2 * 6
            path = "M ${fmt(x1)} ${fmt(y1)} C ${fmt(x1 + d1 * dx)} ${fmt(y1)}, ${fmt(tip + d2 * dx)} ${fmt(y2)}, ${fmt(tip)} ${fmt(y2)}"
            endX = x2
            endDir = -d2
            // Bezier midpoint (t = 0.5)
            val mx = 0.125 * x1 + 0.375 * (x1 + d1 * dx) + 0.375 * (tip + d2 * dx) + 0.125 * tip
            mid = mx to (y1 + y2) / 2
        }
        sb.append("<path d=\"$path\"/>")
        // Arrow head (explicit polygon: markers are not supported by every SVG renderer).
        val bx = endX - endDir * 7
        sb.append("<polygon points=\"${fmt(endX)},${fmt(y2)} ${fmt(bx)},${fmt(y2 - 4)} ${fmt(bx)},${fmt(y2 + 4)}\" fill=\"#6A7A8C\" stroke=\"none\"/>\n")
        e.label?.let { text ->
            val w = textWidth(text) * 0.9 + 6
            labels.append("<rect x=\"${fmt(mid.first - w / 2)}\" y=\"${fmt(mid.second - 8)}\" width=\"${fmt(w)}\" height=\"14\" rx=\"3\" fill=\"#FFFFFF\" fill-opacity=\"0.85\"/>")
            labels.append("<text x=\"${fmt(mid.first)}\" y=\"${fmt(mid.second + 3)}\" font-size=\"${FONT_SIZE - 1}\" text-anchor=\"middle\" fill=\"#44546A\">${esc(text)}</text>\n")
        }
    }

    private data class Quad(val x1: Double, val d1: Int, val x2: Double, val d2: Int)

    private fun drawBox(sb: StringBuilder, b: Box) {
        val header = headerHeight(b)
        val headerFill = b.color ?: "#C0C0C0"
        val headerText = contrastText(headerFill)
        sb.append("<g>")
        sb.append("<title>${esc(b.tooltip)}</title>")
        sb.append("<rect x=\"${fmt(b.x)}\" y=\"${fmt(b.y)}\" width=\"${fmt(b.w)}\" height=\"${fmt(b.h)}\" rx=\"4\" fill=\"#FAFAFA\" stroke=\"#7A7A7A\" stroke-width=\"1\"/>")
        sb.append("<rect x=\"${fmt(b.x)}\" y=\"${fmt(b.y)}\" width=\"${fmt(b.w)}\" height=\"${fmt(header)}\" rx=\"4\" fill=\"$headerFill\" stroke=\"#7A7A7A\" stroke-width=\"1\"/>")
        sb.append("<text x=\"${fmt(b.x + PAD)}\" y=\"${fmt(b.y + 17)}\" font-weight=\"bold\" font-size=\"${FONT_SIZE + 1}\" fill=\"$headerText\">${esc(clip(b.title, b.w - 2 * PAD, true))}</text>")
        b.subtitle?.let {
            sb.append("<text x=\"${fmt(b.x + PAD)}\" y=\"${fmt(b.y + 31)}\" font-size=\"${FONT_SIZE - 1}\" fill=\"$headerText\">${esc(clip("«$it»", b.w - 2 * PAD))}</text>")
        }
        sb.append("</g>\n")
        var y = b.y + header + 3
        for (r in b.rows) {
            sb.append("<g><title>${esc(r.tooltip)}</title>")
            sb.append("<rect x=\"${fmt(b.x + PAD)}\" y=\"${fmt(y + (ROW_HEIGHT - SWATCH) / 2)}\" width=\"$SWATCH\" height=\"$SWATCH\" rx=\"2\" fill=\"${r.color ?: "#FFFFFF"}\" stroke=\"#555555\" stroke-width=\"0.5\"/>")
            sb.append("<text x=\"${fmt(b.x + PAD + SWATCH + 6)}\" y=\"${fmt(y + ROW_HEIGHT - 5)}\" fill=\"#1E1E1E\">${esc(clip(r.label, b.w - 2 * PAD - SWATCH - 6))}</text>")
            sb.append("</g>\n")
            y += ROW_HEIGHT
        }
        if (b.hiddenRows > 0) {
            sb.append("<text x=\"${fmt(b.x + PAD)}\" y=\"${fmt(y + ROW_HEIGHT - 5)}\" font-style=\"italic\" fill=\"#666666\">${esc(moreText(b))}</text>\n")
        }
    }

    private fun clip(text: String, width: Double, bold: Boolean = false): String {
        if (textWidth(text, bold) <= width) return text
        var t = text
        while (t.length > 1 && textWidth("$t…", bold) > width) t = t.dropLast(1)
        return "$t…"
    }

    companion object {
        private const val FONT_SIZE = 12
        private const val PAD = 8.0
        private const val SWATCH = 10.0
        private const val ROW_HEIGHT = 18.0
        private const val HEADER_HEIGHT = 24
        private const val MIN_BOX_WIDTH = 120.0
        private const val MAX_BOX_WIDTH = 420.0
        private const val MARGIN = 20.0
        private const val TOP_MARGIN = 56.0
        private const val H_GAP = 130.0
        private const val V_GAP = 28.0
        private const val MAX_ROWS_PER_BOX = 80
        private const val MAX_TOTAL_ROWS = 6000

        private fun fmt(d: Double): String {
            val r = Math.round(d * 10) / 10.0
            return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
        }

        fun esc(s: String): String {
            val sb = StringBuilder(s.length + 8)
            for (c in s) {
                when {
                    c == '&' -> sb.append("&amp;")
                    c == '<' -> sb.append("&lt;")
                    c == '>' -> sb.append("&gt;")
                    c == '"' -> sb.append("&quot;")
                    c == '\n' || c == '\t' -> sb.append(c)
                    c == '\r' -> Unit
                    c.code < 32 -> Unit
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        /** DGML colours are #AARRGGBB / #RRGGBB (or a few WPF names); returns a CSS colour. */
        fun toCss(value: String): String? {
            val v = value.trim()
            if (v.startsWith("#")) {
                val hex = v.substring(1)
                return when (hex.length) {
                    8 -> "#" + hex.substring(2).uppercase()
                    6 -> "#" + hex.uppercase()
                    3 -> "#" + hex.uppercase()
                    else -> null
                }
            }
            return if (v.matches(Regex("[A-Za-z]+"))) v.lowercase() else null
        }

        private fun contrastText(cssColor: String): String {
            if (!cssColor.startsWith("#") || cssColor.length != 7) return "#000000"
            val r = cssColor.substring(1, 3).toInt(16)
            val g = cssColor.substring(3, 5).toInt(16)
            val b = cssColor.substring(5, 7).toInt(16)
            val luminance = 0.299 * r + 0.587 * g + 0.114 * b
            return if (luminance < 140) "#FFFFFF" else "#000000"
        }
    }
}
