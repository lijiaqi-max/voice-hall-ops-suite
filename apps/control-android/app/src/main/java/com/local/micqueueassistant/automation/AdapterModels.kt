package com.local.micqueueassistant.automation

import android.accessibilityservice.AccessibilityService
import com.local.micqueueassistant.domain.IncomingGroupMessage
import com.local.micqueueassistant.domain.SeatSnapshotPayload

data class AdapterStatus(
    val supported: Boolean,
    val packageName: String?,
    val versionName: String?,
    val pageType: String,
    val calibrated: Boolean,
    val reason: String,
)

interface WechatGroupAdapter {
    fun inspect(service: AccessibilityService): AdapterStatus
    suspend fun captureIncomingMessages(service: AccessibilityService): List<IncomingGroupMessage>
    suspend fun sendReply(service: AccessibilityService, message: String): Result<Unit>
}

interface IngkeeVoiceRoomAdapter {
    fun inspect(service: AccessibilityService): AdapterStatus
    suspend fun captureSeatSnapshot(service: AccessibilityService): SeatSnapshotPayload
}
