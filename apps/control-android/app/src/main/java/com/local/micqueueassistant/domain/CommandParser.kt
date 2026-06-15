package com.local.micqueueassistant.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CommandParser {
    private val openRegex = Regex(
        """^开排\s+(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})-(\d{2}:\d{2})\s+(\d+)\s+截止\s*(\d{2}:\d{2})$""",
    )
    private val joinRegex = Regex("""^补(?:\s+(.+))?$""")
    private val insertHostRegex = Regex("""^插主持\s+@?(.+?)\s+(\d+)$""")
    private val bindRegex = Regex("""^绑定\s+(.+)$""")
    private val totalRegex = Regex("""^累计(?:\s+@?(.+))?$""")
    private val exportRegex = Regex("""^导表\s+(日|周|月)\s+(\S+)$""")

    fun parse(
        raw: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): CommandParseResult {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        openRegex.matchEntire(text)?.let { match ->
            return runCatching {
                val date = LocalDate.parse(match.groupValues[1])
                val start = LocalTime.parse(match.groupValues[2])
                val end = LocalTime.parse(match.groupValues[3])
                val capacity = match.groupValues[4].toInt()
                val cutoff = LocalTime.parse(match.groupValues[5])
                require(capacity in 1..99) { "普通麦位数必须为 1–99" }
                val startDateTime = LocalDateTime.of(date, start)
                val endDateTime = LocalDateTime.of(
                    if (end <= start) date.plusDays(1) else date,
                    end,
                )
                val cutoffDateTime = LocalDateTime.of(
                    if (cutoff < start && end <= start) date.plusDays(1) else date,
                    cutoff,
                )
                require(endDateTime.isAfter(startDateTime)) { "班次结束时间必须晚于开始时间" }
                require(!cutoffDateTime.isAfter(endDateTime)) { "截止时间不能晚于班次结束" }
                CommandParseResult(
                    GroupCommand.OpenShift(
                        startAtEpochMs = startDateTime.atZone(zoneId).toInstant().toEpochMilli(),
                        endAtEpochMs = endDateTime.atZone(zoneId).toInstant().toEpochMilli(),
                        capacity = capacity,
                        cutoffAtEpochMs = cutoffDateTime.atZone(zoneId).toInstant().toEpochMilli(),
                    ),
                )
            }.getOrElse { CommandParseResult(null, it.message ?: "开排格式无效") }
        }
        joinRegex.matchEntire(text)?.let {
            return CommandParseResult(GroupCommand.Join(it.groupValues[1].ifBlank { null }))
        }
        if (text == "取消") return CommandParseResult(GroupCommand.Cancel)
        insertHostRegex.matchEntire(text)?.let {
            val position = it.groupValues[2].toIntOrNull()
            if (position == null || position < 1) {
                return CommandParseResult(null, "主持位置必须从 1 开始")
            }
            return CommandParseResult(GroupCommand.InsertHost(it.groupValues[1].trim(), position))
        }
        if (text == "截止") return CommandParseResult(GroupCommand.CloseShift)
        if (text == "麦序") return CommandParseResult(GroupCommand.ShowQueue)
        bindRegex.matchEntire(text)?.let {
            val name = it.groupValues[1].trim()
            return if (name.length in 1..40) {
                CommandParseResult(GroupCommand.Bind(name))
            } else {
                CommandParseResult(null, "映客昵称长度必须为 1–40")
            }
        }
        totalRegex.matchEntire(text)?.let {
            return CommandParseResult(GroupCommand.Total(it.groupValues[1].ifBlank { null }))
        }
        exportRegex.matchEntire(text)?.let {
            val type = when (it.groupValues[1]) {
                "日" -> "day"
                "周" -> "week"
                else -> "month"
            }
            val key = it.groupValues[2]
            val valid = runCatching {
                when (type) {
                    "month" -> YearMonth.parse(key)
                    else -> LocalDate.parse(key, DateTimeFormatter.ISO_DATE)
                }
            }.isSuccess
            return if (valid) {
                CommandParseResult(GroupCommand.Export(type, key))
            } else {
                CommandParseResult(null, "导表日期格式无效")
            }
        }
        if (text == "帮助") return CommandParseResult(GroupCommand.Help)
        return CommandParseResult(null, null)
    }
}
