package com.local.voicehall.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceProjectionIntegrationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `device events project idempotently into queue mic segments and attendance`() =
        testApplication {
            application { module(testEnvironment()) }
            val token = client.login()
            val roomId = client.createRoom(token)
            val registration = client.post("/devices/register") {
                authorized(token)
                setBody("""{"roomId":"$roomId","name":"Projection device"}""")
            }
            assertEquals(HttpStatusCode.Created, registration.status)
            val registrationJson = json.parseToJsonElement(registration.bodyAsText()).jsonObject
            val deviceId = registrationJson.getValue("deviceId").jsonPrimitive.content
            val deviceSecret = registrationJson.getValue("deviceSecret").jsonPrimitive.content

            val now = System.currentTimeMillis()
            val shiftId = UUID.randomUUID().toString()
            val queueId = UUID.randomUUID().toString()
            val bindingId = UUID.randomUUID().toString()
            val queuePayload = QueueEntryEventPayload(
                entryId = queueId,
                shiftId = shiftId,
                wechatName = "Host Wechat",
                role = "host",
                position = 1,
                state = "queued",
                createdBy = "admin",
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )

            val failedQueue = client.sendEvent(
                deviceId,
                deviceSecret,
                DeviceEventInput(
                    eventId = "queue-before-shift",
                    type = "queue_entry_upsert",
                    roomId = roomId,
                    occurredAtEpochMs = now,
                    payload = json.encodeToString(queuePayload),
                ),
            )
            assertFalse(failedQueue.accepted)

            val shiftPayload = ControlShiftEventPayload(
                shiftId = shiftId,
                label = "Projection shift",
                startAtEpochMs = now - 60_000,
                endAtEpochMs = now + 3_600_000,
                capacity = 8,
                cutoffAtEpochMs = now + 300_000,
                state = "open",
                createdBy = "admin",
                updatedAtEpochMs = now,
            )
            assertTrue(
                client.sendEvent(
                    deviceId,
                    deviceSecret,
                    DeviceEventInput(
                        eventId = "shift-1",
                        type = "shift_upsert",
                        roomId = roomId,
                        occurredAtEpochMs = now,
                        payload = json.encodeToString(shiftPayload),
                    ),
                ).accepted,
            )

            val bindingPayload = BindingEventPayload(
                bindingId = bindingId,
                wechatName = "Host Wechat",
                ingkeeName = "Host Ingkee",
                state = "approved",
                approvedBy = "admin",
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )
            assertTrue(
                client.sendEvent(
                    deviceId,
                    deviceSecret,
                    DeviceEventInput(
                        eventId = "binding-1",
                        type = "binding_upsert",
                        roomId = roomId,
                        occurredAtEpochMs = now,
                        payload = json.encodeToString(bindingPayload),
                    ),
                ).accepted,
            )

            val retriedQueue = client.sendEvent(
                deviceId,
                deviceSecret,
                DeviceEventInput(
                    eventId = "queue-before-shift",
                    type = "queue_entry_upsert",
                    roomId = roomId,
                    occurredAtEpochMs = now,
                    payload = json.encodeToString(queuePayload),
                ),
            )
            assertTrue(retriedQueue.accepted)

            suspend fun snapshot(
                eventId: String,
                capturedAt: Long,
                names: List<String>,
                pageStatus: String = "voice_room",
            ): DeviceAck =
                client.sendEvent(
                    deviceId,
                    deviceSecret,
                    DeviceEventInput(
                        eventId = eventId,
                        type = "seat_snapshot",
                        roomId = roomId,
                        occurredAtEpochMs = capturedAt,
                        payload = json.encodeToString(
                            SeatSnapshotEventPayload(
                                eventId = eventId,
                                capturedAtEpochMs = capturedAt,
                                seats = names.mapIndexed { index, name ->
                                    SeatObservationPayload(index + 1, name)
                                },
                                pageStatus = pageStatus,
                                source = "mock",
                            ),
                        ),
                    ),
                )

            assertTrue(snapshot("seat-1", now, listOf("Host Ingkee")).accepted)
            assertTrue(snapshot("seat-2", now + 5_000, listOf("Host Ingkee")).accepted)
            assertTrue(snapshot("seat-3", now + 6_000, emptyList()).accepted)
            val closingAck = snapshot("seat-4", now + 17_000, emptyList())
            assertTrue(closingAck.accepted)
            assertTrue(snapshot("seat-4", now + 17_000, emptyList()).accepted)
            assertTrue(snapshot("seat-5", now + 20_000, listOf("Host Ingkee")).accepted)
            assertTrue(snapshot("seat-6", now + 24_000, listOf("Host Ingkee")).accepted)
            assertTrue(snapshot("seat-7", now + 26_000, emptyList(), pageStatus = "unreadable").accepted)

            val segments = client.get("/mic-segments?roomId=$roomId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, segments.status)
            val segmentRows = json.parseToJsonElement(segments.bodyAsText()).jsonArray
            assertEquals(2, segmentRows.size)
            val segment = segmentRows.first { row ->
                row.jsonObject.getValue("state").jsonPrimitive.content == "closed"
            }.jsonObject
            assertEquals("closed", segment.getValue("state").jsonPrimitive.content)
            assertEquals("host", segment.getValue("role").jsonPrimitive.content)
            assertEquals(5, segment.getValue("durationSeconds").jsonPrimitive.content.toLong())
            assertEquals("Host Wechat", segment.getValue("wechatName").jsonPrimitive.content)
            val uncertainSegment = segmentRows.first { row ->
                row.jsonObject.getValue("state").jsonPrimitive.content == "uncertain"
            }.jsonObject
            assertEquals(4, uncertainSegment.getValue("durationSeconds").jsonPrimitive.content.toLong())
            assertEquals(
                "Page unreadable",
                uncertainSegment.getValue("correctionReason").jsonPrimitive.content,
            )

            val attendance = client.get("/attendance?roomId=$roomId&shiftId=$shiftId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, attendance.status)
            val attendanceRows = json.parseToJsonElement(attendance.bodyAsText()).jsonArray
            assertEquals(1, attendanceRows.size)
            val attendanceRow = attendanceRows.single().jsonObject
            assertEquals(9, attendanceRow.getValue("hostSeconds").jsonPrimitive.content.toLong())
            assertEquals(0, attendanceRow.getValue("ordinarySeconds").jsonPrimitive.content.toLong())
            assertEquals(2, attendanceRow.getValue("segmentCount").jsonPrimitive.content.toInt())

            val queue = client.get("/queue-entries?roomId=$roomId&shiftId=$shiftId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(1, json.parseToJsonElement(queue.bodyAsText()).jsonArray.size)
            val bindings = client.get("/bindings?roomId=$roomId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(1, json.parseToJsonElement(bindings.bodyAsText()).jsonArray.size)
        }

    private suspend fun HttpClient.login(): String =
        post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"TestPassword-123"}""")
        }.let { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            json.parseToJsonElement(response.bodyAsText())
                .jsonObject.getValue("accessToken").jsonPrimitive.content
        }

    private suspend fun HttpClient.createRoom(token: String): String =
        post("/rooms") {
            authorized(token)
            setBody("""{"name":"Projection room","platform":"ingkee"}""")
        }.let { response ->
            assertEquals(HttpStatusCode.Created, response.status)
            json.parseToJsonElement(response.bodyAsText())
                .jsonObject.getValue("id").jsonPrimitive.content
        }

    private suspend fun HttpClient.sendEvent(
        deviceId: String,
        deviceSecret: String,
        event: DeviceEventInput,
    ): DeviceAck {
        val body = json.encodeToString(event)
        val timestamp = System.currentTimeMillis().toString()
        val response = post("/devices/events") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", deviceId)
            header("X-Timestamp", timestamp)
            header("X-Signature", signature(deviceSecret, timestamp, body))
            setBody(body)
        }
        assertTrue(response.status == HttpStatusCode.Accepted || response.status == HttpStatusCode.OK)
        return json.decodeFromString(response.bodyAsText())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorized(token: String) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun signature(secret: String, timestamp: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal("$timestamp\n$body".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun testEnvironment(): Map<String, String> = mapOf(
        "DATABASE_URL" to "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "JWT_SECRET" to "projection-integration-test-jwt-secret",
        "DEVICE_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 9 }),
        "BOOTSTRAP_ORG" to "Projection Test Organization",
        "BOOTSTRAP_ADMIN_USER" to "admin",
        "BOOTSTRAP_ADMIN_PASSWORD" to "TestPassword-123",
        "BOOTSTRAP_ADMIN_NAME" to "Test Owner",
    )
}
