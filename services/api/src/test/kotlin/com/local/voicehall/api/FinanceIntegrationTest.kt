package com.local.voicehall.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinanceIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `settlement blocks unallocated revenue records estimates and applies auditable adjustments`() =
        testApplication {
            application { module(testEnvironment()) }
            val token = client.login()
            val roomId = client.create("/rooms", token, """{"name":"财务闭环厅","platform":"ingkee"}""")
                .getValue("id").jsonPrimitive.content
            val memberId = client.create(
                "/accounts",
                token,
                """
                {
                  "username":"finance-member",
                  "displayName":"成员甲",
                  "password":"MemberPassword-123",
                  "role":"member",
                  "roomIds":["$roomId"]
                }
                """.trimIndent(),
            ).getValue("id").jsonPrimitive.content
            val customerId = client.create(
                "/customers",
                token,
                """
                {
                  "displayName":"客户甲",
                  "platform":"ingkee",
                  "externalUserId":"finance-customer-1"
                }
                """.trimIndent(),
            ).getValue("id").jsonPrimitive.content

            val start = System.currentTimeMillis() - 3_600_000
            val end = start + 3_600_000
            client.create(
                "/shifts",
                token,
                """
                {
                  "roomId":"$roomId",
                  "title":"财务测试班",
                  "startAtEpochMs":$start,
                  "endAtEpochMs":$end,
                  "hostFixedCents":1000,
                  "hostHourlyCents":3600
                }
                """.trimIndent(),
            )
            client.create(
                "/settlements/expenses",
                token,
                """
                {
                  "roomId":"$roomId",
                  "category":"场地",
                  "amountCents":2000,
                  "note":"测试支出",
                  "occurredAtEpochMs":${start + 1_000}
                }
                """.trimIndent(),
            )
            val previewImport = client.post("/revenue-imports/preview") {
                authorized(token)
                setBody(
                    """
                    {
                      "fileName":"finance.csv",
                      "fileSha256":"${"b".repeat(64)}",
                      "expectedTotalCents":100000,
                      "rows":[{
                        "roomId":"$roomId",
                        "platform":"ingkee",
                        "transactionId":"FIN-1",
                        "customerExternalId":"finance-customer-1",
                        "customerDisplayName":"客户甲",
                        "grossCents":100000,
                        "occurredAtEpochMs":${start + 2_000}
                      }]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, previewImport.status)
            val importId = json.parseToJsonElement(previewImport.bodyAsText())
                .jsonObject.getValue("importId").jsonPrimitive.content
            assertEquals(
                HttpStatusCode.OK,
                client.post("/revenue-imports/$importId/commit") { authorized(token) }.status,
            )

            val previewPayload =
                """{"roomId":"$roomId","periodStartEpochMs":$start,"periodEndEpochMs":$end}"""
            val unallocatedPreview = client.post("/settlements/preview") {
                authorized(token)
                setBody(previewPayload)
            }
            assertEquals(HttpStatusCode.OK, unallocatedPreview.status)
            val unallocatedJson = json.parseToJsonElement(unallocatedPreview.bodyAsText()).jsonObject
            assertEquals(100000, unallocatedJson.getValue("unallocatedRevenueCents").jsonPrimitive.content.toLong())
            assertTrue(unallocatedJson.getValue("hostCostEstimated").jsonPrimitive.content.toBoolean())
            assertEquals(
                HttpStatusCode.Conflict,
                client.post("/settlements/close") {
                    authorized(token)
                    setBody(previewPayload)
                }.status,
            )

            client.create(
                "/tasks",
                token,
                """
                {
                  "roomId":"$roomId",
                  "customerId":"$customerId",
                  "title":"财务归属",
                  "brief":"用于确认成员佣金归属",
                  "assignedAccountId":"$memberId",
                  "publish":true
                }
                """.trimIndent(),
            )
            assertEquals(
                HttpStatusCode.Conflict,
                client.post("/settlements/close") {
                    authorized(token)
                    setBody(previewPayload)
                }.status,
            )

            val close = client.post("/settlements/close") {
                authorized(token)
                setBody(
                    """
                    {
                      "roomId":"$roomId",
                      "periodStartEpochMs":$start,
                      "periodEndEpochMs":$end,
                      "hostCostOverrideReason":"已人工核对主持排班，比赛演示采用预估费用"
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, close.status)
            val closed = json.parseToJsonElement(close.bodyAsText()).jsonObject
            val settlementId = closed.getValue("id").jsonPrimitive.content
            assertEquals(0, closed.getValue("unallocatedRevenueCents").jsonPrimitive.content.toLong())
            assertEquals(4600, closed.getValue("hostCostCents").jsonPrimitive.content.toLong())
            assertEquals(0, closed.getValue("reconciliationDifferenceCents").jsonPrimitive.content.toLong())

            val adjustment = client.post("/settlements/$settlementId/adjustments") {
                authorized(token)
                setBody("""{"amountCents":500,"effect":"payable","reason":"补记主持交通补贴"}""")
            }
            assertEquals(HttpStatusCode.OK, adjustment.status)
            val adjustmentJson = json.parseToJsonElement(adjustment.bodyAsText()).jsonObject
            assertEquals(
                adjustmentJson.getValue("beforePayableCents").jsonPrimitive.content.toLong() + 500,
                adjustmentJson.getValue("afterPayableCents").jsonPrimitive.content.toLong(),
            )
            assertEquals(
                adjustmentJson.getValue("beforeNetProfitCents").jsonPrimitive.content.toLong() - 500,
                adjustmentJson.getValue("afterNetProfitCents").jsonPrimitive.content.toLong(),
            )

            val report = client.get("/reports/finance/$settlementId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, report.status)
            val reportJson = json.parseToJsonElement(report.bodyAsText()).jsonObject
            val finalSettlement = reportJson.getValue("settlement").jsonObject
            assertEquals(0, finalSettlement.getValue("reconciliationDifferenceCents").jsonPrimitive.content.toLong())
            assertEquals(1, reportJson.getValue("memberCommissions").jsonArray.size)
            assertEquals(1, reportJson.getValue("hostCosts").jsonArray.size)
            assertEquals(1, reportJson.getValue("expenses").jsonArray.size)
            assertEquals(1, reportJson.getValue("adjustments").jsonArray.size)

            val export = client.get("/reports/export.xlsx?settlementId=$settlementId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, export.status)
            assertTrue(export.headers[HttpHeaders.ContentDisposition]?.contains(".xlsx") == true)
            assertTrue(export.bodyAsBytes().take(2).toByteArray().contentEquals(byteArrayOf(0x50, 0x4b)))

            assertEquals(
                1,
                json.parseToJsonElement(
                    client.get("/revenue-imports") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.bodyAsText(),
                ).jsonArray.size,
            )
            assertEquals(
                1,
                json.parseToJsonElement(
                    client.get("/settlements") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.bodyAsText(),
                ).jsonArray.size,
            )
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

    private suspend fun HttpClient.create(
        path: String,
        token: String,
        body: String,
    ) = post(path) {
        authorized(token)
        setBody(body)
    }.let { response ->
        assertTrue(response.status == HttpStatusCode.Created || response.status == HttpStatusCode.OK)
        json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorized(token: String) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun testEnvironment(): Map<String, String> = mapOf(
        "DATABASE_URL" to "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "JWT_SECRET" to "finance-integration-test-jwt-secret",
        "DEVICE_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 6 }),
        "BOOTSTRAP_ORG" to "Finance Test Organization",
        "BOOTSTRAP_ADMIN_USER" to "admin",
        "BOOTSTRAP_ADMIN_PASSWORD" to "TestPassword-123",
        "BOOTSTRAP_ADMIN_NAME" to "Test Owner",
    )
}
