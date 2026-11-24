package com.efpt.rider.json

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * JSON helpers that reproduce the output of .NET's DataContractJsonSerializer as used by the
 * Visual Studio extension (JsonReaderWriterFactory with a three-space indent):
 *  - members are written in ordinal (alphabetical) order,
 *  - '/' is escaped as "\/",
 *  - files are written as UTF-8 with a byte order mark.
 *
 * Reading is lenient: missing members fall back to the CLR default value (false / 0 / null),
 * exactly like DataContractJsonSerializer, which never runs constructors or initializers.
 */
object DcJson {
    private const val INDENT = "   "
    private val newLine: String = System.lineSeparator()

    fun parse(text: String): JsonElement = JsonParser.parseString(text.removePrefix("﻿"))

    fun readFile(path: Path): JsonElement = parse(String(Files.readAllBytes(path), StandardCharsets.UTF_8))

    /** Writes [text] as UTF-8 with BOM, like File.WriteAllText(path, text, Encoding.UTF8). */
    fun writeFile(path: Path, text: String) {
        path.parent?.let { Files.createDirectories(it) }
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(StandardCharsets.UTF_8)
        Files.write(path, bytes)
    }

    /** Serializes with DataContractJsonSerializer-compatible formatting. */
    fun write(element: JsonElement, indent: Boolean = true): String {
        val sb = StringBuilder()
        writeElement(sb, element, 0, indent)
        return sb.toString()
    }

    private fun writeElement(sb: StringBuilder, element: JsonElement, level: Int, indent: Boolean) {
        when {
            element.isJsonNull -> sb.append("null")
            element.isJsonPrimitive -> writePrimitive(sb, element.asJsonPrimitive)
            element.isJsonArray -> {
                val array = element.asJsonArray
                if (array.isEmpty) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                array.forEachIndexed { i, child ->
                    if (i > 0) sb.append(',')
                    newLine(sb, level + 1, indent)
                    writeElement(sb, child, level + 1, indent)
                }
                newLine(sb, level, indent)
                sb.append(']')
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                if (obj.size() == 0) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                obj.keySet().sorted().forEachIndexed { i, key ->
                    if (i > 0) sb.append(',')
                    newLine(sb, level + 1, indent)
                    writeString(sb, key)
                    sb.append(if (indent) ": " else ":")
                    writeElement(sb, obj.get(key), level + 1, indent)
                }
                newLine(sb, level, indent)
                sb.append('}')
            }
        }
    }

    private fun newLine(sb: StringBuilder, level: Int, indent: Boolean) {
        if (!indent) return
        sb.append(newLine)
        repeat(level) { sb.append(INDENT) }
    }

    private fun writePrimitive(sb: StringBuilder, primitive: JsonPrimitive) {
        when {
            primitive.isBoolean -> sb.append(if (primitive.asBoolean) "true" else "false")
            primitive.isNumber -> sb.append(primitive.asNumber.toString())
            else -> writeString(sb, primitive.asString)
        }
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '/' -> sb.append("\\/")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

// ---- Lenient readers (DataContractJsonSerializer semantics: absent or null -> CLR default) ----

fun JsonObject.str(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else it.toString() }

fun JsonObject.bool(key: String): Boolean = get(key)?.takeUnless { it.isJsonNull }?.let {
    if (it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) it.asBoolean else it.asString.equals("true", ignoreCase = true)
} ?: false

fun JsonObject.int(key: String): Int = get(key)?.takeUnless { it.isJsonNull }?.asInt ?: 0

fun JsonObject.long(key: String): Long = get(key)?.takeUnless { it.isJsonNull }?.asLong ?: 0L

fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.strings(key: String): List<String>? = arr(key)?.mapNotNull { e -> e.takeUnless { it.isJsonNull }?.asString }

fun <T> JsonObject.objects(key: String, map: (JsonObject) -> T): List<T>? =
    arr(key)?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(map) }

// ---- Writers ----

/** Adds a member, writing JSON null for null values (DataContract default EmitDefaultValue = true). */
fun JsonObject.put(key: String, value: Any?) {
    add(key, toElement(value))
}

/** Adds a member only when it is not the type's default value (EmitDefaultValue = false). */
fun JsonObject.putIfNotDefault(key: String, value: Any?) {
    when (value) {
        null -> return
        is Boolean -> if (!value) return
        is Int -> if (value == 0) return
        is Long -> if (value == 0L) return
    }
    add(key, toElement(value))
}

fun toElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull.INSTANCE
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray().also { a -> value.forEach { a.add(toElement(it)) } }
    is JsonWritable -> value.toJson()
    else -> JsonPrimitive(value.toString())
}

interface JsonWritable {
    fun toJson(): JsonObject
}
