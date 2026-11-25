package com.movieshell.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSandboxTest {

    @Test
    fun derive_collectsRuleHostAndLiteralHosts() {
        val rule = """
            var rule = {
                host: 'https://example.com',
                homeUrl: 'https://example.com/home',
                searchUrl: 'https://api.other.com/search?wd=**',
            };
        """.trimIndent()
        val sandbox = SourceSandbox.derive(rule, "https://api.other.com/x")
        assertTrue(sandbox.allowedHosts.contains("example.com"))
        assertTrue(sandbox.allowedHosts.contains("api.other.com"))
        assertTrue(sandbox.enforced)
    }

    @Test
    fun allows_exactAndSubdomainOnly() {
        val sandbox = SourceSandbox(setOf("example.com"))
        assertTrue(sandbox.allows("https://example.com/a"))
        assertTrue(sandbox.allows("https://a.example.com/a"))
        assertTrue(sandbox.allows("example.com"))
        assertFalse(sandbox.allows("https://badexample.com/"))
        assertFalse(sandbox.allows("https://example.com.evil.com/"))
        assertFalse(sandbox.allows("https://other.com/"))
        assertFalse(sandbox.allows(""))
        assertFalse(sandbox.allows("not a host !!"))
    }

    @Test
    fun allows_stripsPortAndCase() {
        val sandbox = SourceSandbox(setOf("example.com"))
        assertTrue(sandbox.allows("https://EXAMPLE.com:8443/x"))
    }

    @Test
    fun unenforced_allowsEverything() {
        val sandbox = SourceSandbox(emptySet(), enforced = false)
        assertTrue(sandbox.allows("https://anything.example/"))
    }

    @Test
    fun hostOf_rejectsIpv6LiteralAndBlank() {
        assertEquals(null, SourceSandbox.hostOf("https://[::1]:8080/"))
        assertEquals(null, SourceSandbox.hostOf("   "))
        assertEquals("example.com", SourceSandbox.hostOf("https://example.com:8080/a?b=c"))
    }

    @Test
    fun derive_capsHostCount() {
        val hosts = (1..100).joinToString("\n") { "fetch('https://h$it.example.com/x');" }
        val sandbox = SourceSandbox.derive(hosts, "")
        assertEquals(SourceSandbox.MAX_HOSTS, sandbox.allowedHosts.size)
    }

    @Test
    fun encodeDecode_roundTrip() {
        val hosts = setOf("b.com", "a.com", "c.example.com")
        assertEquals(hosts, SourceSandbox.decodeHosts(SourceSandbox.encodeHosts(hosts)))
        assertEquals("a.com,b.com,c.example.com", SourceSandbox.encodeHosts(hosts))
        assertTrue(SourceSandbox.decodeHosts("").isEmpty())
    }
}
