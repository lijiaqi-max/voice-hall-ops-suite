package com.local.voicehall.api

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinanceWorkbookTest {
    @Test
    fun `generates an xlsx package with four readable worksheets`() {
        val settlement = FinanceCalculator.calculate(
            FinanceInputs(
                grossCents = 100_000,
                platformRateBps = 5_000,
                organizationShareBps = 10_000,
                memberCommissionBps = 3_000,
                hostCostCents = 4_600,
                expenseCents = 2_000,
            ),
            roomId = "room-1",
            periodStart = 1,
            periodEnd = 2,
            ruleId = "rule-1",
        ).copy(id = "settlement-1", state = "closed")
        val report = FinancialReport(
            settlement = settlement,
            memberCommissions = listOf(
                SettlementLineView("m1", "member_commission", accountId = "a1", label = "成员甲", grossCents = 100_000, amountCents = 15_000, verified = true),
            ),
            hostCosts = listOf(
                SettlementLineView("h1", "host_cost", referenceId = "s1", label = "晚班", grossCents = 0, amountCents = 4_600, verified = false, note = "人工确认"),
            ),
            expenses = listOf(
                SettlementLineView("e1", "expense", referenceId = "e1", label = "场地", grossCents = 0, amountCents = 2_000, verified = true),
            ),
            adjustments = emptyList(),
        )

        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(FinanceWorkbook.generate(report))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }

        assertTrue("[Content_Types].xml" in entries)
        assertTrue("xl/workbook.xml" in entries)
        assertEquals(4, entries.keys.count { it.startsWith("xl/worksheets/sheet") })
        assertTrue(entries.getValue("xl/workbook.xml").contains("成员佣金"))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("净利润"))
        assertTrue(entries.getValue("xl/worksheets/sheet2.xml").contains("成员甲"))
    }
}
