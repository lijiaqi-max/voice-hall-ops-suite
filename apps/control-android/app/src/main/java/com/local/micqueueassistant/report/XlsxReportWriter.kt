package com.local.micqueueassistant.report

import com.local.micqueueassistant.data.AuditLogEntity
import com.local.micqueueassistant.data.MicSegmentEntity
import com.local.micqueueassistant.data.QueueEntryEntity
import com.local.micqueueassistant.data.ShiftEntity
import com.local.micqueueassistant.domain.QueueRole
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ReportPeriod(
    val type: String,
    val key: String,
    val startAtEpochMs: Long,
    val endAtEpochMs: Long,
)

object XlsxReportWriter {
    fun resolvePeriod(
        type: String,
        key: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ReportPeriod {
        val startDate = when (type) {
            "day", "week" -> LocalDate.parse(key)
            "month" -> YearMonth.parse(key).atDay(1)
            else -> error("未知报表周期")
        }
        val endDate = when (type) {
            "day" -> startDate.plusDays(1)
            "week" -> startDate.plusDays(7)
            "month" -> startDate.plusMonths(1)
            else -> error("未知报表周期")
        }
        return ReportPeriod(
            type,
            key,
            startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        )
    }

    fun write(
        output: OutputStream,
        period: ReportPeriod,
        segments: List<MicSegmentEntity>,
        shifts: List<ShiftEntity>,
        queueEntries: List<QueueEntryEntity> = emptyList(),
        auditLogs: List<AuditLogEntity> = emptyList(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) {
        val rows = segments.filter {
            it.startedAtEpochMs < period.endAtEpochMs &&
                (it.endedAtEpochMs ?: it.lastSeenAtEpochMs) >= period.startAtEpochMs
        }
        val sheets = listOf(
            "麦时明细" to detailRows(rows, shifts, zoneId),
            "日汇总" to dailyRows(rows, shifts, queueEntries, zoneId),
            "月汇总" to monthlyRows(rows, zoneId),
            "异常记录" to anomalyRows(rows, auditLogs, period, zoneId),
        )
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putText("[Content_Types].xml", contentTypes())
            zip.putText("_rels/.rels", rootRelationships())
            zip.putText("xl/workbook.xml", workbook(sheets.map { it.first }))
            zip.putText("xl/_rels/workbook.xml.rels", workbookRelationships(sheets.size))
            zip.putText("xl/styles.xml", styles())
            sheets.forEachIndexed { index, (_, data) ->
                zip.putText("xl/worksheets/sheet${index + 1}.xml", worksheet(data))
            }
        }
    }

    private fun detailRows(
        segments: List<MicSegmentEntity>,
        shifts: List<ShiftEntity>,
        zoneId: ZoneId,
    ): List<List<Cell>> {
        val shiftMap = shifts.associateBy(ShiftEntity::id)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val body = segments.sortedBy(MicSegmentEntity::startedAtEpochMs).mapIndexed { index, row ->
            listOf(
                TextCell(row.shiftId?.let { shiftMap[it]?.label }.orEmpty()),
                TextCell(row.wechatName ?: "未绑定"),
                TextCell(row.ingkeeName),
                TextCell(if (row.role == QueueRole.HOST.value) "主持" else "普通"),
                NumberCell(row.queuePosition?.toDouble() ?: 0.0),
                TextCell(Instant.ofEpochMilli(row.startedAtEpochMs).atZone(zoneId).format(formatter)),
                TextCell(
                    row.endedAtEpochMs?.let {
                        Instant.ofEpochMilli(it).atZone(zoneId).format(formatter)
                    }.orEmpty(),
                ),
                NumberCell(row.durationSeconds.toDouble()),
                FormulaCell("H${index + 2}/60", row.durationSeconds / 60.0),
                TextCell(row.state),
            )
        }
        return listOf(
            listOf("班次", "成员", "映客昵称", "角色", "排麦序号", "上麦时间", "下麦时间", "时长(秒)", "时长(分钟)", "状态")
                .map(::HeaderCell),
        ) + body
    }

    private fun dailyRows(
        segments: List<MicSegmentEntity>,
        shifts: List<ShiftEntity>,
        queueEntries: List<QueueEntryEntity>,
        zoneId: ZoneId,
    ): List<List<Cell>> {
        val segmentGroups = segments.groupBy {
            Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zoneId).toLocalDate() to
                (it.wechatName ?: "未绑定:${it.ingkeeName}")
        }
        val shiftMap = shifts.associateBy(ShiftEntity::id)
        val queueCounts = queueEntries
            .filter { it.state != "cancelled" }
            .mapNotNull { row ->
                val shift = shiftMap[row.shiftId] ?: return@mapNotNull null
                val date = Instant.ofEpochMilli(shift.startAtEpochMs).atZone(zoneId).toLocalDate()
                (date to row.wechatName) to row
            }
            .groupingBy { it.first }
            .eachCount()
        val keys = (segmentGroups.keys + queueCounts.keys).distinct().sortedWith(
            compareBy<Pair<LocalDate, String>>({ it.first }, { it.second }),
        )
        return listOf(
            listOf("日期", "成员", "排麦次数", "上麦次数", "普通麦时(秒)", "主持麦时(秒)", "异常时长(秒)")
                .map(::HeaderCell),
        ) + keys.map { key ->
            val rows = segmentGroups[key].orEmpty()
            listOf(
                TextCell(key.first.toString()),
                TextCell(key.second),
                NumberCell((queueCounts[key] ?: 0).toDouble()),
                NumberCell(rows.size.toDouble()),
                NumberCell(rows.filter { it.role != QueueRole.HOST.value }.sumOf { it.durationSeconds }.toDouble()),
                NumberCell(rows.filter { it.role == QueueRole.HOST.value }.sumOf { it.durationSeconds }.toDouble()),
                NumberCell(rows.filter { it.state == "uncertain" }.sumOf { it.durationSeconds }.toDouble()),
            )
        }
    }

    private fun monthlyRows(
        segments: List<MicSegmentEntity>,
        zoneId: ZoneId,
    ): List<List<Cell>> {
        val ranked = segments.groupBy { it.wechatName ?: "未绑定:${it.ingkeeName}" }
            .map { (name, rows) ->
                val ordinary = rows.filter { it.role != QueueRole.HOST.value }.sumOf { it.durationSeconds }
                val host = rows.filter { it.role == QueueRole.HOST.value }.sumOf { it.durationSeconds }
                MonthlySummary(
                    name,
                    rows.map {
                        Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zoneId).toLocalDate()
                    }.distinct().size,
                    rows.mapNotNull(MicSegmentEntity::shiftId).distinct().size,
                    ordinary,
                    host,
                    ordinary + host,
                )
            }.sortedByDescending(MonthlySummary::totalSeconds)
        return listOf(
            listOf("排名", "成员", "出勤天数", "班次数", "普通麦时(秒)", "主持麦时(秒)", "累计麦时(秒)")
                .map(::HeaderCell),
        ) + ranked.mapIndexed { index, row ->
            listOf(
                NumberCell((index + 1).toDouble()),
                TextCell(row.name),
                NumberCell(row.attendanceDays.toDouble()),
                NumberCell(row.shiftCount.toDouble()),
                NumberCell(row.ordinarySeconds.toDouble()),
                NumberCell(row.hostSeconds.toDouble()),
                NumberCell(row.totalSeconds.toDouble()),
            )
        }
    }

    private fun anomalyRows(
        segments: List<MicSegmentEntity>,
        auditLogs: List<AuditLogEntity>,
        period: ReportPeriod,
        zoneId: ZoneId,
    ): List<List<Cell>> {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val segmentRows = segments.filter {
            it.bindingId == null || it.state in setOf("uncertain", "corrected")
        }.map { row ->
            listOf(
                TextCell(Instant.ofEpochMilli(row.startedAtEpochMs).atZone(zoneId).format(formatter)),
                TextCell(row.ingkeeName),
                TextCell(row.wechatName.orEmpty()),
                TextCell(if (row.bindingId == null) "未绑定昵称" else row.state),
                NumberCell(row.durationSeconds.toDouble()),
                TextCell(row.correctionReason.orEmpty()),
            )
        }
        val logRows = auditLogs.filter {
            it.createdAtEpochMs in period.startAtEpochMs until period.endAtEpochMs &&
                (it.level == "warning" || it.type.contains("interrupted") || it.type.contains("error"))
        }.map { row ->
            listOf(
                TextCell(Instant.ofEpochMilli(row.createdAtEpochMs).atZone(zoneId).format(formatter)),
                TextCell(""),
                TextCell(""),
                TextCell(row.type),
                NumberCell(0.0),
                TextCell(row.message),
            )
        }
        return listOf(
            listOf("时间", "映客昵称", "成员", "异常类型", "时长(秒)", "说明").map(::HeaderCell),
        ) + segmentRows + logRows
    }

    private fun worksheet(rows: List<List<Cell>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        val widths = listOf(30, 18, 18, 12, 14, 22, 22, 14, 14, 16)
        val columnCount = rows.maxOfOrNull { it.size } ?: 1
        append("<cols>")
        repeat(columnCount) { index ->
            val width = widths.getOrElse(index) { 16 }
            append("""<col min="${index + 1}" max="${index + 1}" width="$width" customWidth="1"/>""")
        }
        append("</cols>")
        append("<sheetData>")
        rows.forEachIndexed { rowIndex, row ->
            append("""<row r="${rowIndex + 1}"${if (rowIndex == 0) """ ht="22" customHeight="1"""" else ""}>""")
            row.forEachIndexed { columnIndex, cell ->
                append(cell.xml("${columnName(columnIndex + 1)}${rowIndex + 1}"))
            }
            append("</row>")
        }
        append("</sheetData>")
        append("""<autoFilter ref="A1:${columnName(rows.maxOfOrNull { it.size } ?: 1)}1"/>""")
        append("</worksheet>")
    }

    private fun contentTypes(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
          <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/worksheets/sheet4.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        </Types>""".trimIndent()

    private fun rootRelationships(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>""".trimIndent()

    private fun workbook(names: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        names.forEachIndexed { index, name ->
            append("""<sheet name="${name.xmlEscape()}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
        }
        append("""</sheets><calcPr calcMode="auto" fullCalcOnLoad="1" forceFullCalc="1"/></workbook>""")
    }

    private fun workbookRelationships(count: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        repeat(count) { index ->
            append("""<Relationship Id="rId${index + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${index + 1}.xml"/>""")
        }
        append("""<Relationship Id="rId${count + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun styles(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <fonts count="2"><font><sz val="11"/><name val="Microsoft YaHei"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Microsoft YaHei"/></font></fonts>
          <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF175CD3"/><bgColor indexed="64"/></patternFill></fill></fills>
          <borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFD0D5DD"/></left><right style="thin"><color rgb="FFD0D5DD"/></right><top style="thin"><color rgb="FFD0D5DD"/></top><bottom style="thin"><color rgb="FFD0D5DD"/></bottom><diagonal/></border></borders>
          <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
          <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/></cellXfs>
        </styleSheet>""".trimIndent()

    private fun columnName(number: Int): String {
        var value = number
        val result = StringBuilder()
        while (value > 0) {
            value -= 1
            result.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return result.reverse().toString()
    }

    private fun ZipOutputStream.putText(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun String.xmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    private sealed interface Cell {
        fun xml(reference: String): String
    }

    private data class TextCell(val value: String) : Cell {
        override fun xml(reference: String): String =
            """<c r="$reference" t="inlineStr"><is><t xml:space="preserve">${value.xmlEscape()}</t></is></c>"""
    }

    private data class HeaderCell(val value: String) : Cell {
        override fun xml(reference: String): String =
            """<c r="$reference" s="1" t="inlineStr"><is><t>${value.xmlEscape()}</t></is></c>"""
    }

    private data class NumberCell(val value: Double) : Cell {
        override fun xml(reference: String): String =
            """<c r="$reference"><v>${if (value % 1.0 == 0.0) value.toLong() else value}</v></c>"""
    }

    private data class FormulaCell(
        val formula: String,
        val cachedValue: Double,
    ) : Cell {
        override fun xml(reference: String): String =
            """<c r="$reference"><f>${formula.xmlEscape()}</f><v>${if (cachedValue % 1.0 == 0.0) cachedValue.toLong() else cachedValue}</v></c>"""
    }

    private data class MonthlySummary(
        val name: String,
        val attendanceDays: Int,
        val shiftCount: Int,
        val ordinarySeconds: Long,
        val hostSeconds: Long,
        val totalSeconds: Long,
    )
}
