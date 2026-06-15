package com.local.voicehall.api

import kotlin.math.roundToLong

data class FinanceInputs(
    val grossCents: Long,
    val platformRateBps: Int,
    val organizationShareBps: Int,
    val memberCommissionBps: Int,
    val hostCostCents: Long,
    val expenseCents: Long,
)

object FinanceCalculator {
    fun calculate(
        input: FinanceInputs,
        roomId: String?,
        periodStart: Long,
        periodEnd: Long,
        ruleId: String,
    ): SettlementView {
        require(input.grossCents >= 0)
        require(input.hostCostCents >= 0)
        require(input.expenseCents >= 0)
        require(input.platformRateBps in 0..10_000)
        require(input.organizationShareBps in 0..10_000)
        require(input.memberCommissionBps in 0..10_000)
        val platform = multiplyBps(input.grossCents, input.platformRateBps)
        val afterPlatform = input.grossCents - platform
        val organizationShare = multiplyBps(afterPlatform, input.organizationShareBps)
        val memberCommission = multiplyBps(organizationShare, input.memberCommissionBps)
        val payable = memberCommission + input.hostCostCents + input.expenseCents
        return SettlementView(
            roomId = roomId,
            periodStartEpochMs = periodStart,
            periodEndEpochMs = periodEnd,
            ruleId = ruleId,
            grossCents = input.grossCents,
            platformDeductionCents = platform,
            organizationShareCents = organizationShare,
            memberCommissionCents = memberCommission,
            hostCostCents = input.hostCostCents,
            expenseCents = input.expenseCents,
            accountsReceivableCents = organizationShare,
            accountsPayableCents = payable,
            netProfitCents = organizationShare - payable,
            state = "preview",
        )
    }

    private fun multiplyBps(cents: Long, bps: Int): Long =
        (cents.toDouble() * bps.toDouble() / 10_000.0).roundToLong()
}

