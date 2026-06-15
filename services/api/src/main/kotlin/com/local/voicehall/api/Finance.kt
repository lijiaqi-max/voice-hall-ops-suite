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
        hostCostEstimated: Boolean = false,
        hostCostOverrideReason: String? = null,
        unallocatedRevenueCents: Long = 0,
    ): SettlementView {
        require(input.grossCents >= 0)
        require(input.hostCostCents >= 0)
        require(input.expenseCents >= 0)
        require(input.platformRateBps in 0..10_000)
        require(input.organizationShareBps in 0..10_000)
        require(input.memberCommissionBps in 0..10_000)
        require(unallocatedRevenueCents >= 0)
        val platform = multiplyBps(input.grossCents, input.platformRateBps)
        val afterPlatform = input.grossCents - platform
        val organizationShare = multiplyBps(afterPlatform, input.organizationShareBps)
        val memberCommission = multiplyBps(organizationShare, input.memberCommissionBps)
        val payable = exactSum(memberCommission, input.hostCostCents, input.expenseCents)
        val netProfit = exactSubtract(organizationShare, payable)
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
            netProfitCents = netProfit,
            hostCostEstimated = hostCostEstimated,
            hostCostOverrideReason = hostCostOverrideReason,
            unallocatedRevenueCents = unallocatedRevenueCents,
            reconciliationDifferenceCents = exactSubtract(organizationShare, payable, netProfit),
            state = "preview",
        )
    }

    internal fun multiplyBps(cents: Long, bps: Int): Long =
        multiplyRatio(cents, bps.toLong(), 10_000L)

    internal fun prorate(centsPerUnit: Long, usedUnits: Long, unitsPerWhole: Long): Long =
        multiplyRatio(centsPerUnit, usedUnits, unitsPerWhole)

    internal fun applyAdjustment(
        receivableCents: Long,
        payableCents: Long,
        netProfitCents: Long,
        amountCents: Long,
        effect: String,
    ): Triple<Long, Long, Long> = when (effect) {
        "receivable" -> Triple(
            exactSum(receivableCents, amountCents),
            payableCents,
            exactSum(netProfitCents, amountCents),
        )
        "payable", "expense" -> Triple(
            receivableCents,
            exactSum(payableCents, amountCents),
            exactSubtract(netProfitCents, amountCents),
        )
        "net" -> Triple(receivableCents, payableCents, exactSum(netProfitCents, amountCents))
        else -> error("Unsupported adjustment effect: $effect")
    }

    private fun multiplyRatio(value: Long, numerator: Long, denominator: Long): Long {
        require(value >= 0 && numerator >= 0 && denominator > 0)
        val product = BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator))
        val divisor = BigInteger.valueOf(denominator)
        val parts = product.divideAndRemainder(divisor)
        val rounded = if (parts[1].shiftLeft(1) >= divisor) parts[0] + BigInteger.ONE else parts[0]
        return rounded.longValueExact()
    }

    private fun exactSum(vararg values: Long): Long =
        values.fold(BigInteger.ZERO) { total, value -> total + BigInteger.valueOf(value) }.longValueExact()

    private fun exactSubtract(first: Long, vararg values: Long): Long =
        values.fold(BigInteger.valueOf(first)) { total, value -> total - BigInteger.valueOf(value) }.longValueExact()
}
