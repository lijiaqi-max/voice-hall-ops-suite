package com.local.voicehall.api

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DeviceCrypto(masterKeyBase64: String) {
    private val masterKey = runCatching {
        Base64.getDecoder().decode(masterKeyBase64)
    }.getOrElse {
        MessageDigest.getInstance("SHA-256")
            .digest(masterKeyBase64.toByteArray(StandardCharsets.UTF_8))
    }.also { require(it.size == 32) { "DEVICE_MASTER_KEY must resolve to 32 bytes" } }

    fun newSecret(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun encrypt(secret: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(secret.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    fun decrypt(ciphertext: String): String {
        val bytes = Base64.getDecoder().decode(ciphertext)
        val iv = bytes.copyOfRange(0, 12)
        val payload = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(payload).toString(StandardCharsets.UTF_8)
    }

    fun signature(secret: String, timestamp: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$timestamp\n$body".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun verify(secret: String, timestamp: String, body: String, provided: String): Boolean =
        MessageDigest.isEqual(
            signature(secret, timestamp, body).toByteArray(StandardCharsets.US_ASCII),
            provided.lowercase().toByteArray(StandardCharsets.US_ASCII),
        )
}

fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

