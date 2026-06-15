package com.local.voicehall.api

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object FinanceWorkbook {
    fun generate(report: FinancialReport): ByteArray {
        val sheets = listOf(
            Sheet(
                "经营汇总",
                listOf(
                    listOf(text("项目"), text("金额（元）"), text("说明")),
                    summaryRow("总流水", report.settlement.grossCents),
                    summaryRow("平台扣除", report.settlement.platformDeductionCents),
                    summaryRow("公会/厅分成", report.settlement.organizationShareCents),
                    summaryRow("成员佣金", report.settlement.memberCommissionCents),
                    summaryRow(
                        "主持费用",
                        report.settlement.hostCostCents,
                        if (report.settlement.hostCostEstimated) "含人工确认的预估费用" else "已核验麦时",
                    ),
                    summaryRow("其他支出", report.settlement.expenseCents),
                    summaryRow("调整合计", report.settlement.adjustmentCents),
                    summaryRow("应收", report.settlement.accountsReceivableCents),
                    summaryRow("应付", report.settlement.accountsPayableCents),
                    summaryRow("净利润", report.settlement.netProfitCents),
                    summaryRow("勾稽差额", report.settlement.reconciliationDifferenceCents),
                ),
            ),
            Sheet(
                "成员佣金",
                listOf(listOf(text("成员"), text("归属流水（元）"), text("佣金（元）"), text("说明"))) +
                    report.memberCommissions.map {
                        listOf(text(it.label), money(it.grossCents), money(it.amountCents), text(it.note.orEmpty()))
                    },
            ),
            Sheet(
                "主持成本",
                listOf(listOf(text("班次"), text("主持费用（元）"), text("核验状态"), text("说明"))) +
                    report.hostCosts.map {
                        listOf(
                            text(it.label),
                            money(it.amountCents),
                            text(if (it.verified) "已核验" else "预估"),
                            text(it.note.orEmpty()),
                        )
                    },
            ),
            Sheet(
                "调整与支出",
                listOf(
                    listOf(
                        text("类型"),
                        text("项目"),
                        text("金额（元）"),
                        text("原因/备注"),
                        text("调整前应收（元）"),
                        text("调整前应付（元）"),
                        text("调整前净利润（元）"),
                        text("调整后应收（元）"),
                        text("调整后应付（元）"),
                        text("调整后净利润（元）"),
                    ),
                ) + report.expenses.map {
                    listOf(
                        text("支出"),
                        text(it.label),
                        money(it.amountCents),
                        text(it.note.orEmpty()),
                        blank(),
                        blank(),
                        blank(),
                        blank(),
                        blank(),
                        blank(),
                    )
                } + report.adjustments.map {
                    listOf(
                        text("调整单"),
                        text(it.effect),
                        money(it.amountCents),
                        text(it.reason),
                        money(it.beforeReceivableCents),
                        money(it.beforePayableCents),
                        money(it.beforeNetProfitCents),
                        money(it.afterReceivableCents),
                        money(it.afterPayableCents),
                        money(it.afterNetProfitCents),
                    )
                },
            ),
        )
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.writeEntry("[Content_Types].xml", contentTypes(sheets.size))
                zip.writeEntry("_rels/.rels", rootRelationships())
                zip.writeEntry("xl/workbook.xml", workbook(sheets))
                zip.writeEntry("xl/_rels/workbook.xml.rels", workbookRelationships(sheets.size))
                zip.writeEntry("xl/styles.xml", styles())
                sheets.forEachIndexed { index, sheet ->
                    zip.writeEntry("xl/worksheets/sheet${index + 1}.xml", worksheet(sheet))
                }
            }
            output.toByteArray()
        }
    }

    private fun summaryRow(label: String, cents: Long, note: String = "") =
        listOf(text(label), money(cents), text(note))

    private fun contentTypes(sheetCount: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        repeat(sheetCount) { index ->
            append("""<Override PartName="/xl/worksheets/sheet${index + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRelationships() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private fun workbook(sheets: List<Sheet>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append(
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
                """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""",
        )
        sheets.forEachIndexed { index, sheet ->
            append(
                """<sheet name="${xml(sheet.name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""",
            )
        }
        append("</sheets></workbook>")
    }

    private fun workbookRelationships(sheetCount: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        repeat(sheetCount) { index ->
            append(
                """<Relationship Id="rId${index + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${index + 1}.xml"/>""",
            )
        }
        append(
            """<Relationship Id="rId${sheetCount + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""",
        )
        append("</Relationships>")
    }

    private fun styles() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="2"><font><sz val="11"/><name val="等线"/></font><font><b/><sz val="11"/><name val="等线"/></font></fonts>""" +
            """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0"/></cellXfs>""" +
            """</styleSheet>"""

    private fun worksheet(sheet: Sheet) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("""<cols><col min="1" max="10" width="20" customWidth="1"/></cols><sheetData>""")
        sheet.rows.forEachIndexed { rowIndex, row ->
            append("""<row r="${rowIndex + 1}">""")
            row.forEachIndexed { columnIndex, cell ->
                val reference = "${columnName(columnIndex)}${rowIndex + 1}"
                val style = if (rowIndex == 0) 2 else if (cell.numeric) 1 else 0
                if (cell.value.isBlank() && !cell.numeric) {
                    append("""<c r="$reference" s="$style"/>""")
                } else if (cell.numeric) {
                    append("""<c r="$reference" s="$style"><v>${cell.value}</v></c>""")
                } else {
                    append(
                        """<c r="$reference" s="$style" t="inlineStr"><is><t xml:space="preserve">${xml(cell.value)}</t></is></c>""",
                    )
                }
            }
            append("</row>")
        }
        append("</sheetData><autoFilter ref=\"A1:${columnName((sheet.rows.maxOfOrNull(List<Cell>::size) ?: 1) - 1)}${sheet.rows.size}\"/></worksheet>")
    }

    private fun money(cents: Long) = Cell(centsToYuan(cents), numeric = true)
    private fun text(value: String) = Cell(value, numeric = false)
    private fun blank() = Cell("", numeric = false)

    private fun centsToYuan(cents: Long): String {
        val negative = cents < 0
        val absolute = cents.toBigInteger().abs()
        val whole = absolute.divide(100.toBigInteger())
        val fraction = absolute.remainder(100.toBigInteger()).toString().padStart(2, '0')
        return "${if (negative) "-" else ""}$whole.$fraction"
    }

    private fun columnName(index: Int): String {
        var value = index + 1
        val result = StringBuilder()
        while (value > 0) {
            value--
            result.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return result.reverse().toString()
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun ZipOutputStream.writeEntry(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private data class Sheet(val name: String, val rows: List<List<Cell>>)
    private data class Cell(val value: String, val numeric: Boolean)
}
