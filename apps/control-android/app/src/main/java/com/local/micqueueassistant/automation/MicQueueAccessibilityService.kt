package com.local.micqueueassistant.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.local.micqueueassistant.MicQueueApp
import com.local.micqueueassistant.domain.DeviceRole
import com.local.micqueueassistant.transport.PairingTransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MicQueueAccessibilityService : AccessibilityService() {
    private var scope: CoroutineScope? = null
    private var eventJob: Job? = null
    private val repository get() = MicQueueApp.instance.repository
    private val wechatAdapter: WechatGroupAdapter = WechatGroupAdapterImpl()
    private val ingkeeAdapter: IngkeeVoiceRoomAdapter = IngkeeVoiceRoom9860Adapter()

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutomationCoordinator.setConnected(true)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (event.eventType !in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            )
        ) return
        eventJob?.cancel()
        eventJob = scope?.launch {
            delay(450)
            val role = repository.config.value.role
            when (role) {
                DeviceRole.ROBOT.value -> handleRobot(packageName)
                DeviceRole.COLLECTOR.value -> handleCollector(packageName)
            }
        }
    }

    private suspend fun handleRobot(packageName: String) {
        if (packageName !in setOf(WechatGroupAdapterImpl.OFFICIAL_PACKAGE, WechatGroupAdapterImpl.MOCK_PACKAGE)) return
        val config = repository.config.value
        val officialEnabled = config.wechatServerCapabilityEnabled &&
            config.wechatOfficialReplyEnabled &&
            config.robotAutoReplyEnabled &&
            config.wechatCalibrationStatus == "已校准"
        val status = wechatAdapter.inspect(this, officialEnabled)
        AutomationCoordinator.updateStatus(status.reason, status.pageType)
        if (!status.supported) return
        wechatAdapter.captureIncomingMessages(this).forEach { message ->
            val response = repository.processIncomingMessage(message).getOrElse {
                it.message ?: "指令处理失败"
            } ?: return@forEach
            if (!status.calibrated) {
                repository.nextPendingReply()?.let { pending ->
                    repository.updateReplyState(pending.id, "failed", failureReason = "正式微信能力未启用")
                }
                AutomationCoordinator.updateLastReply("待发送：$response")
                return@forEach
            }
            val reply = repository.nextPendingReply() ?: return@forEach
            repository.updateReplyState(reply.id, "sending", incrementAttempt = true)
            wechatAdapter.sendReply(this, response, officialEnabled)
                .onSuccess {
                    repository.updateReplyState(reply.id, "sent")
                    AutomationCoordinator.updateLastReply(response)
                    delay(1_000)
                }
                .onFailure {
                    repository.updateReplyState(reply.id, "failed", failureReason = it.message)
                    AutomationCoordinator.updateStatus(it.message ?: "群回复失败", status.pageType)
                }
        }
    }

    private suspend fun handleCollector(packageName: String) {
        if (packageName !in setOf(IngkeeVoiceRoom9860Adapter.OFFICIAL_PACKAGE, IngkeeVoiceRoom9860Adapter.MOCK_PACKAGE)) return
        val config = repository.config.value
        val officialEnabled = config.ingkeeServerCapabilityEnabled &&
            config.ingkeeOfficialCaptureEnabled &&
            config.ingkeeCalibrationStatus == "已校准"
        val status = ingkeeAdapter.inspect(this, officialEnabled)
        AutomationCoordinator.updateStatus(status.reason, status.pageType)
        if (!status.calibrated) {
            AutomationCoordinator.updateSeats(emptyList())
            return
        }
        val payload = ingkeeAdapter.captureSeatSnapshot(this, officialEnabled)
        AutomationCoordinator.updateSeats(
            payload.seats.sortedBy { it.seatIndex }.map { "${it.seatIndex}. ${it.ingkeeName}" },
        )
        repository.applySeatSnapshot(payload)
        PairingTransportManager.sendCollectorEvent(payload)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AutomationCoordinator.setConnected(false)
        eventJob?.cancel()
        scope?.cancel()
        scope = null
        super.onDestroy()
    }
}
