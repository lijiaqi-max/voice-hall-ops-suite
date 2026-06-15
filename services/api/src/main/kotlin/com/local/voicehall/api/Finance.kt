package com.local.voicehall.api

import java.math.BigInteger

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

    internal fun multiplyBps(cents: Long, bps: Int): Long =
        multiplyRatio(cents, bps.toLong(), 10_000L)

    internal fun prorate(centsPerUnit: Long, usedUnits: Long, unitsPerWhole: Long): Long =
        multiplyRatio(centsPerUnit, usedUnits, unitsPerWhole)

    private fun multiplyRatio(value: Long, numerator: Long, denominator: Long): Long {
        require(value >= 0 && numerator >= 0 && denominator > 0)
        val product = BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator))
        val divisor = BigInteger.valueOf(denominator)
        val parts = product.divideAndRemainder(divisor)
        val rounded = if (parts[1].shiftLeft(1) >= divisor) parts[0] + BigInteger.ONE else parts[0]
        return rounded.longValueExact()
    }
}
