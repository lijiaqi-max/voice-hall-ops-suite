package com.local.voicehall.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TotpTest {
    @Test
    fun `verifies RFC 6238 SHA1 vector as six digits`() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

        assertTrue(Totp.verify(secret, "287082", epochMillis = 59_000, window = 0))
        assertFalse(Totp.verify(secret, "287083", epochMillis = 59_000, window = 0))
        assertFalse(Totp.verify(secret, null, epochMillis = 59_000, window = 0))
    }
}
