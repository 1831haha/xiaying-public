package com.movieshell.core.util

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class Base64EnvelopeTest {

    private val smallJson =
        """{"sites":[{"key":"a","name":"A","type":3,"api":"csp_X","searchable":1}],"parses":[],"spider":"https://x/y.jar"}"""

    private fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test fun `wmz envelope unwraps`() {
        val raw = "\n" + b64("base64") + "**" + b64(smallJson)
        assertEquals(smallJson, Base64Envelope.unwrap(raw))
    }

    @Test fun `plain base64 marker unwraps`() {
        val raw = "base64**" + b64(smallJson)
        assertEquals(smallJson, Base64Envelope.unwrap(raw))
    }

    @Test fun `plain json untouched`() {
        assertEquals(smallJson, Base64Envelope.unwrap(smallJson))
    }

    @Test fun `double star without marker untouched`() {
        assertEquals("abc**def", Base64Envelope.unwrap("abc**def"))
    }

    @Test fun `bad body untouched`() {
        val raw = b64("base64") + "**not-base64!!!"
        assertEquals(raw, Base64Envelope.unwrap(raw))
    }

    @Test fun `decoded non-json untouched`() {
        val raw = b64("base64") + "**" + b64("hello world")
        assertEquals(raw, Base64Envelope.unwrap(raw))
    }

    @Test fun `empty and blank untouched`() {
        assertEquals("", Base64Envelope.unwrap(""))
        assertEquals("   ", Base64Envelope.unwrap("   "))
    }

    @Test fun `json value with double star untouched`() {
        val json = """{"sites":[{"key":"a","name":"A**B","type":3,"api":"csp_X","searchable":1}]}"""
        assertEquals(json, Base64Envelope.unwrap(json))
    }

    @Test fun `txt warehouse untouched`() {
        val txt = "多仓\nhttps://a.com/x.json\nhttps://b.com/y.json"
        assertEquals(txt, Base64Envelope.unwrap(txt))
    }

    @Test fun `comment with double star untouched`() {
        val text = "// see ** note\n{\"sites\":[]}"
        assertEquals(text, Base64Envelope.unwrap(text))
    }

    @Test fun `bom envelope unwraps`() {
        val raw = "\uFEFF" + b64("base64") + "**" + b64(smallJson)
        assertEquals(smallJson, Base64Envelope.unwrap(raw))
    }

    @Test fun `bare base64 json untouched`() {
        val raw = b64(smallJson)
        assertEquals(raw, Base64Envelope.unwrap(raw))
    }
}
