package com.local.voicehall.api

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class FinanceCalculatorTest {
    @Test
    fun calculatesOperatingStatementUsingIntegerCents() {
        val result = FinanceCalculator.calculate(
            FinanceInputs(
                grossCents = 1_000_00,
                platformRateBps = 5_000,
                organizationShareBps = 8_000,
                memberCommissionBps = 3_000,
                hostCostCents = 5_000,
                expenseCents = 2_000,
            ),
            roomId = "room-1",
            periodStart = 1,
            periodEnd = 2,
            ruleId = "rule-1",
        )
        assertEquals(50_000, result.platformDeductionCents)
        assertEquals(40_000, result.organizationShareCents)
        assertEquals(12_000, result.memberCommissionCents)
        assertEquals(19_000, result.accountsPayableCents)
        assertEquals(21_000, result.netProfitCents)
    }

    @Test
    fun roundsBasisPointsWithoutFloatingPoint() {
        val random = Random(42)
        repeat(2_000) {
            val cents = random.nextLong(0, 10_000_000_000L)
            val bps = random.nextInt(0, 10_001)
            val product = BigInteger.valueOf(cents).multiply(BigInteger.valueOf(bps.toLong()))
            val divisor = BigInteger.valueOf(10_000)
            val parts = product.divideAndRemainder(divisor)
            val expected = if (parts[1].shiftLeft(1) >= divisor) {
                parts[0] + BigInteger.ONE
            } else {
                parts[0]
            }.longValueExact()
            assertEquals(expected, FinanceCalculator.multiplyBps(cents, bps))
        }
    }

    @Test
    fun proratesHostCostUsingIntegerDuration() {
        assertEquals(3_333, FinanceCalculator.prorate(10_000, 1_200_000, 3_600_000))
        assertEquals(5_000, FinanceCalculator.prorate(10_000, 1_800_000, 3_600_000))
    }

    @Test
    fun appliesAdjustmentsWithoutBreakingReconciliation() {
        val receivable = FinanceCalculator.applyAdjustment(50_000, 20_000, 30_000, 500, "receivable")
        assertEquals(Triple(50_500L, 20_000L, 30_500L), receivable)
        assertEquals(0, receivable.first - receivable.second - receivable.third)

        val payable = FinanceCalculator.applyAdjustment(50_000, 20_000, 30_000, 500, "payable")
        assertEquals(Triple(50_000L, 20_500L, 29_500L), payable)
        assertEquals(0, payable.first - payable.second - payable.third)
    }
}
