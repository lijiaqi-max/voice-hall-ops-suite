package com.local.interactionassistant.executor.automation

import android.accessibilityservice.AccessibilityService
import com.local.interactionassistant.executor.data.AppSettingsEntity
import com.local.interactionassistant.executor.data.PublishedAsset
import com.local.interactionassistant.executor.data.TaskAssetView
import com.local.interactionassistant.executor.data.TaskEntity

data class AdapterMatch(
    val supported: Boolean,
    val packageName: String?,
    val versionName: String?,
    val reason: String,
)

data class AutomationCandidate(
    val externalUserId: String,
    val displayName: String,
    val note: String = "",
    val gender: String? = null,
    val consumption: Long? = null,
    val interactionType: String = "manual_confirmed",
    val interactionSummary: String = "",
)

data class AutomationResult(
    val success: Boolean,
    val reason: String,
    val visibleRecipient: String? = null,
    val verificationSummary: String? = null,
)

data class VerificationResult(
    val ready: Boolean,
    val reason: String,
    val visibleRecipient: String? = null,
    val summary: String? = null,
)

data class ScanResult(
    val candidates: List<AutomationCandidate>,
    val reason: String,
)

data class AdapterCapabilities(
    val visibleScan: Boolean,
    val autoNavigation: Boolean,
    val autoNavigationVerified: Boolean,
    val imageSend: Boolean,
    val reason: String,
)

enum class AdapterCapability {
    VISIBLE_INTERACTION_CAPTURE,
    MANUAL_CHAT_VERIFICATION,
    AUTO_NAVIGATION,
    IMAGE_SEND,
}

data class CapturedInteraction(
    val externalUserId: String,
    val displayName: String,
    val type: String,
    val summary: String,
)

data class InteractionCaptureResult(
    val interactions: List<CapturedInteraction>,
    val reason: String,
)

data class NavigationResult(
    val ready: Boolean,
    val reason: String,
    val manualFallbackRequired: Boolean,
    val pageType: String,
)

data class ExecutionPayload(
    val task: TaskEntity,
    val assets: List<TaskAssetView>,
    val settings: AppSettingsEntity,
)

interface TargetAdapter {
    val id: String

    fun inspect(service: AccessibilityService): AdapterMatch

    fun capabilities(service: AccessibilityService): AdapterCapabilities

    fun pageType(service: AccessibilityService): String

    fun getCalibrationStatus(service: AccessibilityService): String

    suspend fun navigateToRecipient(
        service: AccessibilityService,
        externalUserId: String,
    ): NavigationResult

    suspend fun scanVisible(service: AccessibilityService): ScanResult

    suspend fun captureVisibleInteractions(
        service: AccessibilityService,
    ): InteractionCaptureResult

    suspend fun verifyConversation(
        service: AccessibilityService,
        payload: ExecutionPayload,
    ): VerificationResult

    suspend fun verify(
        service: AccessibilityService,
        payload: ExecutionPayload,
    ): VerificationResult = verifyConversation(service, payload)

    suspend fun execute(
        service: AccessibilityService,
        payload: ExecutionPayload,
        publishedAssets: List<PublishedAsset>,
    ): AutomationResult
}
