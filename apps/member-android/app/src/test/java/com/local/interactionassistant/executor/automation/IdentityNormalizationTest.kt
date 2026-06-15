package com.local.interactionassistant.executor.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityNormalizationTest {
    @Test
    fun ignoresWhitespaceAndCaseOnly() {
        assertEquals("测试user", " 测试 User ".normalizedIdentity())
    }

    @Test
    fun doesNotErasePunctuation() {
        assertEquals("user-01", "User-01".normalizedIdentity())
    }
}
