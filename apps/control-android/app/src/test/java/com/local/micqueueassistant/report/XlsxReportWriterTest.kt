package com.local.micqueueassistant.report

import com.local.micqueueassistant.data.MicSegmentEntity
import com.local.micqueueassistant.data.ShiftEntity
import com.local.micqueueassistant.domain.QueueRole
import com.local.micqueueassistant.domain.SegmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import java.util.zip.ZipInputStream

class XlsxReportWriterTest {
    @Test
    fun createsFourSheetXlsxWithChineseAndFormula() {
        val zone = ZoneId.of("Asia/Shanghai")
        val period = XlsxReportWriter.resolvePeriod("day", "2026-06-13", zone)
        val shift = ShiftEntity(
            id = "s1",
            label = "2026-06-13 16:00-17:00",
            startAtEpochMs = period.startAtEpochMs + 16 * 3_600_000,
            endAtEpochMs = period.startAtEpochMs + 17 * 3_600_000,
            capacity = 8,
            cutoffAtEpochMs = period.startAtEpochMs + 16 * 3_600_000 + 600_000,
            state = "completed",
            createdBy = "琳惠",
        )
        val segment = MicSegmentEntity(
            id = "m1",
            shiftId = shift.id,
            bindingId = "b1",
            wechatName = "夏天",
            ingkeeName = "映客小夏",
            role = QueueRole.PARTICIPANT.value,
            queuePosition = 1,
            startedAtEpochMs = shift.startAtEpochMs,
            lastSeenAtEpochMs = shift.startAtEpochMs + 600_000,
            endedAtEpochMs = shift.startAtEpochMs + 600_000,
            durationSeconds = 600,
            state = SegmentState.CLOSED.value,
        )
        val bytes = ByteArrayOutputStream().also {
            XlsxReportWriter.write(
                output = it,
                period = period,
                segments = listOf(segment),
                shifts = listOf(shift),
                zoneId = zone,
            )
        }.toByteArray()
        System.getenv("MIC_QUEUE_SAMPLE_XLSX")?.takeIf(String::isNotBlank)?.let { path ->
            File(path).apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
        }

        assertTrue(bytes.size > 1_000)
        val entries = unzip(bytes)
        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/styles.xml",
                "xl/worksheets/sheet1.xml",
                "xl/worksheets/sheet2.xml",
                "xl/worksheets/sheet3.xml",
                "xl/worksheets/sheet4.xml",
            ),
            entries.keys,
        )
        assertTrue(entries.getValue("xl/workbook.xml").contains("麦时明细"))
        assertTrue(entries.getValue("xl/workbook.xml").contains("异常记录"))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("映客小夏"))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("<f>H2/60</f>"))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("<v>10</v>"))
        assertTrue(entries.getValue("xl/workbook.xml").contains("fullCalcOnLoad=\"1\""))
    }

    @Test
    fun resolvesWeeklyAndMonthlyPeriods() {
        val zone = ZoneId.of("Asia/Shanghai")
        val week = XlsxReportWriter.resolvePeriod("week", "2026-06-08", zone)
        val month = XlsxReportWriter.resolvePeriod("month", "2026-06", zone)
        assertEquals(7 * 86_400_000L, week.endAtEpochMs - week.startAtEpochMs)
        assertEquals(30 * 86_400_000L, month.endAtEpochMs - month.startAtEpochMs)
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return result
    }
}
