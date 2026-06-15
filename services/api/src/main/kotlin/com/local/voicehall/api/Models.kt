package com.local.voicehall.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(val error: String, val detail: String? = null)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val otp: String? = null,
)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String,
    val refreshExpiresInSeconds: Long,
    val account: AccountView,
    val permissions: Set<String>,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

@Serializable
data class ChangePasswordResponse(
    val status: String = "password_changed",
    val reauthenticationRequired: Boolean = true,
)

@Serializable
data class AccountView(
    val id: String,
    val organizationId: String,
    val username: String,
    val displayName: String,
    val role: String,
)

@Serializable
data class AccountCreateInput(
    val username: String,
    val displayName: String,
    val password: String,
    val role: String,
    val roomIds: List<String> = emptyList(),
    val totpSecret: String? = null,
)

@Serializable
data class RoomInput(
    val name: String,
    val platform: String = "ingkee",
    val externalRoomId: String? = null,
)

@Serializable
data class RoomView(
    val id: String,
    val name: String,
    val platform: String,
    val externalRoomId: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class ShiftInput(
    val roomId: String,
    val hostAccountId: String? = null,
    val title: String,
    val startAtEpochMs: Long,
    val endAtEpochMs: Long,
    val hostFixedCents: Long = 0,
    val hostHourlyCents: Long = 0,
)

@Serializable
data class ShiftView(
    val id: String,
    val roomId: String,
    val hostAccountId: String? = null,
    val title: String,
    val startAtEpochMs: Long,
    val endAtEpochMs: Long,
    val status: String,
    val hostFixedCents: Long,
    val hostHourlyCents: Long,
    val checkedInAtEpochMs: Long? = null,
)

@Serializable
data class CustomerInput(
    val displayName: String,
    val platform: String,
    val externalUserId: String,
    val platformDisplayName: String = displayName,
    val relationshipStage: String = "new_interaction",
    val contactEligibility: String = "manual_confirmed",
)

@Serializable
data class CustomerView(
    val id: String,
    val displayName: String,
    val platform: String,
    val externalUserId: String,
    val relationshipStage: String,
    val contactEligibility: String,
    val lastInteractionAtEpochMs: Long? = null,
    val revenue7dCents: Long = 0,
    val revenue30dCents: Long = 0,
    val revenue90dCents: Long = 0,
    val lifetimeRevenueCents: Long = 0,
    val valueLevel: String = "standard",
)

@Serializable
data class TaskInput(
    val roomId: String? = null,
    val customerId: String,
    val title: String,
    val brief: String,
    val priority: Int = 0,
    val assignedAccountId: String? = null,
    val publish: Boolean = true,
)

@Serializable
data class TaskResultInput(
    val channel: String,
    val note: String,
    val nextFollowUpAtEpochMs: Long? = null,
    val expectedVersion: Int? = null,
    val clientOperationId: String? = null,
)

@Serializable
data class TaskView(
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
data class RevenueRowInput(
    val roomId: String,
    val platform: String = "ingkee",
    val transactionId: String? = null,
    val customerExternalId: String? = null,
    val customerDisplayName: String? = null,
    val giftCategory: String? = null,
    val grossCents: Long,
    val occurredAtEpochMs: Long,
)

@Serializable
data class RevenuePreviewRequest(
    val fileName: String,
    val fileSha256: String,
    val expectedTotalCents: Long,
    val rows: List<RevenueRowInput>,
)

@Serializable
data class RevenuePreviewResponse(
    val importId: String,
    val rowCount: Int,
    val validRowCount: Int,
    val duplicateCount: Int,
    val expectedTotalCents: Long,
    val calculatedTotalCents: Long,
    val canCommit: Boolean,
    val errors: List<String>,
)

@Serializable
data class SettlementPreviewRequest(
    val roomId: String? = null,
    val periodStartEpochMs: Long,
    val periodEndEpochMs: Long,
    val ruleId: String? = null,
)

@Serializable
data class SettlementRuleInput(
    val name: String,
    val effectiveFromEpochMs: Long,
    val platformRateBps: Int,
    val organizationShareBps: Int,
    val memberCommissionBps: Int,
)

@Serializable
data class SettlementView(
    val id: String? = null,
    val roomId: String? = null,
    val periodStartEpochMs: Long,
    val periodEndEpochMs: Long,
    val ruleId: String,
    val grossCents: Long,
    val platformDeductionCents: Long,
    val organizationShareCents: Long,
    val memberCommissionCents: Long,
    val hostCostCents: Long,
    val expenseCents: Long,
    val accountsReceivableCents: Long,
    val accountsPayableCents: Long,
    val netProfitCents: Long,
    val state: String,
)

@Serializable
data class AdjustmentInput(val amountCents: Long, val reason: String)

@Serializable
data class ExpenseInput(
    val roomId: String? = null,
    val category: String,
    val amountCents: Long,
    val note: String,
    val occurredAtEpochMs: Long,
)

@Serializable
data class ValueLevelRuleInput(
    val code: String,
    val label: String,
    val minimum30dCents: Long,
    val minimumLifetimeCents: Long,
    val sortOrder: Int,
)

@Serializable
data class ReportSummary(
    val periodStartEpochMs: Long,
    val periodEndEpochMs: Long,
    val grossCents: Long,
    val taskTotal: Int,
    val taskApproved: Int,
    val customerTotal: Int,
    val roomTotals: List<RoomRevenue>,
    val dailyTotals: List<DailyRevenue>,
)

@Serializable
data class RoomRevenue(val roomId: String, val roomName: String, val grossCents: Long)

@Serializable
data class DailyRevenue(val date: String, val grossCents: Long)

@Serializable
data class DeviceRegistrationInput(val roomId: String, val name: String)

@Serializable
data class DeviceRegistrationResponse(val deviceId: String, val deviceSecret: String)

@Serializable
data class DeviceView(
    val id: String,
    val roomId: String,
    val name: String,
    val enabled: Boolean,
    val lastSeenAtEpochMs: Long? = null,
)

@Serializable
data class DeviceEventInput(
    val eventId: String,
    val type: String,
    val roomId: String,
    val occurredAtEpochMs: Long,
    val payload: String,
)

@Serializable
data class DeviceWsEnvelope(
    val timestamp: String,
    val signature: String,
    val event: DeviceEventInput,
)

@Serializable
data class DeviceAck(val eventId: String, val accepted: Boolean)

@Serializable
data class AuditView(
    val id: String,
    val accountId: String? = null,
    val action: String,
    val resourceType: String,
    val resourceId: String? = null,
    val summary: String,
    val createdAtEpochMs: Long,
)

@Serializable
data class LegacyMigrationInput(
    val source: String,
    val sourceVersion: String,
    val recordCount: Int,
    val payloadJson: String,
)

data class AuthAccount(
    val id: String,
    val organizationId: String,
    val username: String,
    val displayName: String,
    val role: String,
    val passwordHash: String,
    val enabled: Boolean,
    val totpSecret: String?,
    val tokenVersion: Int,
)

data class RefreshGrant(
    val account: AuthAccount,
    val refreshToken: String,
    val refreshExpiresInSeconds: Long,
)
