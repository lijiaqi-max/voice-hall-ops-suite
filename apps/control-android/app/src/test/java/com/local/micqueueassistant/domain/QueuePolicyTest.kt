package com.local.micqueueassistant.domain

import com.local.micqueueassistant.data.QueueEntryEntity
import com.local.micqueueassistant.data.ShiftEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class QueuePolicyTest {
    @Test
    fun enforcesOrdinaryCapacityAndDuplicateRule() {
        val first = QueuePolicy.insertParticipant(
            existing = emptyList(),
            shiftId = "s1",
            name = "夏天",
            capacity = 1,
            id = "q1",
            createdBy = "夏天",
            now = 1L,
        )
        assertEquals(1, first.single().position)

        assertThrows(IllegalArgumentException::class.java) {
            QueuePolicy.insertParticipant(first, "s1", "芊芊", 1, "q2", "芊芊", 2L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QueuePolicy.insertParticipant(first, "s1", "夏天", 2, "q3", "夏天", 2L)
        }
    }

    @Test
    fun hostInsertionShiftsQueueWithoutConsumingCapacity() {
        val participants = listOf(
            row("q1", "夏天", 1),
            row("q2", "芊芊", 2),
        )
        val withHost = QueuePolicy.insertHost(
            participants,
            "s1",
            "琳惠",
            2,
            "host",
            "admin",
            10L,
        ).sortedBy(QueueEntryEntity::position)

        assertEquals(listOf("夏天", "琳惠", "芊芊"), withHost.map { it.wechatName })
        assertEquals(2, withHost.count { it.role == QueueRole.PARTICIPANT.value })
        assertEquals(QueueRole.HOST.value, withHost[1].role)
    }

    @Test
    fun queueMessageMatchesScreenshotStyle() {
        val zone = ZoneId.of("Asia/Shanghai")
        val start = LocalDateTime.of(2026, 6, 13, 16, 0).atZone(zone).toInstant().toEpochMilli()
        val shift = ShiftEntity(
            id = "s1",
            label = "2026-06-13 16:00-17:00",
            startAtEpochMs = start,
            endAtEpochMs = start + 3_600_000,
            capacity = 8,
            cutoffAtEpochMs = start + 600_000,
            state = ShiftState.OPEN.value,
            createdBy = "admin",
        )
        val text = QueuePolicy.formatQueue(shift, listOf(row("q1", "夏天", 1)), zone)
        assertTrue(text.contains("主持:待定"))
        assertTrue(text.contains("1.@夏天(补)"))
        assertTrue(text.contains("空:7"))
        assertTrue(text.contains("16:10 前可补排"))
    }

    private fun row(id: String, name: String, position: Int) = QueueEntryEntity(
        id = id,
        shiftId = "s1",
        wechatName = name,
        position = position,
        createdBy = name,
    )
}
