package com.local.micqueueassistant.domain

import com.local.micqueueassistant.data.QueueEntryEntity
import com.local.micqueueassistant.data.ShiftEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object QueuePolicy {
    fun insertParticipant(
        existing: List<QueueEntryEntity>,
        shiftId: String,
        name: String,
        capacity: Int,
        id: String,
        createdBy: String,
        now: Long,
    ): List<QueueEntryEntity> {
        require(existing.none { it.state == "queued" && it.wechatName == name }) {
            "你已经在当前麦序中"
        }
        val participants = existing.count {
            it.state == "queued" && it.role == QueueRole.PARTICIPANT.value
        }
        require(participants < capacity) { "当前普通麦位已满" }
        val nextPosition = (existing.filter { it.state == "queued" }.maxOfOrNull { it.position } ?: 0) + 1
        return existing + QueueEntryEntity(
            id = id,
            shiftId = shiftId,
            wechatName = name,
            role = QueueRole.PARTICIPANT.value,
            position = nextPosition,
            createdBy = createdBy,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
    }

    fun insertHost(
        existing: List<QueueEntryEntity>,
        shiftId: String,
        name: String,
        requestedPosition: Int,
        id: String,
        createdBy: String,
        now: Long,
    ): List<QueueEntryEntity> {
        require(existing.none {
            it.state == "queued" && it.wechatName == name && it.role == QueueRole.HOST.value
        }) { "该主持已在麦序中" }
        val active = existing.filter { it.state == "queued" }.sortedBy { it.position }
        val position = requestedPosition.coerceIn(1, active.size + 1)
        val shifted = existing.map { row ->
            if (row.state == "queued" && row.position >= position) {
                row.copy(position = row.position + 1, updatedAtEpochMs = now)
            } else {
                row
            }
        }
        return shifted + QueueEntryEntity(
            id = id,
            shiftId = shiftId,
            wechatName = name,
            role = QueueRole.HOST.value,
            position = position,
            createdBy = createdBy,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
    }

    fun compact(entries: List<QueueEntryEntity>, now: Long): List<QueueEntryEntity> {
        var next = 1
        return entries.sortedBy { it.position }.map { row ->
            if (row.state == "queued") {
                row.copy(position = next++, updatedAtEpochMs = now)
            } else {
                row
            }
        }
    }

    fun formatQueue(
        shift: ShiftEntity,
        entries: List<QueueEntryEntity>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val time = DateTimeFormatter.ofPattern("HH:mm")
        val start = Instant.ofEpochMilli(shift.startAtEpochMs).atZone(zoneId).format(time)
        val end = Instant.ofEpochMilli(shift.endAtEpochMs).atZone(zoneId).format(time)
        val cutoff = Instant.ofEpochMilli(shift.cutoffAtEpochMs).atZone(zoneId).format(time)
        val active = entries.filter { it.state == "queued" }.sortedBy { it.position }
        val hosts = active.filter { it.role == QueueRole.HOST.value }.joinToString("、") { it.wechatName }
            .ifBlank { "待定" }
        val participants = active.count { it.role == QueueRole.PARTICIPANT.value }
        return buildString {
            appendLine("主持:$hosts")
            appendLine("时间:$start-$end")
            appendLine("──────────")
            appendLine(if (shift.state == ShiftState.OPEN.value) "当前麦序" else "当前麦序已经截止")
            active.forEach { row ->
                appendLine("${row.position}.@${row.wechatName}${if (row.role == QueueRole.HOST.value) "(主持)" else "(补)"}")
            }
            appendLine("空:${(shift.capacity - participants).coerceAtLeast(0)}")
            append("$cutoff 前可补排")
        }
    }
}
