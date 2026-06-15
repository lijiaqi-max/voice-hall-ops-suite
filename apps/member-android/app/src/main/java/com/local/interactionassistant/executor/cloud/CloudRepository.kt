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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@Serializable
private data class LoginInput(
    val username: String,
    val password: String,
    val otp: String? = null,
)

@Serializable
private data class RefreshTokenInput(val refreshToken: String)

@Serializable
private data class LoginResponse(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String,
    val refreshExpiresInSeconds: Long,
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
    val version: Int = 0,
)

@Serializable
data class TaskSubmission(
    val channel: String,
    val note: String,
    val nextFollowUpAtEpochMs: Long? = null,
)

@Serializable
private data class TaskSubmitRequest(
    val channel: String,
    val note: String,
    val nextFollowUpAtEpochMs: Long? = null,
    val expectedVersion: Int,
    val clientOperationId: String,
)

@Serializable
private data class AdviceInput(
    val relationshipStage: String,
    val interactionRecencyBucket: String,
    val valueLevel: String,
    val taskPurpose: String,
    val tone: String,
)

@Serializable
data class MemberAdvice(
    val advice: String,
    val riskTags: List<String>,
    val fallbackUsed: Boolean,
    val humanConfirmationRequired: Boolean,
)

@Serializable
data class MemberStats(
    val claimedCount: Int,
    val submittedCount: Int,
    val approvedCount: Int,
    val followUpCompletedCount: Int,
    val followUpCompletionRateBps: Int,
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

class CloudRepository(
    private val database: ExecutorDatabase,
    private val tokenCipher: TokenCipher,
) {
    private val dao = database.dao()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val config: Flow<CloudConfigEntity?> = dao.observeCloudConfig()
    val tasks: Flow<List<CloudTaskEntity>> = dao.observeCloudTasks()
    val pendingCount: Flow<Int> = dao.observePendingCloudRequestCount()
    val conflictCount: Flow<Int> = dao.observeCloudConflictCount()

    suspend fun login(baseUrl: String, username: String, password: String, otp: String?) {
        val normalized = normalizeBaseUrl(baseUrl)
        val response: LoginResponse = execute(
            baseUrl = normalized,
            path = "/auth/login",
            method = "POST",
            body = json.encodeToString(LoginInput(username.trim(), password, otp?.trim()?.ifBlank { null })),
            token = null,
        )
        saveAuthenticatedConfig(normalized, username.trim(), response)
        sync()
    }

    suspend fun logout() {
        val pending = dao.getCloudOutbox()
        check(pending.isEmpty()) { "仍有 ${pending.size} 条待同步或冲突操作，请先处理后再退出" }
        val current = dao.getCloudConfig()
        val refreshToken = current?.refreshToken?.let(tokenCipher::decrypt).orEmpty()
        if (current != null && refreshToken.isNotBlank()) {
            runCatching {
                execute<Map<String, String>>(
                    current.baseUrl,
                    "/auth/logout",
                    "POST",
                    json.encodeToString(RefreshTokenInput(refreshToken)),
                    token = null,
                )
            }
        }
        dao.saveCloudConfig(
            (current ?: CloudConfigEntity()).copy(
                accessToken = "",
                refreshToken = "",
                accessTokenExpiresAtEpochMs = 0,
                refreshTokenExpiresAtEpochMs = 0,
                accountId = "",
                displayName = "",
                role = "",
                authState = "signed_out",
                lastSyncError = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        dao.clearCloudTasks()
        dao.clearCloudOutbox()
    }

    suspend fun sync() {
        val startedConfig = requireConfig()
        try {
            replayOutbox()
            val remote: List<TaskDto> = authorizedExecute(
                startedConfig.baseUrl,
                "/tasks",
                "GET",
                body = null,
            )
            val local = dao.getAllCloudTasks().associateBy(CloudTaskEntity::id)
            val remoteIds = remote.map(TaskDto::id).toSet()
            val merged = remote.map { dto ->
                val cached = local[dto.id]
                dto.toEntity().let { fresh ->
                    if (cached != null && cached.syncState in setOf("pending", "conflict")) {
                        fresh.copy(
                            syncState = cached.syncState,
                            pendingOperationId = cached.pendingOperationId,
                            conflictMessage = cached.conflictMessage,
                        )
                    } else {
                        fresh
                    }
                }
            } + local.values.filter {
                it.id !in remoteIds && it.syncState in setOf("pending", "conflict")
            }
            dao.clearCloudTasks()
            dao.upsertCloudTasks(merged)
            val current = requireConfig()
            dao.saveCloudConfig(
                current.copy(
                    authState = "authenticated",
                    lastSyncAtEpochMs = System.currentTimeMillis(),
                    lastSyncError = null,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        } catch (error: Exception) {
            dao.getCloudConfig()?.let {
                dao.saveCloudConfig(
                    it.copy(
                        lastSyncError = error.message ?: "同步失败",
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
            throw error
        }
    }

    suspend fun claim(taskId: String) = action(taskId, "claim", null)

    suspend fun start(taskId: String) = action(taskId, "start", null)

    suspend fun submit(taskId: String, submission: TaskSubmission) =
        action(taskId, "submit", submission)

    suspend fun generateAdvice(taskId: String): MemberAdvice {
        val config = requireConfig()
        val task = dao.getCloudTask(taskId) ?: error("本地作业不存在，请先同步")
        return authorizedExecute(
            config.baseUrl,
            "/advice/generate",
            "POST",
            json.encodeToString(
                AdviceInput(
                    relationshipStage = "已有互动关系",
                    interactionRecencyBucket = "由任务上下文确认",
                    valueLevel = task.valueLevel,
                    taskPurpose = task.title,
                    tone = "温和",
                ),
            ),
        )
    }

    suspend fun personalStats(): MemberStats {
        val config = requireConfig()
        return authorizedExecute(config.baseUrl, "/tasks-stats/me", "GET", null)
    }

    suspend fun discardConflictAndSync(taskId: String) {
        dao.deleteCloudTaskConflicts(taskId)
        dao.updateCloudTaskSyncState(
            taskId,
            syncState = "synced",
            operationId = null,
            conflictMessage = null,
            updatedAt = System.currentTimeMillis(),
        )
        sync()
    }

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
        authorizedExecute<Map<String, String>>(
            config.baseUrl,
            "/migration/legacy",
            "POST",
            json.encodeToString(
                LegacyMigrationInput(
                    recordCount = candidates.size + interactions.size + tasks.size,
                    payloadJson = json.encodeToString(payload),
                ),
            ),
        )
        return candidates.size + interactions.size + tasks.size
    }

    private suspend fun action(
        taskId: String,
        action: String,
        submission: TaskSubmission?,
    ) {
        val config = requireConfig()
        val current = dao.getCloudTask(taskId) ?: error("本地作业不存在，请先同步")
        check(current.syncState != "pending") { "该作业已有待同步操作" }
        check(current.syncState != "conflict") { "该作业存在冲突，请先同步或联系管理员" }
        val operationId = UUID.randomUUID().toString()
        val path = if (action in setOf("claim", "start")) {
            "/tasks/$taskId/$action?expectedVersion=${current.serverVersion}"
        } else {
            "/tasks/$taskId/$action"
        }
        val body = submission?.let {
            json.encodeToString(
                TaskSubmitRequest(
                    channel = it.channel,
                    note = it.note,
                    nextFollowUpAtEpochMs = it.nextFollowUpAtEpochMs,
                    expectedVersion = current.serverVersion,
                    clientOperationId = operationId,
                ),
            )
        }
        try {
            val task: TaskDto = authorizedExecute(
                config.baseUrl,
                path,
                "POST",
                body,
                headers = mapOf("X-Client-Operation-Id" to operationId),
            )
            dao.upsertCloudTasks(listOf(task.toEntity()))
        } catch (error: ApiException) {
            if (error.status == HttpURLConnection.HTTP_CONFLICT) {
                enqueueConflict(current, action, operationId, path, body, error.message.orEmpty())
                error("服务端作业已变化，本次操作未生效，请同步后处理冲突")
            }
            throw error
        } catch (error: IOException) {
            enqueueOfflineOperation(current, action, operationId, path, body)
        }
    }

    private suspend fun enqueueOfflineOperation(
        current: CloudTaskEntity,
        action: String,
        operationId: String,
        path: String,
        body: String?,
    ) {
        dao.enqueueCloudRequest(
            CloudOutboxEntity(
                id = operationId,
                operationId = operationId,
                taskId = current.id,
                action = action,
                method = "POST",
                path = path,
                bodyJson = body.orEmpty(),
                expectedVersion = current.serverVersion,
                state = "pending",
                dedupeKey = operationId,
            ),
        )
        dao.updateCloudTaskSyncState(
            current.id,
            syncState = "pending",
            operationId = operationId,
            conflictMessage = null,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun enqueueConflict(
        current: CloudTaskEntity,
        action: String,
        operationId: String,
        path: String,
        body: String?,
        message: String,
    ) {
        dao.enqueueCloudRequest(
            CloudOutboxEntity(
                id = operationId,
                operationId = operationId,
                taskId = current.id,
                action = action,
                method = "POST",
                path = path,
                bodyJson = body.orEmpty(),
                expectedVersion = current.serverVersion,
                state = "conflict",
                dedupeKey = operationId,
                lastError = message,
            ),
        )
        dao.updateCloudTaskSyncState(
            current.id,
            syncState = "conflict",
            operationId = operationId,
            conflictMessage = message,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun replayOutbox() {
        dao.getPendingCloudOutbox().forEach { queued ->
            try {
                val config = requireConfig()
                val task: TaskDto = authorizedExecute(
                    config.baseUrl,
                    queued.path,
                    queued.method,
                    queued.bodyJson.ifBlank { null },
                    headers = mapOf("X-Client-Operation-Id" to queued.operationId),
                )
                dao.upsertCloudTasks(listOf(task.toEntity()))
                dao.deleteCloudOutbox(queued.id)
            } catch (error: ApiException) {
                if (error.status == HttpURLConnection.HTTP_CONFLICT) {
                    dao.markCloudOutboxConflict(queued.id, error.message.orEmpty())
                    queued.taskId?.let { taskId ->
                        dao.updateCloudTaskSyncState(
                            taskId,
                            syncState = "conflict",
                            operationId = queued.operationId,
                            conflictMessage = error.message,
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                    return
                }
                dao.markCloudOutboxFailed(queued.id, error.message ?: "同步失败")
                throw error
            } catch (error: IOException) {
                dao.markCloudOutboxFailed(queued.id, error.message ?: "网络不可用")
                return
            }
        }
    }

    private suspend fun requireConfig(): CloudConfigEntity =
        dao.getCloudConfig()?.takeIf {
            it.baseUrl.isNotBlank() &&
                (it.accessToken.isNotBlank() || it.refreshToken.isNotBlank()) &&
                it.authState != "reauthentication_required"
        } ?: error("登录已过期，请重新登录运营中台")

    private suspend inline fun <reified T> authorizedExecute(
        baseUrl: String,
        path: String,
        method: String,
        body: String?,
        headers: Map<String, String> = emptyMap(),
    ): T {
        var config = requireConfig()
        if (
            config.accessTokenExpiresAtEpochMs > 0 &&
            config.accessTokenExpiresAtEpochMs <= System.currentTimeMillis() + REFRESH_SKEW_MS
        ) {
            config = refresh(config)
        }
        return try {
            execute(
                baseUrl,
                path,
                method,
                body,
                tokenCipher.decrypt(config.accessToken),
                headers,
            )
        } catch (error: ApiException) {
            if (error.status != HttpURLConnection.HTTP_UNAUTHORIZED) throw error
            config = refresh(config)
            execute(
                baseUrl,
                path,
                method,
                body,
                tokenCipher.decrypt(config.accessToken),
                headers,
            )
        }
    }

    private suspend fun refresh(config: CloudConfigEntity): CloudConfigEntity {
        val refreshToken = tokenCipher.decrypt(config.refreshToken)
        if (
            refreshToken.isBlank() ||
            (config.refreshTokenExpiresAtEpochMs > 0 &&
                config.refreshTokenExpiresAtEpochMs <= System.currentTimeMillis())
        ) {
            markReauthenticationRequired(config, "刷新令牌已过期")
            error("登录已过期，请重新认证")
        }
        return try {
            val response: LoginResponse = execute(
                config.baseUrl,
                "/auth/refresh",
                "POST",
                json.encodeToString(RefreshTokenInput(refreshToken)),
                token = null,
            )
            saveAuthenticatedConfig(config.baseUrl, config.username, response)
        } catch (error: ApiException) {
            if (error.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                markReauthenticationRequired(config, "刷新令牌已撤销")
            }
            throw error
        }
    }

    private suspend fun saveAuthenticatedConfig(
        baseUrl: String,
        username: String,
        response: LoginResponse,
    ): CloudConfigEntity {
        val now = System.currentTimeMillis()
        val saved = CloudConfigEntity(
            baseUrl = baseUrl,
            username = username,
            accessToken = tokenCipher.encrypt(response.accessToken),
            refreshToken = tokenCipher.encrypt(response.refreshToken),
            accessTokenExpiresAtEpochMs = now + response.expiresInSeconds * 1_000L,
            refreshTokenExpiresAtEpochMs = now + response.refreshExpiresInSeconds * 1_000L,
            accountId = response.account.id,
            displayName = response.account.displayName,
            role = response.account.role,
            authState = "authenticated",
            lastSyncError = null,
            updatedAtEpochMs = now,
        )
        dao.saveCloudConfig(saved)
        return saved
    }

    private suspend fun markReauthenticationRequired(config: CloudConfigEntity, reason: String) {
        dao.saveCloudConfig(
            config.copy(
                accessToken = "",
                authState = "reauthentication_required",
                lastSyncError = reason,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

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
        headers: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw ApiException(responseCode, "服务器返回 $responseCode：$responseText")
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
        serverVersion = version,
        syncState = "synced",
        pendingOperationId = null,
        conflictMessage = null,
    )

    private companion object {
        const val REFRESH_SKEW_MS = 30_000L
    }
}

private class ApiException(
    val status: Int,
    message: String,
) : IOException(message)
