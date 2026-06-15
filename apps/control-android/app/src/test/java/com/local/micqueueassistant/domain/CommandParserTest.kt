package com.local.micqueueassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CommandParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun parsesOpenShiftAndUserCommands() {
        val open = CommandParser.parse(
            "开排 2026-06-13 16:00-17:30 8 截止16:10",
            zone,
        )
        val command = open.command as GroupCommand.OpenShift
        assertEquals(8, command.capacity)
        assertTrue(command.endAtEpochMs > command.startAtEpochMs)
        assertTrue(command.cutoffAtEpochMs in command.startAtEpochMs..command.endAtEpochMs)

        assertEquals(GroupCommand.Join(null), CommandParser.parse("补", zone).command)
        assertEquals(
            GroupCommand.Join("16:00-17:30"),
            CommandParser.parse("补 16:00-17:30", zone).command,
        )
        assertEquals(GroupCommand.Cancel, CommandParser.parse("取消", zone).command)
        assertEquals(GroupCommand.Help, CommandParser.parse("帮助", zone).command)
    }

    @Test
    fun parsesAdminBindingAndExportCommands() {
        assertEquals(
            GroupCommand.InsertHost("琳惠", 3),
            CommandParser.parse("插主持 @琳惠 3", zone).command,
        )
        assertEquals(
            GroupCommand.Bind("映客小夏"),
            CommandParser.parse("绑定 映客小夏", zone).command,
        )
        assertEquals(
            GroupCommand.Total("琳惠"),
            CommandParser.parse("累计 @琳惠", zone).command,
        )
        assertEquals(
            GroupCommand.Export("month", "2026-06"),
            CommandParser.parse("导表 月 2026-06", zone).command,
        )
    }

    @Test
    fun ignoresUnknownMessagesAndRejectsBadDates() {
        val unknown = CommandParser.parse("今天吃什么", zone)
        assertNull(unknown.command)
        assertNull(unknown.error)

        val invalid = CommandParser.parse("导表 日 2026-99-99", zone)
        assertNull(invalid.command)
        assertEquals("导表日期格式无效", invalid.error)
    }
}
