package com.local.voicehall.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import de.mkammerer.argon2.Argon2Factory
import io.ktor.server.auth.jwt.JWTPrincipal
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object Roles {
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val SCHEDULER = "scheduler"
    const val FINANCE = "finance"
    const val MEMBER = "member"
    const val AUDITOR = "auditor"
    const val DEVICE = "device"

    private val permissions = mapOf(
        OWNER to setOf("*"),
        ADMIN to setOf("rooms.write", "shifts.write", "customers.write", "tasks.write", "tasks.review", "revenue.write", "settlements.write", "reports.read", "audit.read", "devices.write", "amounts.exact"),
        SCHEDULER to setOf("rooms.read", "shifts.write", "customers.read", "tasks.write", "reports.read"),
        FINANCE to setOf("rooms.read", "customers.read", "revenue.write", "settlements.write", "reports.read", "audit.read", "amounts.exact"),
        MEMBER to setOf("rooms.read", "tasks.claim", "tasks.execute", "customers.read"),
        AUDITOR to setOf("rooms.read", "customers.read", "reports.read", "audit.read", "amounts.exact"),
        DEVICE to setOf("device.events.write"),
    )

    fun permissionsFor(role: String): Set<String> = permissions[role].orEmpty()

    fun allows(role: String, permission: String): Boolean {
        val granted = permissionsFor(role)
        return "*" in granted || permission in granted ||
            (permission.endsWith(".read") && permission.substringBeforeLast('.') + ".write" in granted)
    }
}

object Passwords {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    fun hash(password: CharArray): String =
        argon2.hash(3, 65_536, 1, password)

    fun verify(hash: String, password: CharArray): Boolean =
        argon2.verify(hash, password)
}

object Totp {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun normalizeAndValidate(secret: String): String {
        val normalized = secret
            .uppercase()
            .filterNot { it.isWhitespace() || it == '-' }
            .trimEnd('=')
        require(normalized.length >= 16) { "TOTP Base32 密钥至少 16 位" }
        decodeBase32(normalized)
        return normalized
    }

    fun verify(
        secret: String,
        code: String?,
        epochMillis: Long = System.currentTimeMillis(),
        window: Int = 1,
    ): Boolean {
        if (code == null || !code.matches(Regex("\\d{6}"))) return false
        return (-window..window).any { offset ->
            val candidate = generate(secret, epochMillis + offset * 30_000L)
            MessageDigest.isEqual(
                candidate.toByteArray(Charsets.US_ASCII),
                code.toByteArray(Charsets.US_ASCII),
            )
        }
    }

    internal fun generate(secret: String, epochMillis: Long): String {
        val key = decodeBase32(normalizeAndValidate(secret))
        val counter = epochMillis / 30_000L
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val digest = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array())
        val offset = digest.last().toInt() and 0x0f
        val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
        return (binary % 10.0.pow(6).toInt()).toString().padStart(6, '0')
    }

    private fun decodeBase32(secret: String): ByteArray {
        val output = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        secret.forEach { character ->
            val value = alphabet.indexOf(character)
            require(value >= 0) { "TOTP 密钥不是有效的 Base32" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output.write((buffer shr bits) and 0xff)
            }
        }
        return output.toByteArray()
    }
}

class JwtService(
    private val secret: String,
    private val issuer: String = "voice-hall-ops",
    private val audience: String = "voice-hall-clients",
) {
    private val algorithm = Algorithm.HMAC256(secret)
    val realm = "voice-hall-ops"

    fun token(account: AuthAccount, ttlSeconds: Long = 900): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(account.id)
            .withClaim("org", account.organizationId)
            .withClaim("role", account.role)
            .withClaim("name", account.displayName)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + ttlSeconds * 1_000))
            .sign(algorithm)
    }

    fun verifier() = JWT.require(algorithm).withIssuer(issuer).withAudience(audience).build()
}

data class RequestIdentity(
    val accountId: String,
    val organizationId: String,
    val role: String,
    val displayName: String,
)

fun JWTPrincipal.identity(): RequestIdentity = RequestIdentity(
    accountId = subject ?: error("token subject missing"),
    organizationId = payload.getClaim("org").asString(),
    role = payload.getClaim("role").asString(),
    displayName = payload.getClaim("name").asString(),
)
