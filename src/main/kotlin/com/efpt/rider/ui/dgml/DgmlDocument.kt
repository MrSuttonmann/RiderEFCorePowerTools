package com.efpt.rider.ui.dgml

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Minimal in-memory model of a DGML (Directed Graph Markup Language) document. */
class DgmlDocument(
    val nodes: LinkedHashMap<String, DgmlNode>,
    val links: List<DgmlLink>,
    /** Category id -> background colour (from <Styles> HasCategory conditions and <Categories>). */
    val categoryBackgrounds: Map<String, String>,
    /** Category id -> display label (Style GroupLabel / Category Label). */
    val categoryLabels: Map<String, String>,
    val graphDirection: String?,
) {
    companion object {
        private val HAS_CATEGORY = Regex("HasCategory\\(\\s*'([^']*)'\\s*\\)")

        fun parse(file: File): DgmlDocument {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val doc = factory.newDocumentBuilder().parse(file)
            val rootEl = doc.documentElement

            val nodes = LinkedHashMap<String, DgmlNode>()
            val links = mutableListOf<DgmlLink>()
            val backgrounds = LinkedHashMap<String, String>()
            val labels = LinkedHashMap<String, String>()

            for (el in children(rootEl, "Nodes").flatMap { children(it, "Node") }) {
                val id = el.getAttribute("Id")
                val attrs = attributes(el)
                val categories = categories(el)
                val existing = nodes[id]
                if (existing == null) {
                    nodes[id] = DgmlNode(id, attrs, categories.toMutableList())
                } else {
                    // Duplicate ids are merged (as Visual Studio does): first non-empty value wins.
                    attrs.forEach { (k, v) -> if (existing.attributes[k].isNullOrEmpty()) existing.attributes[k] = v }
                    categories.forEach { if (it !in existing.categories) existing.categories += it }
                }
            }

            for (el in children(rootEl, "Links").flatMap { children(it, "Link") }) {
                links += DgmlLink(el.getAttribute("Source"), el.getAttribute("Target"), attributes(el), categories(el))
            }

            for (el in children(rootEl, "Categories").flatMap { children(it, "Category") }) {
                val id = el.getAttribute("Id")
                el.getAttribute("Background").takeIf { it.isNotEmpty() }?.let { backgrounds.putIfAbsent(id, it) }
                el.getAttribute("Label").takeIf { it.isNotEmpty() }?.let { labels.putIfAbsent(id, it) }
            }

            for (style in children(rootEl, "Styles").flatMap { children(it, "Style") }) {
                if (!style.getAttribute("TargetType").equals("Node", true)) continue
                val condition = children(style, "Condition").firstOrNull()?.getAttribute("Expression") ?: continue
                val category = HAS_CATEGORY.find(condition)?.groupValues?.get(1) ?: continue
                val background = children(style, "Setter")
                    .firstOrNull { it.getAttribute("Property").equals("Background", true) }
                    ?.getAttribute("Value")
                if (!background.isNullOrEmpty()) backgrounds[category] = background
                style.getAttribute("GroupLabel").takeIf { it.isNotEmpty() }?.let { labels.putIfAbsent(category, it) }
            }

            return DgmlDocument(nodes, links, backgrounds, labels, rootEl.getAttribute("GraphDirection").ifEmpty { null })
        }

        private fun children(parent: Element, localName: String): List<Element> {
            val list = mutableListOf<Element>()
            var n = parent.firstChild
            while (n != null) {
                if (n is Element && (n.localName ?: n.tagName).substringAfter(':') == localName) list += n
                n = n.nextSibling
            }
            return list
        }

        private fun attributes(el: Element): MutableMap<String, String> {
            val map = LinkedHashMap<String, String>()
            val attrs = el.attributes
            for (i in 0 until attrs.length) {
                val a = attrs.item(i)
                if (a.nodeName.startsWith("xmlns")) continue
                map[a.nodeName] = a.nodeValue
            }
            return map
        }

        private fun categories(el: Element): List<String> = buildList {
            el.getAttribute("Category").takeIf { it.isNotEmpty() }?.let { add(it) }
            children(el, "Category").mapNotNull { it.getAttribute("Ref").takeIf { r -> r.isNotEmpty() } }.forEach { add(it) }
        }
    }
}

class DgmlNode(val id: String, val attributes: MutableMap<String, String>, val categories: MutableList<String>) {
    val label: String get() = attributes["Label"]?.takeIf { it.isNotEmpty() } ?: id
    val group: String? get() = attributes["Group"]?.takeIf { it.isNotEmpty() }
    val category: String? get() = categories.firstOrNull()
}

class DgmlLink(val source: String, val target: String, val attributes: Map<String, String>, val categories: List<String>) {
    val label: String? get() = attributes["Label"]?.takeIf { it.isNotEmpty() }
    val isContainment: Boolean get() = categories.any { it.equals("Contains", true) }
}
