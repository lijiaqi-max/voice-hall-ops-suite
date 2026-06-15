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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SecurityAndScopeIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `refresh rotation room scope task integrity device revoke and login throttle`() =
        testApplication {
            application { module(testEnvironment()) }

            val adminLogin = client.login("admin", "TestPassword-123")
            val adminToken = adminLogin.getValue("accessToken").jsonPrimitive.content
            val refreshToken = adminLogin.getValue("refreshToken").jsonPrimitive.content

            val refreshed = client.post("/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody("""{"refreshToken":"$refreshToken"}""")
            }
            assertEquals(HttpStatusCode.OK, refreshed.status)
            val refreshedJson = json.parseToJsonElement(refreshed.bodyAsText()).jsonObject
            val rotatedRefresh = refreshedJson.getValue("refreshToken").jsonPrimitive.content
            assertNotEquals(refreshToken, rotatedRefresh)

            val reusedRefresh = client.post("/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody("""{"refreshToken":"$refreshToken"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, reusedRefresh.status)
            val logout = client.post("/auth/logout") {
                contentType(ContentType.Application.Json)
                setBody("""{"refreshToken":"$rotatedRefresh"}""")
            }
            assertEquals(HttpStatusCode.OK, logout.status)
            val loggedOutRefresh = client.post("/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody("""{"refreshToken":"$rotatedRefresh"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, loggedOutRefresh.status)

            val room1 = client.createRoom(adminToken, "Room One")
            val room2 = client.createRoom(adminToken, "Room Two")
            client.post("/accounts") {
                authorized(adminToken)
                setBody(
                    """
                    {
                      "username":"member-one",
                      "displayName":"Member One",
                      "password":"MemberPassword-123",
                      "role":"member",
                      "roomIds":["$room1"]
                    }
                    """.trimIndent(),
                )
            }.also { assertEquals(HttpStatusCode.Created, it.status) }
            client.post("/accounts") {
                authorized(adminToken)
                setBody(
                    """
                    {
                      "username":"scheduler-one",
                      "displayName":"Scheduler One",
                      "password":"SchedulerPassword-123",
                      "role":"scheduler",
                      "roomIds":["$room1"]
                    }
                    """.trimIndent(),
                )
            }.also { assertEquals(HttpStatusCode.Created, it.status) }

            val customer1 = client.createCustomer(adminToken, "Customer One", "customer-1")
            val customer2 = client.createCustomer(adminToken, "Customer Two", "customer-2")
            val memberId = client.login("member-one", "MemberPassword-123")
                .getValue("account").jsonObject.getValue("id").jsonPrimitive.content

            val concurrentResults = coroutineScope {
                listOf("A", "B").map { suffix ->
                    async {
                        client.post("/tasks") {
                            authorized(adminToken)
                            setBody(
                                """
                                {
                                  "roomId":"$room1",
                                  "customerId":"$customer1",
                                  "title":"Concurrent $suffix",
                                  "brief":"Only one active task is allowed"
                                }
                                """.trimIndent(),
                            )
                        }.status
                    }
                }.awaitAll()
            }
            assertEquals(1, concurrentResults.count { it == HttpStatusCode.Created })
            assertEquals(1, concurrentResults.count { it == HttpStatusCode.Conflict })

            client.post("/tasks") {
                authorized(adminToken)
                setBody(
                    """
                    {
                      "roomId":"$room2",
                      "customerId":"$customer2",
                      "title":"Other room",
                      "brief":"Must not be visible to room one member"
                    }
                    """.trimIndent(),
                )
            }.also { assertEquals(HttpStatusCode.Created, it.status) }

            val memberToken = client.login("member-one", "MemberPassword-123")
                .getValue("accessToken").jsonPrimitive.content
            val visibleTasks = client.get("/tasks") {
                header(HttpHeaders.Authorization, "Bearer $memberToken")
            }
            assertEquals(HttpStatusCode.OK, visibleTasks.status)
            val visibleTaskRows = json.parseToJsonElement(visibleTasks.bodyAsText()).jsonArray
            assertEquals(1, visibleTaskRows.size)
            assertEquals(room1, visibleTaskRows.single().jsonObject.getValue("roomId").jsonPrimitive.content)

            val assignedCustomer = client.createCustomer(adminToken, "Assigned Customer", "customer-3")
            val assignedTask = client.post("/tasks") {
                authorized(adminToken)
                setBody(
                    """
                    {
                      "roomId":"$room1",
                      "customerId":"$assignedCustomer",
                      "title":"Assigned task",
                      "brief":"Member operation idempotency",
                      "assignedAccountId":"$memberId"
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.Created, assignedTask.status)
            val assignedTaskId = json.parseToJsonElement(assignedTask.bodyAsText())
                .jsonObject.getValue("id").jsonPrimitive.content

            val operationId = "operation-${UUID.randomUUID()}"
            val started = client.post("/tasks/$assignedTaskId/start?expectedVersion=0") {
                header(HttpHeaders.Authorization, "Bearer $memberToken")
                header("X-Client-Operation-Id", operationId)
            }
            assertEquals(HttpStatusCode.OK, started.status)
            val duplicateStart = client.post("/tasks/$assignedTaskId/start?expectedVersion=0") {
                header(HttpHeaders.Authorization, "Bearer $memberToken")
                header("X-Client-Operation-Id", operationId)
            }
            assertEquals(HttpStatusCode.OK, duplicateStart.status)
            assertEquals(
                1,
                json.parseToJsonElement(duplicateStart.bodyAsText())
                    .jsonObject.getValue("version").jsonPrimitive.content.toInt(),
            )

            val schedulerToken = client.login("scheduler-one", "SchedulerPassword-123")
                .getValue("accessToken").jsonPrimitive.content
            val scopedReport = client.get("/reports/summary?start=0&end=${System.currentTimeMillis() + 60_000}") {
                header(HttpHeaders.Authorization, "Bearer $schedulerToken")
            }
            assertEquals(HttpStatusCode.OK, scopedReport.status)
            val scopedReportJson = json.parseToJsonElement(scopedReport.bodyAsText()).jsonObject
            assertEquals(
                1,
                scopedReportJson.getValue("roomTotals").jsonArray.size,
            )
            assertEquals(
                room1,
                scopedReportJson.getValue("roomTotals").jsonArray.single()
                    .jsonObject.getValue("roomId").jsonPrimitive.content,
            )
            assertEquals(
                2,
                scopedReportJson.getValue("customerTotal").jsonPrimitive.content.toInt(),
            )

            val registration = client.post("/devices/register") {
                authorized(adminToken)
                setBody("""{"roomId":"$room1","name":"Test control"}""")
            }
            assertEquals(HttpStatusCode.Created, registration.status)
            val deviceId = json.parseToJsonElement(registration.bodyAsText())
                .jsonObject.getValue("deviceId").jsonPrimitive.content
            val disabled = client.post("/devices/$deviceId/disable") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
            }
            assertEquals(HttpStatusCode.OK, disabled.status)
            assertEquals(
                "false",
                json.parseToJsonElement(disabled.bodyAsText())
                    .jsonObject.getValue("enabled").jsonPrimitive.content,
            )

            repeat(5) {
                val failed = client.post("/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"blocked-user","password":"wrong"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, failed.status)
            }
            val blocked = client.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"blocked-user","password":"wrong"}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)

            val changedPassword = client.post("/auth/change-password") {
                authorized(adminToken)
                setBody(
                    """
                    {
                      "currentPassword":"TestPassword-123",
                      "newPassword":"NewTestPassword-456"
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, changedPassword.status, changedPassword.bodyAsText())
            val revokedAccess = client.get("/auth/me") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
            }
            assertEquals(HttpStatusCode.Unauthorized, revokedAccess.status)
            client.login("admin", "NewTestPassword-456")
        }

    private suspend fun HttpClient.login(username: String, password: String) =
        post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }.let { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            json.parseToJsonElement(response.bodyAsText()).jsonObject
        }

    private suspend fun HttpClient.createRoom(token: String, name: String): String =
        post("/rooms") {
            authorized(token)
            setBody("""{"name":"$name","platform":"ingkee"}""")
        }.let { response ->
            assertEquals(HttpStatusCode.Created, response.status)
            json.parseToJsonElement(response.bodyAsText())
                .jsonObject.getValue("id").jsonPrimitive.content
        }

    private suspend fun HttpClient.createCustomer(
        token: String,
        name: String,
        externalId: String,
    ): String = post("/customers") {
        authorized(token)
        setBody(
            """
            {
              "displayName":"$name",
              "platform":"ingkee",
              "externalUserId":"$externalId"
            }
            """.trimIndent(),
        )
    }.let { response ->
        assertEquals(HttpStatusCode.Created, response.status)
        json.parseToJsonElement(response.bodyAsText())
            .jsonObject.getValue("id").jsonPrimitive.content
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorized(token: String) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun testEnvironment(): Map<String, String> = mapOf(
        "DATABASE_URL" to "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "JWT_SECRET" to "security-integration-test-jwt-secret",
        "DEVICE_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 8 }),
        "BOOTSTRAP_ORG" to "Security Test Organization",
        "BOOTSTRAP_ADMIN_USER" to "admin",
        "BOOTSTRAP_ADMIN_PASSWORD" to "TestPassword-123",
        "BOOTSTRAP_ADMIN_NAME" to "Test Owner",
    )
}
