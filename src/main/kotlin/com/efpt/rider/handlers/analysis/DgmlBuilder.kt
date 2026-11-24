package com.efpt.rider.handlers.analysis

/**
 * Port of Dgml.DgmlBuilder + Dgml.DebugViewParser (src/Nupkg/ErikEJ.EntityFrameworkCore.DgmlBuilder),
 * which the VS extension links in to turn a DbContext Model.DebugView into DGML using the embedded
 * template.dgml. The output is kept textually identical to the VS extension (including its quirks,
 * e.g. the duplicated node of the last entity type).
 */
object DgmlBuilder {
    /** Environment.NewLine. */
    private val NL: String = System.lineSeparator()

    fun build(debugView: String, contextName: String, template: String = TEMPLATE): String {
        val result = DebugViewParser.parse(debugView.split(Regex("\r\n|\n")), contextName)
        val nodes = result.nodes.joinToString(NL)
        val links = result.links.joinToString(NL)
        return template.replace("{Links}", links, ignoreCase = true).replace("{Nodes}", nodes, ignoreCase = true)
    }

    /** template.dgml (EFCorePowerTools.DgmlBuilder.template.dgml embedded resource), BOM stripped as by StreamReader. */
    val TEMPLATE: String = """<?xml version="1.0" encoding="utf-8"?>
<DirectedGraph GraphDirection="LeftToRight" xmlns="http://schemas.microsoft.com/vs/2009/dgml">
  <Nodes>
{Nodes}
  </Nodes>
  <Links>
{Links}
  </Links>
  <Styles>
    <Style TargetType="Node" GroupLabel="EntityType" ValueLabel="True">
      <Condition Expression="HasCategory('EntityType')" />
      <Setter Property="Background" Value="#FFC0C0C0" />
    </Style>
    <Style TargetType="Node" GroupLabel="Property Primary" ValueLabel="True">
      <Condition Expression="HasCategory('Property Primary')" />
      <Setter Property="Background" Value="#FF008000" />
    </Style>
    <Style TargetType="Node" GroupLabel="Property Optional" ValueLabel="True">
      <Condition Expression="HasCategory('Property Optional')" />
      <Setter Property="Background" Value="#FF808040" />
    </Style>
    <Style TargetType="Node" GroupLabel="Property Foreign" ValueLabel="True">
      <Condition Expression="HasCategory('Property Foreign')" />
      <Setter Property="Background" Value="#FF8080FF" />
    </Style>
    <Style TargetType="Node" GroupLabel="Property Required" ValueLabel="True">
      <Condition Expression="HasCategory('Property Required')" />
      <Setter Property="Background" Value="#FFC0A000" />
    </Style>
    <Style TargetType="Node" GroupLabel="Navigation Property" ValueLabel="True">
      <Condition Expression="HasCategory('Navigation Property')" />
      <Setter Property="Background" Value="#FF990000" />
    </Style>
    <Style TargetType="Node" GroupLabel="Navigation Collection" ValueLabel="True">
      <Condition Expression="HasCategory('Navigation Collection')" />
      <Setter Property="Background" Value="#FFFF3232" />
    </Style>
    <Style TargetType="Node" GroupLabel="Model" ValueLabel="True">
      <Condition Expression="HasCategory('Model')" />
      <Setter Property="Background" Value="#FFFFFFFF" />
    </Style>
  </Styles>
  <Properties>
    <Property Id="Name" Group="Model Properties" DataType="System.String" />
    <Property Id="Field"  Description="Backing field" Group="Model Properties" DataType="System.String" />
    <Property Id="Type"  Description="CLR data type" Group="Model Properties" DataType="System.String" />
    <Property Id="To" Description="Source property" Group="Model Properties" DataType="System.String" />
    <Property Id="From" Description="Target property" Group="Model Properties" DataType="System.String" />
    <Property Id="Dependent" Description="Dependent entity" Group="Model Properties" DataType="System.String" />
    <Property Id="Inverse" Description="Inverse entity" Group="Model Properties" DataType="System.String" />
    <Property Id="Principal" Description="Principal entity" Group="Model Properties" DataType="System.String" />
    <Property Id="Annotations" Description="Annotations" Group="Model Properties" DataType="System.String" />
    <Property Id="BaseClass"  Description="Base class" Group="Model Properties" DataType="System.String" />
    <Property Id="ChangeTrackingStrategy" Description="Change tracking strategy" Group="Model Properties" DataType="System.String" />
    <Property Id="IsUnique" Group="Model Properties" DataType="System.Boolean" />
    <Property Id="IsAbstract" Group="Model Properties" DataType="System.Boolean" />
    <Property Id="ProductVersion" Label="Product Version" Description="EF Core product version" Group="Model Properties" DataType="System.String" />
    <Property Id="IsIndexed" Group="Property Flags" DataType="System.Boolean" />
    <Property Id="IsRequired" Group="Property Flags" DataType="System.Boolean" />
    <Property Id="IsPrimaryKey" Group="Property Flags" DataType="System.Boolean" />
    <Property Id="IsForeignKey" Group="Property Flags" DataType="System.Boolean" />
    <Property Id="IsShadow" Group="Property Flags" DataType="System.Boolean" />
    <Property Id="IsAlternateKey" Group="Property Flags" DataType="System.Boolean" />
    <Property Id="IsConcurrencyToken" Group="Property Flags" DataType="System.Boolean" />
    <Property Id="IsIndexed" Group="Property Flags" DataType="System.Boolean" />
    <Property Id="IsUnicode" Group="Property Flags" DataType="System.Boolean" />
    <Property Id="ValueGenerated" Group="Property Flags" DataType="System.String" />
    <Property Id="BeforeSaveBehavior" Group="Property Flags" DataType="System.String" />
    <Property Id="AfterSaveBehavior" Group="Property Flags" DataType="System.String" />
    <Property Id="PropertyAccessMode" Group="Property Flags" DataType="System.String" />
  </Properties>
</DirectedGraph>""".replace("\n", NL)
}

/** Port of Dgml.DebugViewParser. */
internal object DebugViewParser {
    private val NL: String = System.lineSeparator()

    class Result {
        val nodes = mutableListOf<String>()
        val links = mutableListOf<String>()
    }

    /** System.Security.SecurityElement.Escape. */
    fun escape(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '&' -> sb.append("&amp;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** C# bool.ToString(). */
    private fun b(value: Boolean) = if (value) "True" else "False"

    fun parse(debugViewLines: List<String>, dbContextName: String): Result {
        val lines = debugViewLines
        val result = Result()

        var modelAnnotated = false
        var productVersion = ""
        val modelAnnotations = StringBuilder()
        var modelPropertyAccessMode = "PropertyAccessMode.Default"
        var changeTrackingStrategy = "ChangeTrackingStrategy.Snapshot"

        for (line in lines) {
            if (line.startsWith("Model:")) {
                val props = line.trim().split(' ')
                if (props.isNotEmpty()) {
                    changeTrackingStrategy = props.firstOrNull { it.startsWith("ChangeTrackingStrategy.") }
                        ?.takeIf { it.isNotEmpty() } ?: "ChangeTrackingStrategy.Snapshot"
                    modelPropertyAccessMode = getPropertyAccessMode(props)
                }
            }

            if (line.startsWith("Annotations:")) modelAnnotated = true

            if (modelAnnotated) {
                if (line.trimStart().startsWith("ProductVersion: ")) {
                    productVersion = line.trim().split(' ')[1]
                }
                if (!line.trimStart().startsWith("ProductVersion: ") && !line.trimStart().startsWith("Annotations:")) {
                    modelAnnotations.append(line.trim()).append(NL)
                }
            }
        }

        result.nodes += "<Node Id=\"IModel\" Label=\"$dbContextName\" ChangeTrackingStrategy=\"$changeTrackingStrategy\" PropertyAccessMode=\"$modelPropertyAccessMode\" ProductVersion=\"$productVersion\" Annotations=\"${modelAnnotations.toString().trim()}\" Category=\"Model\" Group=\"Expanded\" />"

        var entityName = ""
        val properties = mutableListOf<String>()
        val propertyLinks = mutableListOf<String>()
        val inProperties = BooleanArray(1)
        var inOtherProperties = false
        var i = -1
        for (line in lines) {
            i++
            if (line.trimStart().startsWith("EntityType:")) {
                entityName = escape(line.trim().split(' ').getOrElse(1) { "" })
                buildEntity(lines, entityName, i, result, properties, propertyLinks, line, inProperties)
            }

            if (line.trimStart().startsWith("Properties:")) {
                inProperties[0] = true
                inOtherProperties = false
            }

            if (entityName.isNotEmpty() && inProperties[0]) {
                if (line.startsWith("    Keys:") || line.startsWith("    Navigations:") ||
                    line.startsWith("    Annotations:") || line.startsWith("    Foreign keys:")
                ) {
                    inOtherProperties = true
                    continue
                }

                if (line.startsWith("      ") && !inOtherProperties) {
                    val annotations = getAnnotations(i, lines)
                    val navigations = getNavigations(i, lines)
                    val foreignKeysFragment = getForeignKeys(i, lines)

                    if (line.startsWith("        Annotations:") || line.startsWith("          ")) continue

                    val annotation = annotations.joinToString(NL)

                    var foundLine = line.replace("(no field, ", "(nofield,")
                    foundLine = foundLine.replace(", ", ",")
                    val props = foundLine.trim().split(' ').toMutableList()

                    val name = props[0]
                    val typeToken = props.getOrElse(1) { "" }
                    val field = getTypeValue(typeToken, true)
                    val type = getTypeValue(typeToken, false)

                    repeat(minOf(2, props.size)) { props.removeAt(0) }

                    val isRequired = props.contains("Required")
                    val isIndexed = props.contains("Index")
                    val isPrimaryKey = props.contains("PK")
                    val isForeignKey = props.contains("FK")
                    val isShadow = props.contains("Shadow")
                    val isAlternateKey = props.contains("AlternateKey")
                    val isConcurrency = props.contains("Concurrency")
                    val isUnicode = !props.contains("Ansi")

                    var beforeSaveBehavior = "PropertySaveBehavior.Save"
                    if (props.contains("BeforeSave:PropertySaveBehavior.Ignore")) beforeSaveBehavior = "PropertySaveBehavior.Ignore"
                    if (props.contains("BeforeSave:PropertySaveBehavior.Throw")) beforeSaveBehavior = "PropertySaveBehavior.Throw"

                    var afterSaveBehavior = "PropertySaveBehavior.Save"
                    if (props.contains("AfterSave:PropertySaveBehavior.Ignore")) afterSaveBehavior = "PropertySaveBehavior.Ignore"
                    if (props.contains("AfterSave:PropertySaveBehavior.Throw")) afterSaveBehavior = "PropertySaveBehavior.Throw"

                    val propertyAccessMode = getPropertyAccessMode(props)

                    var maxLength = props.firstOrNull { it.startsWith("MaxLength") }
                    maxLength = if (maxLength.isNullOrEmpty()) "None" else maxLength.replace("MaxLength", "")

                    val valueGenerated = props.firstOrNull { it.startsWith("ValueGenerated.") } ?: "None"
                    var category = "Property Required"
                    if (!isRequired) category = "Property Optional"
                    if (isForeignKey) category = "Property Foreign"
                    if (isPrimaryKey) category = "Property Primary"

                    properties += "<Node Id = \"$entityName.$name\" Label=\"$name ($type)\" Name=\"$name\" Category=\"$category\" Type=\"$type\" MaxLength=\"$maxLength\" Field=\"$field\" PropertyAccessMode=\"$propertyAccessMode\" BeforeSaveBehavior=\"$beforeSaveBehavior\" AfterSaveBehavior=\"$afterSaveBehavior\" Annotations=\"$annotation\" IsPrimaryKey=\"${b(isPrimaryKey)}\" IsForeignKey=\"${b(isForeignKey)}\" IsRequired=\"${b(isRequired)}\" IsIndexed=\"${b(isIndexed)}\" IsShadow=\"${b(isShadow)}\" IsAlternateKey=\"${b(isAlternateKey)}\" IsConcurrencyToken=\"${b(isConcurrency)}\" IsUnicode=\"${b(isUnicode)}\" ValueGenerated=\"$valueGenerated\" />"

                    val navigationResult = parseNavigations(navigations, entityName)
                    properties += navigationResult.first
                    propertyLinks += navigationResult.second
                    propertyLinks += "<Link Source = \"$entityName\" Target=\"$entityName.$name\" Category=\"Contains\" />"
                    propertyLinks += parseForeignKeys(foreignKeysFragment)
                }
            }
        }

        buildEntity(lines, entityName, i, result, properties, propertyLinks, null, inProperties)
        return result
    }

    private fun getPropertyAccessMode(props: List<String>): String {
        var mode = "PropertyAccessMode.Default"
        if (props.contains("PropertyAccessMode.Field")) mode = "PropertyAccessMode.Field"
        if (props.contains("PropertyAccessMode.FieldDuringConstruction")) mode = "PropertyAccessMode.FieldDuringConstruction"
        if (props.contains("PropertyAccessMode.Property")) mode = "PropertyAccessMode.Property"
        return mode
    }

    private fun buildEntity(
        lines: List<String>, entityName: String, i: Int, result: Result,
        properties: MutableList<String>, propertyLinks: MutableList<String>, line: String?, inProperties: BooleanArray,
    ) {
        if (entityName.isNotEmpty()) {
            var isAbstract = false
            var baseClass = ""
            var changeTrackingStrategy: String? = "ChangeTrackingStrategy.Snapshot"

            if (!line.isNullOrEmpty()) {
                val parts = line.trim().split(' ')
                isAbstract = parts.contains("Abstract")
                if (parts.contains("Base:")) baseClass = parts.getOrElse(parts.indexOf("Base:") + 1) { "" }
                changeTrackingStrategy = parts.firstOrNull { it.startsWith("ChangeTrackingStrategy.") }
            }
            if (changeTrackingStrategy.isNullOrEmpty()) changeTrackingStrategy = "ChangeTrackingStrategy.Snapshot"

            val annotation = getEntityAnnotations(i, lines).joinToString(NL)

            result.nodes += "<Node Id = \"$entityName\" Label=\"$entityName\" Name=\"$entityName\" BaseClass=\"$baseClass\" IsAbstract=\"${b(isAbstract)}\" ChangeTrackingStrategy=\"$changeTrackingStrategy\"  Annotations=\"$annotation\" Category=\"EntityType\" Group=\"Expanded\" />"
            result.links += "<Link Source = \"IModel\" Target=\"$entityName\" Category=\"Contains\" />"
            result.nodes += properties.distinct()
            result.links += propertyLinks.distinct()
            properties.clear()
            propertyLinks.clear()
        }
        inProperties[0] = false
    }

    private fun parseNavigations(navigations: List<String>, entityName: String): Pair<List<String>, List<String>> {
        val properties = mutableListOf<String>()
        val links = mutableListOf<String>()

        for (navigation in navigations) {
            val parts = navigation.trim().split(' ').toMutableList()
            if (parts.size < 3) continue

            val name = parts[0]
            val noField = !parts[2].endsWith(")")
            val fieldStripped = if (parts[1].startsWith("(") && parts[1].length >= 2) parts[1].substring(1, parts[1].length - 1) else parts[1]
            val typeStripped = if (parts[2].endsWith(")")) parts[2].dropLast(1) else parts[1]

            var field = escape(fieldStripped)
            var type = escape(typeStripped)
            if (noField) {
                type = field
                field = ""
            }

            parts.removeAt(0)
            parts.removeAt(0)

            var dependent = ""
            var inverse = ""
            var principal = ""
            if (parts.contains("Inverse:")) inverse = escape(parts.getOrElse(parts.indexOf("Inverse:") + 1) { "" })
            if (parts.contains("ToDependent")) dependent = escape(parts.getOrElse(parts.indexOf("ToDependent") + 1) { "" })
            if (parts.contains("ToPrincipal")) principal = escape(parts.getOrElse(parts.indexOf("ToPrincipal") + 1) { "" })

            val category = if (parts.contains("Collection")) "Navigation Collection" else "Navigation Property"
            val displayName = if (parts.contains("Collection")) "$name (*)" else "$name (1)"
            val propertyAccessMode = getPropertyAccessMode(parts)

            properties += "<Node Id = \"$entityName.$name\" Label=\"$displayName\" Name=\"$name\" Category=\"$category\" Type=\"$type\"  Field=\"$field\" Dependent=\"$dependent\" Principal=\"$principal\" Inverse=\"$inverse\" PropertyAccessMode=\"$propertyAccessMode\" />"
            links += "<Link Source = \"$entityName\" Target=\"$entityName.$name\" Category=\"Contains\" />"
        }
        return properties to links
    }

    private fun parseForeignKeys(fragments: List<String>): List<String> {
        val links = mutableListOf<String>()
        var i = 0
        if (fragments.size > 1) {
            for (fragment in fragments) {
                i++
                var trim = fragment.trim()
                if (trim == "Foreign keys:" || trim == "Annotations:" || trim.startsWith("Relational:")) continue

                val annotation = getFkAnnotations(i, fragments)

                // Multi key FKs!
                trim = trim.replace("', '", ",")
                trim = trim.replace(" (Dictionary<string, object>)", "")

                val parts = trim.split(' ').toMutableList()
                for (x in parts.indices) {
                    if (parts[x].startsWith("{'")) parts[x] = parts[x].substring(2)
                    if (parts[x].endsWith("'}")) parts[x] = parts[x].substring(0, parts[x].lastIndexOf("'}"))
                }

                // Rider: the VS parser throws (and aborts the whole command) on a malformed line; skip it instead.
                if (parts.size < 5) continue

                val source = escape(parts[0])
                val target = escape(parts[3])
                val fromColumns = escape(parts[1])
                val toColumns = escape(parts[4])

                repeat(5) { parts.removeAt(0) }

                val isUnique = parts.contains("Unique")
                val label = if (isUnique) "1:1" else "1:*"

                links += "<Link Source=\"$source\" Target=\"$target\" From=\"$source.$fromColumns\" To=\"$target.$toColumns\" Name=\"${"$source -> $target"}\" Annotations=\"${annotation.joinToString(NL)}\" IsUnique=\"${b(isUnique)}\" Label=\"$label\" Category=\"Foreign Key\" />"
            }
        }
        return links
    }

    private fun getTypeValue(type: String, asField: Boolean): String {
        val index = if (asField) 0 else 1
        val result = type.replace("(", "").replace(")", "")
        return if (result.contains(',')) escape(result.split(',')[index])
        else if (asField) "" else escape(result)
    }

    // The loops below mirror `while (x++ < maxLength)` of the C# original: x runs from i + 1 to maxLength.

    private fun getForeignKeys(i: Int, lines: List<String>): List<String> {
        var x = i
        val result = mutableListOf<String>()
        val maxLength = lines.size - 1
        var inSection = false
        while (x < maxLength) {
            x++
            val line = lines[x]
            val trim = line.trim()
            if (!inSection) inSection = trim == "Foreign keys:"
            if (line.startsWith("    Annotations:") || line.startsWith("Annotations:") ||
                trim.startsWith("Indexes:") || trim.startsWith("EntityType:")
            ) break
            if (inSection) result += line
        }
        return result
    }

    private fun getNavigations(i: Int, lines: List<String>): List<String> {
        var x = i
        val result = mutableListOf<String>()
        val maxLength = lines.size - 1
        var inSection = false
        while (x < maxLength) {
            x++
            val line = lines[x]
            val trim = line.trim()
            if (!inSection) inSection = trim == "Navigations:"
            if (line.startsWith("    Annotations:") || line.startsWith("Annotations:") ||
                trim.startsWith("Keys:") || trim.startsWith("EntityType:")
            ) break
            if (inSection) result += line
        }
        if (result.size > 1) result.removeAt(0)
        return result
    }

    private fun getEntityAnnotations(i: Int, lines: List<String>): List<String> {
        var x = i
        val values = mutableListOf<String>()
        val maxLength = lines.size - 1
        var inTheMix = false
        while (x < maxLength) {
            x++
            val line = lines[x]
            val trim = line.trim()
            if (!inTheMix) inTheMix = line == "    Annotations: "
            if (line.startsWith("Annotations:") || trim.startsWith("EntityType:")) break
            if (inTheMix && !trim.startsWith("Annotations:")) values += escape(trim)
        }
        return values
    }

    private fun getAnnotations(i: Int, lines: List<String>): List<String> {
        var x = i
        val annotations = mutableListOf<String>()
        val maxLength = lines.size - 1
        if (x < maxLength) {
            x++
            if (lines[x] == "        Annotations: ") {
                while (x < maxLength) {
                    x++
                    if (!lines[x].startsWith("        ")) break
                    annotations += escape(lines[x].trim())
                }
            }
        }
        return annotations
    }

    private fun getFkAnnotations(i: Int, lines: List<String>): List<String> {
        var x = i
        val annotations = mutableListOf<String>()
        val maxLength = lines.size - 1
        while (x < maxLength) {
            x++
            val line = lines[x]
            if (line.startsWith("          ")) annotations += escape(line.trim())
            // Rider: C# Substring(7, 1) throws on short lines; treat them as the end of the section.
            if (line.length < 8 || line[7] != ' ') break
            if (line.startsWith("    Foreign Keys:")) break
        }
        return annotations
    }
}
