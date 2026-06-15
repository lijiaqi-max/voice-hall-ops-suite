package com.local.interactionassistant.executor.cloud

import com.local.interactionassistant.executor.data.CloudConfigEntity
import com.local.interactionassistant.executor.data.CloudOutboxEntity
import com.local.interactionassistant.executor.data.CloudTaskEntity
import com.local.interactionassistant.executor.data.ExecutorDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@Serializable
private data class LoginInput(val username: String, val password: String)

@Serializable
private data class LoginResponse(
    val accessToken: String,
    val account: AccountDto,
)

@Serializable
private data class AccountDto(
    val id: String,
    val displayName: String,
    val role: String,
)

@Serializable
private data class TaskDto(
    val id: String,
    val roomId: String? = null,
    val customerId: String,
    val customerName: String,
    val title: String,
    val brief: String,
    val state: String,
    val priority: Int,
    val assignedAccountId: String? = null,
    val resultChannel: String? = null,
    val resultNote: String? = null,
    val nextFollowUpAtEpochMs: Long? = null,
    val valueLevel: String = "standard",
    val visibleRevenueCents: Long? = null,
)

@Serializable
data class TaskSubmission(
    val channel: String,
    val note: String,
    val nextFollowUpAtEpochMs: Long? = null,
)

@Serializable
private data class LegacyMigrationInput(
    val source: String = "interaction-assistant",
    val sourceVersion: String = "1.2.0",
    val recordCount: Int,
    val payloadJson: String,
)

@Serializable
private data class LegacyPayload(
    val candidates: List<LegacyCandidate>,
    val interactions: Int,
    val tasks: Int,
)

@Serializable
private data class LegacyCandidate(
    val externalUserId: String,
    val displayName: String,
    val relationshipStage: String,
    val contactEligibility: String,
    val lastInteractionAtEpochMs: Long? = null,
)

class CloudRepository(private val database: ExecutorDatabase) {
    private val dao = database.dao()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val config: Flow<CloudConfigEntity?> = dao.observeCloudConfig()
    val tasks: Flow<List<CloudTaskEntity>> = dao.observeCloudTasks()

    suspend fun login(baseUrl: String, username: String, password: String) {
        val normalized = normalizeBaseUrl(baseUrl)
        val response: LoginResponse = execute(
            baseUrl = normalized,
            path = "/auth/login",
            method = "POST",
            body = json.encodeToString(LoginInput(username.trim(), password)),
            token = null,
        )
        dao.saveCloudConfig(
            CloudConfigEntity(
                baseUrl = normalized,
                username = username.trim(),
                accessToken = response.accessToken,
                accountId = response.account.id,
                displayName = response.account.displayName,
                role = response.account.role,
            ),
        )
        sync()
    }

    suspend fun logout() {
        val current = dao.getCloudConfig() ?: CloudConfigEntity()
        dao.saveCloudConfig(current.copy(accessToken = "", accountId = "", displayName = "", role = ""))
        dao.clearCloudTasks()
    }

    suspend fun sync() {
        val config = requireConfig()
        replayOutbox(config)
        val remote: List<TaskDto> = execute(
            config.baseUrl,
            "/tasks",
            "GET",
            null,
            config.accessToken,
        )
        dao.clearCloudTasks()
        dao.upsertCloudTasks(remote.map { it.toEntity() })
    }

    suspend fun claim(taskId: String) = action(taskId, "claim", null, "claimed")

    suspend fun start(taskId: String) = action(taskId, "start", null, "in_progress")

    suspend fun submit(taskId: String, submission: TaskSubmission) =
        action(taskId, "submit", json.encodeToString(submission), "submitted")

    suspend fun uploadLegacyPreview(): Int {
        val config = requireConfig()
        val candidates = dao.getAllCandidates()
        val interactions = dao.getAllInteractionEvents()
        val tasks = dao.getAllTasks()
        val payload = LegacyPayload(
            candidates = candidates.map {
                LegacyCandidate(
                    externalUserId = it.externalUserId,
                    displayName = it.displayName,
                    relationshipStage = it.relationshipStage,
                    contactEligibility = it.contactEligibility,
                    lastInteractionAtEpochMs = it.lastInteractionAtEpochMs,
                )
            },
            interactions = interactions.size,
            tasks = tasks.size,
        )
        execute<Map<String, String>>(
            config.baseUrl,
            "/migration/legacy",
            "POST",
            json.encodeToString(
                LegacyMigrationInput(
                    recordCount = candidates.size + interactions.size + tasks.size,
                    payloadJson = json.encodeToString(payload),
                ),
            ),
            config.accessToken,
        )
        return candidates.size + interactions.size + tasks.size
    }

    private suspend fun action(
        taskId: String,
        action: String,
        body: String?,
        offlineState: String,
    ) {
        val config = requireConfig()
        val path = "/tasks/$taskId/$action"
        try {
            val task: TaskDto = execute(config.baseUrl, path, "POST", body, config.accessToken)
            dao.upsertCloudTasks(listOf(task.toEntity()))
        } catch (error: Exception) {
            val current = dao.getCloudTask(taskId) ?: throw error
            dao.upsertCloudTasks(
                listOf(current.copy(state = offlineState, updatedAtEpochMs = System.currentTimeMillis())),
            )
            dao.enqueueCloudRequest(
                CloudOutboxEntity(
                    id = UUID.randomUUID().toString(),
                    method = "POST",
                    path = path,
                    bodyJson = body.orEmpty(),
                    dedupeKey = "$taskId:$action:${current.updatedAtEpochMs}",
                ),
            )
        }
    }

    private suspend fun replayOutbox(config: CloudConfigEntity) {
        dao.getCloudOutbox().forEach { queued ->
            try {
                execute<TaskDto>(
                    config.baseUrl,
                    queued.path,
                    queued.method,
                    queued.bodyJson.ifBlank { null },
                    config.accessToken,
                )
                dao.deleteCloudOutbox(queued.id)
            } catch (error: Exception) {
                dao.markCloudOutboxFailed(queued.id, error.message ?: "同步失败")
                return
            }
        }
    }

    private suspend fun requireConfig(): CloudConfigEntity =
        dao.getCloudConfig()?.takeIf { it.baseUrl.isNotBlank() && it.accessToken.isNotBlank() }
            ?: error("请先登录运营中台")

    private fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        require(normalized.startsWith("https://")) { "正式环境必须使用 HTTPS 地址" }
        return normalized
    }

    private suspend inline fun <reified T> execute(
        baseUrl: String,
        path: String,
        method: String,
        body: String?,
        token: String?,
    ): T = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(connection.responseCode in 200..299) {
                "服务器返回 ${connection.responseCode}：$responseText"
            }
            json.decodeFromString<T>(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun TaskDto.toEntity() = CloudTaskEntity(
        id = id,
        roomId = roomId,
        customerId = customerId,
        customerName = customerName,
        title = title,
        brief = brief,
        state = state,
        priority = priority,
        assignedAccountId = assignedAccountId,
        resultChannel = resultChannel,
        resultNote = resultNote,
        nextFollowUpAtEpochMs = nextFollowUpAtEpochMs,
        valueLevel = valueLevel,
        visibleRevenueCents = visibleRevenueCents,
    )
}
