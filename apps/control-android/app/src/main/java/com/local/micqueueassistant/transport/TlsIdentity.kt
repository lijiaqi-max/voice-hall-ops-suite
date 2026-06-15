package com.local.micqueueassistant.transport

import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

object TlsIdentity {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "mic-queue-wss-server"

    fun serverContext(): Pair<SSLContext, String> {
        ensureKey()
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
        val keyManagerFactory = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm(),
        ).apply {
            init(keyStore, null)
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, SecureRandom())
        }
        return sslContext to certificate.sha256()
    }

    @SuppressLint("CustomX509TrustManager")
    fun clientContext(
        expectedFingerprint: String?,
        onObservedFingerprint: (String) -> Unit,
    ): SSLContext {
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val cert = chain.firstOrNull() ?: error("服务器未提供证书")
                val observed = cert.sha256()
                onObservedFingerprint(observed)
                if (!expectedFingerprint.isNullOrBlank()) {
                    check(observed.equals(expectedFingerprint, ignoreCase = true)) {
                        "服务器证书指纹不匹配"
                    }
                }
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(manager), SecureRandom())
        }
    }

    private fun ensureKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(ALIAS)) return
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setKeySize(2048)
            .setCertificateSubject(X500Principal("CN=Mic Queue Local WSS"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - 86_400_000L))
            .setCertificateNotAfter(Date(now + 3_650L * 86_400_000L))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun X509Certificate.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(encoded)
            .joinToString("") { "%02x".format(it) }
}
