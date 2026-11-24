package com.efpt.rider.json

import com.efpt.rider.model.ReverseEngineerOptions
import com.efpt.rider.model.Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Rider plugin must read and write efpt.config.json / efpt.renaming.json byte-for-byte like the
 * Visual Studio extension (DataContractJsonSerializer), so teams can mix both tools without churn.
 */
class DcJsonRoundTripTest {
    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/samples/$name")!!.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")

    private fun normalize(s: String) = s.replace("\r\n", "\n").trimEnd()

    @Test
    fun `efpt config written by Visual Studio round-trips unchanged`() {
        for (name in listOf("efpt.config.json", "efpt.consoleapp.config.json")) {
            val original = resource(name)
            val options = ReverseEngineerOptions.fromJson(DcJson.parse(original).asJsonObject)
            val keys = DcJson.parse(original).asJsonObject.keySet()
            // Only compare members present in the original (older files lack newer options).
            val rewritten = options.toJson().also { o -> o.keySet().toList().filterNot { it in keys }.forEach(o::remove) }
            assertEquals(name, normalize(original), normalize(DcJson.write(rewritten)))
        }
    }

    @Test
    fun `renaming file round-trips unchanged`() {
        val original = resource("efpt.renaming.json")
        assertEquals(normalize(original), normalize(Schema.listToJson(Schema.listFromJson(original))))
    }

    @Test
    fun `missing members get CLR defaults like DataContractJsonSerializer`() {
        val options = ReverseEngineerOptions.fromJson(DcJson.parse("{}").asJsonObject)
        assertFalse(options.useInflector)
        assertFalse(options.useTypedTvpParameters)
        assertTrue(ReverseEngineerOptions().useInflector)
    }

    @Test
    fun `EF Core 8 and 9 configs are migrated to EF Core 10 like upstream`() {
        for (legacy in listOf(4, 5)) {
            val options = ReverseEngineerOptions.fromJson(DcJson.parse("""{"CodeGenerationMode": $legacy}""").asJsonObject)
            assertEquals(com.efpt.rider.model.CodeGenerationMode.EFCore10, options.codeGenerationMode)
            assertEquals(6, options.toJson().get("CodeGenerationMode").asInt)
        }
        assertEquals(com.efpt.rider.model.CodeGenerationMode.EFCore10, ReverseEngineerOptions().codeGenerationMode)
    }

    @Test
    fun `strings are escaped like DataContractJsonSerializer`() {
        val o = com.google.gson.JsonObject().apply { put("B", """a/b\c"d"""); put("A", null) }
        assertEquals("""{"A":null,"B":"a\/b\\c\"d"}""", DcJson.write(o, indent = false))
    }
}
