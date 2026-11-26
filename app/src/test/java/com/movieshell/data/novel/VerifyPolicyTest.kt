package com.movieshell.data.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.27 解缠⑨：验证通道判定口径（与改版前 VerifyGate 内联逻辑逐字等价）。 */
class VerifyPolicyTest {

    private val longPage = "<html>" + "正文".repeat(300) + "</html>"

    @Test fun markersUnchanged() {
        assertEquals(
            listOf("Just a moment", "cf-challenge", "Attention Required", "Checking if the site connection is secure"),
            VerifyPolicy.CHALLENGE_MARKERS,
        )
        assertEquals(30_000L, VerifyPolicy.BACKGROUND_TIMEOUT_MS)
        assertEquals(180_000L, VerifyPolicy.FOREGROUND_TIMEOUT_MS)
    }

    @Test fun challengeDetected() {
        assertTrue(VerifyPolicy.looksLikeChallenge("<title>Just a moment...</title>"))
        assertFalse(VerifyPolicy.looksLikeChallenge(longPage))
    }

    @Test fun backgroundPassRules() {
        assertTrue(VerifyPolicy.backgroundPassed("<div id=cf-challenge>", "a=1; cf_clearance=x"))
        assertTrue(VerifyPolicy.backgroundPassed(longPage, ""))
        assertFalse(VerifyPolicy.backgroundPassed("<html>短</html>", ""))
        assertFalse(VerifyPolicy.backgroundPassed(longPage + "Just a moment", ""))
        val exactly500 = "x".repeat(500)
        assertFalse(VerifyPolicy.backgroundPassed(exactly500, ""))   // 原口径 length > 500
        assertTrue(VerifyPolicy.backgroundPassed(exactly500 + "x", ""))
    }

    @Test fun mergeCookies() {
        assertEquals(
            "a=1; b=2; c=3",
            VerifyPolicy.mergeCookieHeaders(listOf("a=1; b=2", null, " b=2 ;c=3;", "")),
        )
        assertEquals("", VerifyPolicy.mergeCookieHeaders(listOf(null, null)))
    }
}
