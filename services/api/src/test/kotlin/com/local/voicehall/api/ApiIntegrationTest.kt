package com.local.voicehall.api

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ApiIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `login scheduling validation revenue reconciliation and device idempotency`() =
        testApplication {
            application { module(testEnvironment()) }

            val login = client.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"TestPassword-123"}""")
            }
            assertEquals(HttpStatusCode.OK, login.status)
            val token = json.parseToJsonElement(login.bodyAsText())
                .jsonObject.getValue("accessToken").jsonPrimitive.content

            val totpSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
            val createTotpAccount = client.post("/accounts") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(
                    """
                    {
                      "username":"finance-totp",
                      "displayName":"财务双重验证",
                      "password":"FinancePassword-123",
                      "role":"finance",
                      "totpSecret":"$totpSecret"
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.Created, createTotpAccount.status)
            val missingOtp = client.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"finance-totp","password":"FinancePassword-123"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, missingOtp.status)
            val validOtp = client.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"username":"finance-totp","password":"FinancePassword-123","otp":"${Totp.generate(totpSecret, System.currentTimeMillis())}"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, validOtp.status)

            val roomResponse = client.post("/rooms") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody("""{"name":"接口测试厅","platform":"ingkee","externalRoomId":"room-1"}""")
            }
            assertEquals(HttpStatusCode.Created, roomResponse.status)
            val roomId = json.parseToJsonElement(roomResponse.bodyAsText())
                .jsonObject.getValue("id").jsonPrimitive.content

            val start = System.currentTimeMillis() + 86_400_000
            val end = start + 3_600_000
            val shiftBody =
                """{"roomId":"$roomId","title":"晚班","startAtEpochMs":$start,"endAtEpochMs":$end,"hostFixedCents":10000,"hostHourlyCents":0}"""
            val firstShift = client.post("/shifts") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(shiftBody)
            }
            assertEquals(HttpStatusCode.Created, firstShift.status)
            val overlappingShift = client.post("/shifts") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(shiftBody.replace("晚班", "冲突班"))
            }
            assertEquals(HttpStatusCode.Conflict, overlappingShift.status)

            val importId = "a".repeat(64)
            val preview = client.post("/revenue-imports/preview") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(
                    """
                    {
                      "fileName":"official.csv",
                      "fileSha256":"$importId",
                      "expectedTotalCents":10000,
                      "rows":[{
                        "roomId":"$roomId",
                        "platform":"ingkee",
                        "transactionId":"T-1",
                        "grossCents":9900,
                        "occurredAtEpochMs":$start
                      }]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, preview.status)
            assertFalse(
                json.parseToJsonElement(preview.bodyAsText())
                    .jsonObject.getValue("canCommit").jsonPrimitive.boolean,
            )

            val registration = client.post("/devices/register") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody("""{"roomId":"$roomId","name":"接口测试厅控"}""")
            }
            assertEquals(HttpStatusCode.Created, registration.status)
            val registrationJson = json.parseToJsonElement(registration.bodyAsText()).jsonObject
            val deviceId = registrationJson.getValue("deviceId").jsonPrimitive.content
            val deviceSecret = registrationJson.getValue("deviceSecret").jsonPrimitive.content
            val eventBody =
                """{"eventId":"event-1","type":"seat_snapshot","roomId":"$roomId","occurredAtEpochMs":$start,"payload":"{}"}"""
            val timestamp = System.currentTimeMillis().toString()
            val signature = signature(deviceSecret, timestamp, eventBody)
            val accepted = client.sendDeviceEvent(deviceId, timestamp, signature, eventBody)
            assertEquals(HttpStatusCode.Accepted, accepted)
            val duplicate = client.sendDeviceEvent(deviceId, timestamp, signature, eventBody)
            assertEquals(HttpStatusCode.OK, duplicate)
        }

    private fun testEnvironment(): Map<String, String> = mapOf(
        "DATABASE_URL" to "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "JWT_SECRET" to "integration-test-jwt-secret",
        "DEVICE_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        "BOOTSTRAP_ORG" to "测试组织",
        "BOOTSTRAP_ADMIN_USER" to "admin",
        "BOOTSTRAP_ADMIN_PASSWORD" to "TestPassword-123",
        "BOOTSTRAP_ADMIN_NAME" to "测试所有者",
    )

    private fun signature(secret: String, timestamp: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal("$timestamp\n$body".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun HttpClient.sendDeviceEvent(
        deviceId: String,
        timestamp: String,
        signature: String,
        body: String,
    ): HttpStatusCode = post("/devices/events") {
        contentType(ContentType.Application.Json)
        header("X-Device-Id", deviceId)
        header("X-Timestamp", timestamp)
        header("X-Signature", signature)
        setBody(body)
    }.status
}
