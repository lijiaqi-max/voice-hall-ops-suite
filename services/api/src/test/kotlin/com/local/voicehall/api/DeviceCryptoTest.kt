package com.local.voicehall.api

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceCryptoTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val crypto = DeviceCrypto(key)

    @Test
    fun encryptsSecretsAndVerifiesHmac() {
        val secret = crypto.newSecret()
        assertEquals(secret, crypto.decrypt(crypto.encrypt(secret)))
        val signature = crypto.signature(secret, "123", "{}")
        assertTrue(crypto.verify(secret, "123", "{}", signature))
        assertFalse(crypto.verify(secret, "124", "{}", signature))
    }
}

