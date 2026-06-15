package com.local.micqueueassistant.transport

import android.content.Context
import com.local.micqueueassistant.MicQueueApp
import com.local.micqueueassistant.domain.SeatSnapshotPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
private data class DeviceRegistrationInput(val roomId: String, val name: String)

@Serializable
private data class DeviceRegistrationResponse(val deviceId: String, val deviceSecret: String)

@Serializable
private data class CloudDeviceEvent(
    val eventId: String,
    val type: String,
    val roomId: String,
    val occurredAtEpochMs: Long,
    val payload: String,
)

@Serializable
private data class DeviceAck(val eventId: String, val accepted: Boolean)

object PairingTransportManager {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow("stopped")
    val state = _state.asStateFlow()
    private val _detail = MutableStateFlow("私有云同步未启动")
    val detail = _detail.asStateFlow()
    private val _observedFingerprint = MutableStateFlow("")
    val observedFingerprint = _observedFingerprint.asStateFlow()
    private val _serverFingerprint = MutableStateFlow("")
    val serverFingerprint = _serverFingerprint.asStateFlow()
    private var syncJob: Job? = null

    fun start(context: Context) {
        syncJob?.cancel()
        syncJob = MicQueueApp.applicationScope.launch {
            val config = MicQueueApp.instance.repository.config.value
            val secret = CloudSecretStore.load(context.applicationContext)
            if (!config.cloudSyncEnabled || config.cloudDeviceId.isBlank() || secret.isNullOrBlank()) {
                _state.value = "error"
                _detail.value = "请先在设置中注册厅控设备"
                return@launch
            }
            _state.value = "connecting"
            while (true) {
                runCatching { flushPending(context.applicationContext) }
                    .onSuccess {
                        _state.value = "connected"
                        _detail.value = "已通过 HTTPS 同步到私有云"
                    }
                    .onFailure {
                        _state.value = "disconnected"
                        _detail.value = "同步中断，事件保留在本地：${it.message}"
                    }
                delay(15_000)
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
        _state.value = "stopped"
        _detail.value = "私有云同步已停止"
        MicQueueApp.applicationScope.launch {
            MicQueueApp.instance.repository.setForegroundServiceEnabled(false)
        }
    }

    suspend fun enroll(
        context: Context,
        baseUrl: String,
        roomId: String,
        adminAccessToken: String,
        deviceName: String,
    ): Result<String> = runCatching {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://")) { "正式环境必须使用 HTTPS 地址" }
        require(roomId.isNotBlank()) { "厅房 ID 不能为空" }
        require(adminAccessToken.isNotBlank()) { "设备注册令牌不能为空" }
        val responseText = http(
            url = "$normalized/devices/register",
            method = "POST",
            body = json.encodeToString(DeviceRegistrationInput(roomId.trim(), deviceName.trim())),
            headers = mapOf("Authorization" to "Bearer ${adminAccessToken.trim()}"),
        )
        val registration = json.decodeFromString<DeviceRegistrationResponse>(responseText)
        CloudSecretStore.save(context.applicationContext, registration.deviceSecret)
        MicQueueApp.instance.repository.saveCloudRegistration(
            normalized,
            roomId.trim(),
            registration.deviceId,
        ).getOrThrow()
        _state.value = "registered"
        _detail.value = "设备注册完成，请启动同步服务"
        "厅控设备注册成功"
    }

    suspend fun disconnect(context: Context): Result<String> = runCatching {
        stop()
        CloudSecretStore.clear(context.applicationContext)
        MicQueueApp.instance.repository.disableCloudSync()
        "已解除本机云端设备凭据"
    }

    suspend fun sendCollectorEvent(payload: SeatSnapshotPayload) {
        val repository = MicQueueApp.instance.repository
        repository.enqueueCollectorEvent(payload.eventId, json.encodeToString(payload))
        runCatching { flushPending(MicQueueApp.instance.applicationContext) }
    }

    fun confirmObservedFingerprint() {
        _detail.value = "HTTPS 证书由系统信任链校验，不使用旧局域网指纹配对"
    }

    private suspend fun flushPending(context: Context) {
        val repository = MicQueueApp.instance.repository
        val config = repository.config.value
        val secret = CloudSecretStore.load(context) ?: error("设备密钥不存在")
        require(config.cloudBaseUrl.startsWith("https://")) { "私有云地址不是 HTTPS" }
        require(config.cloudRoomId.isNotBlank() && config.cloudDeviceId.isNotBlank()) {
            "设备尚未注册"
        }
        repository.pendingCollectorEvents()
            .sortedWith(compareBy({ eventPriority(it.type) }, { it.createdAtEpochMs }, { it.id }))
            .forEach { row ->
            val event = CloudDeviceEvent(
                eventId = row.eventId,
                type = row.type,
                roomId = config.cloudRoomId,
                occurredAtEpochMs = row.createdAtEpochMs,
                payload = row.payloadJson,
            )
            val body = json.encodeToString(event)
            val timestamp = System.currentTimeMillis().toString()
            val signature = hmac(secret, "$timestamp\n$body")
            val response = http(
                url = "${config.cloudBaseUrl}/devices/events",
                method = "POST",
                body = body,
                headers = mapOf(
                    "X-Device-Id" to config.cloudDeviceId,
                    "X-Timestamp" to timestamp,
                    "X-Signature" to signature,
                ),
            )
            val ack = json.decodeFromString<DeviceAck>(response)
            check(ack.accepted) { "Server retained the event but projection failed; retry is required" }
            check(ack.eventId == row.eventId) { "服务器确认事件不匹配" }
            repository.markCollectorEventSent(row.id)
        }
        repository.markCloudSync()
        repository.setForegroundServiceEnabled(true)
    }

    private fun eventPriority(type: String): Int = when (type) {
        "shift_upsert" -> 0
        "binding_upsert" -> 1
        "queue_entry_upsert" -> 2
        "seat_snapshot" -> 3
        else -> 9
    }

    private fun hmac(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun http(
        url: String,
        method: String,
        body: String,
        headers: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach(connection::setRequestProperty)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(connection.responseCode in 200..299) {
                "服务器返回 ${connection.responseCode}：$response"
            }
            response
        } finally {
            connection.disconnect()
        }
    }
}
