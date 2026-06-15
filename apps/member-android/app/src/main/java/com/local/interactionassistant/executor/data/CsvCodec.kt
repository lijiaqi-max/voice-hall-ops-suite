package com.local.interactionassistant.executor.data

data class CandidateCsvRow(
    val externalUserId: String,
    val displayName: String,
    val note: String,
    val templateId: String?,
    val imageCount: Int,
    val gender: String?,
    val consumption: Long?,
)

data class CsvImportResult(
    val rows: List<CandidateCsvRow>,
    val errors: List<String>,
)

object CsvCodec {
    private val requiredHeaders = setOf(
        "external_user_id",
        "display_name",
        "note",
        "template_id",
        "image_count",
    )

    fun parseCandidates(content: String): CsvImportResult {
        val records = parseRecords(content)
        if (records.isEmpty()) return CsvImportResult(emptyList(), listOf("CSV 文件为空"))
        val headers = records.first().map { it.trim().lowercase() }
        val missing = requiredHeaders - headers.toSet()
        if (missing.isNotEmpty()) {
            return CsvImportResult(emptyList(), listOf("缺少字段: ${missing.sorted().joinToString()}"))
        }
        val index = headers.withIndex().associate { it.value to it.index }
        val rows = mutableListOf<CandidateCsvRow>()
        val errors = mutableListOf<String>()
        records.drop(1).forEachIndexed { recordIndex, record ->
            val lineNumber = recordIndex + 2
            fun value(name: String): String = record.getOrNull(index[name] ?: -1)?.trim().orEmpty()
            val externalId = value("external_user_id")
            val displayName = value("display_name")
            val imageCount = value("image_count").ifEmpty { "0" }.toIntOrNull()
            if (externalId.length !in 3..40 || displayName.isBlank()) {
                errors += "第 $lineNumber 行：用户 ID 或昵称无效"
                return@forEachIndexed
            }
            if (imageCount == null || imageCount !in 0..3) {
                errors += "第 $lineNumber 行：image_count 必须为 0–3"
                return@forEachIndexed
            }
            val gender = value("gender").lowercase().ifBlank { null }
            if (gender != null && gender !in setOf("male", "female", "unknown")) {
                errors += "第 $lineNumber 行：gender 必须为 male、female 或 unknown"
                return@forEachIndexed
            }
            val consumptionText = value("consumption")
            val consumption = consumptionText.ifBlank { null }?.toLongOrNull()
            if (consumptionText.isNotBlank() && consumption == null) {
                errors += "第 $lineNumber 行：consumption 必须为整数"
                return@forEachIndexed
            }
            rows += CandidateCsvRow(
                externalUserId = externalId,
                displayName = displayName,
                note = value("note"),
                templateId = value("template_id").ifBlank { null },
                imageCount = imageCount,
                gender = gender,
                consumption = consumption,
            )
        }
        return CsvImportResult(rows, errors)
    }

    fun parsePastedCandidates(content: String): CsvImportResult {
        val rows = mutableListOf<CandidateCsvRow>()
        val errors = mutableListOf<String>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            val fields = if ('\t' in line) {
                line.split('\t')
            } else {
                parseRecords(line).firstOrNull().orEmpty()
            }.map(String::trim)
            val externalId = fields.getOrNull(0).orEmpty()
            val displayName = fields.getOrNull(1).orEmpty()
            if (externalId.length !in 3..40 || displayName.isBlank()) {
                errors += "第 $lineNumber 行：需要用户 ID 和昵称"
                return@forEachIndexed
            }
            val imageCount = fields.getOrNull(4).orEmpty().ifBlank { "0" }.toIntOrNull()
            if (imageCount == null || imageCount !in 0..3) {
                errors += "第 $lineNumber 行：图片数量必须为 0–3"
                return@forEachIndexed
            }
            val gender = fields.getOrNull(5)?.lowercase()?.ifBlank { null }
            if (gender != null && gender !in setOf("male", "female", "unknown")) {
                errors += "第 $lineNumber 行：性别必须为 male、female 或 unknown"
                return@forEachIndexed
            }
            val consumptionText = fields.getOrNull(6).orEmpty()
            val consumption = consumptionText.ifBlank { null }?.toLongOrNull()
            if (consumptionText.isNotBlank() && consumption == null) {
                errors += "第 $lineNumber 行：消费值必须为整数"
                return@forEachIndexed
            }
            rows += CandidateCsvRow(
                externalUserId = externalId,
                displayName = displayName,
                note = fields.getOrNull(2).orEmpty(),
                templateId = fields.getOrNull(3)?.ifBlank { null },
                imageCount = imageCount,
                gender = gender,
                consumption = consumption,
            )
        }
        return CsvImportResult(rows, errors)
    }

    fun encode(rows: List<List<String>>): String = buildString {
        rows.forEach { row ->
            append(row.joinToString(",") { field ->
                if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                    "\"${field.replace("\"", "\"\"")}\""
                } else {
                    field
                }
            })
            append("\r\n")
        }
    }

    internal fun parseRecords(content: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < content.length) {
            val char = content[index]
            when {
                quoted && char == '"' && content.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index += 1
                }
                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> {
                    row += field.toString()
                    field.clear()
                }
                !quoted && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && content.getOrNull(index + 1) == '\n') index += 1
                    row += field.toString()
                    field.clear()
                    if (row.any(String::isNotBlank)) records += row
                    row = mutableListOf()
                }
                else -> field.append(char)
            }
            index += 1
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any(String::isNotBlank)) records += row
        }
        return records
    }
}
