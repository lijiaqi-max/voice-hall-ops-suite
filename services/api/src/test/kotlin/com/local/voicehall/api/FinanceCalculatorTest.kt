package com.local.voicehall.api

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
}

