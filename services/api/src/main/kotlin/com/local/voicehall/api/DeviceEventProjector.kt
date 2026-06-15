package com.local.voicehall.api

import kotlinx.serialization.json.Json
import java.sql.Connection
import java.util.UUID

class DeviceEventProjector(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun project(
        connection: Connection,
        organizationId: String,
        roomId: String,
        deviceId: String,
        event: DeviceEventInput,
    ) {
        when (event.type) {
            "shift_upsert" -> projectShift(
                connection,
                organizationId,
                roomId,
                json.decodeFromString<ControlShiftEventPayload>(event.payload),
            )
            "binding_upsert" -> projectBinding(
                connection,
                organizationId,
                roomId,
                json.decodeFromString<BindingEventPayload>(event.payload),
            )
            "queue_entry_upsert" -> projectQueueEntry(
                connection,
                organizationId,
                roomId,
                json.decodeFromString<QueueEntryEventPayload>(event.payload),
            )
            "seat_snapshot" -> projectSeatSnapshot(
                connection,
                organizationId,
                roomId,
                deviceId,
                json.decodeFromString<SeatSnapshotEventPayload>(event.payload),
            )
            else -> error("Unsupported device event type: ${event.type}")
        }
    }

    private fun projectShift(
        connection: Connection,
        organizationId: String,
        roomId: String,
        payload: ControlShiftEventPayload,
    ) {
        require(payload.endAtEpochMs > payload.startAtEpochMs) { "Shift time range is invalid" }
        require(payload.capacity in 1..100) { "Shift capacity is invalid" }
        val updated = connection.prepareStatement(
            """
            UPDATE shifts
            SET title=?,start_at=?,end_at=?,status=?
            WHERE id=? AND organization_id=? AND room_id=?
            """.trimIndent(),
        ).use {
            it.setString(1, payload.label.take(160))
            it.setLong(2, payload.startAtEpochMs)
            it.setLong(3, payload.endAtEpochMs)
            it.setString(4, payload.state)
            it.setString(5, payload.shiftId)
            it.setString(6, organizationId)
            it.setString(7, roomId)
            it.executeUpdate()
        }
        if (updated == 0) {
            connection.prepareStatement(
                """
                INSERT INTO shifts(
                    id,organization_id,room_id,host_account_id,title,start_at,end_at,status,
                    host_fixed_cents,host_hourly_cents,created_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, payload.shiftId)
                it.setString(2, organizationId)
                it.setString(3, roomId)
                it.setNull(4, java.sql.Types.VARCHAR)
                it.setString(5, payload.label.take(160))
                it.setLong(6, payload.startAtEpochMs)
                it.setLong(7, payload.endAtEpochMs)
                it.setString(8, payload.state)
                it.setLong(9, 0)
                it.setLong(10, 0)
                it.setLong(11, payload.updatedAtEpochMs)
                it.executeUpdate()
            }
        }
    }

    private fun projectBinding(
        connection: Connection,
        organizationId: String,
        roomId: String,
        payload: BindingEventPayload,
    ) {
        require(payload.wechatName.isNotBlank() && payload.ingkeeName.isNotBlank()) {
            "Binding names cannot be blank"
        }
        val existingId = connection.prepareStatement(
            """
            SELECT id FROM member_platform_bindings
            WHERE organization_id=? AND room_id=? AND external_binding_id=?
            """.trimIndent(),
        ).use {
            it.setString(1, organizationId)
            it.setString(2, roomId)
            it.setString(3, payload.bindingId)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        if (existingId == null) {
            connection.prepareStatement(
                """
                INSERT INTO member_platform_bindings(
                    id,organization_id,room_id,external_binding_id,wechat_name,ingkee_name,state,
                    approved_by_name,created_at,updated_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setString(2, organizationId)
                it.setString(3, roomId)
                it.setString(4, payload.bindingId)
                it.setString(5, payload.wechatName.take(120))
                it.setString(6, payload.ingkeeName.take(120))
                it.setString(7, payload.state)
                it.setNullableString(8, payload.approvedBy?.take(120))
                it.setLong(9, payload.createdAtEpochMs)
                it.setLong(10, payload.updatedAtEpochMs)
                it.executeUpdate()
            }
        } else {
            connection.prepareStatement(
                """
                UPDATE member_platform_bindings
                SET wechat_name=?,ingkee_name=?,state=?,approved_by_name=?,updated_at=?
                WHERE id=? AND organization_id=? AND room_id=?
                """.trimIndent(),
            ).use {
                it.setString(1, payload.wechatName.take(120))
                it.setString(2, payload.ingkeeName.take(120))
                it.setString(3, payload.state)
                it.setNullableString(4, payload.approvedBy?.take(120))
                it.setLong(5, payload.updatedAtEpochMs)
                it.setString(6, existingId)
                it.setString(7, organizationId)
                it.setString(8, roomId)
                it.executeUpdate()
            }
        }
    }

    private fun projectQueueEntry(
        connection: Connection,
        organizationId: String,
        roomId: String,
        payload: QueueEntryEventPayload,
    ) {
        val shiftExists = connection.prepareStatement(
            "SELECT COUNT(*) FROM shifts WHERE id=? AND organization_id=? AND room_id=?",
        ).use {
            it.setString(1, payload.shiftId)
            it.setString(2, organizationId)
            it.setString(3, roomId)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) == 1 }
        }
        check(shiftExists) { "Queue entry shift does not exist" }
        val bindingId = findApprovedBinding(connection, organizationId, roomId, null, payload.wechatName)?.id
        val updated = connection.prepareStatement(
            """
            UPDATE queue_entries
            SET binding_id=?,wechat_name=?,role=?,position=?,state=?,created_by=?,updated_at=?
            WHERE id=? AND organization_id=? AND room_id=?
            """.trimIndent(),
        ).use {
            it.setNullableString(1, bindingId)
            it.setString(2, payload.wechatName.take(120))
            it.setString(3, payload.role)
            it.setInt(4, payload.position)
            it.setString(5, payload.state)
            it.setString(6, payload.createdBy.take(120))
            it.setLong(7, payload.updatedAtEpochMs)
            it.setString(8, payload.entryId)
            it.setString(9, organizationId)
            it.setString(10, roomId)
            it.executeUpdate()
        }
        if (updated == 0) {
            connection.prepareStatement(
                """
                INSERT INTO queue_entries(
                    id,organization_id,room_id,shift_id,binding_id,wechat_name,role,position,state,
                    created_by,created_at,updated_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, payload.entryId)
                it.setString(2, organizationId)
                it.setString(3, roomId)
                it.setString(4, payload.shiftId)
                it.setNullableString(5, bindingId)
                it.setString(6, payload.wechatName.take(120))
                it.setString(7, payload.role)
                it.setInt(8, payload.position)
                it.setString(9, payload.state)
                it.setString(10, payload.createdBy.take(120))
                it.setLong(11, payload.createdAtEpochMs)
                it.setLong(12, payload.updatedAtEpochMs)
                it.executeUpdate()
            }
        }
    }

    private fun projectSeatSnapshot(
        connection: Connection,
        organizationId: String,
        roomId: String,
        deviceId: String,
        payload: SeatSnapshotEventPayload,
    ) {
        require(payload.capturedAtEpochMs > 0) { "Snapshot timestamp is invalid" }
        val active = activePresence(connection, organizationId, roomId, deviceId)
        if (payload.pageStatus != "voice_room") {
            active.forEach { closePresence(connection, it, it.lastSeenAt, "uncertain", "Page unreadable") }
            return
        }

        val visibleNames = payload.seats
            .map(SeatObservationPayload::ingkeeName)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toSet()
        val activeByName = active.associateBy(PresenceRow::ingkeeName)

        visibleNames.forEach { name ->
            val current = activeByName[name]
            if (current == null) {
                startPresence(connection, organizationId, roomId, deviceId, name, payload.capturedAtEpochMs)
            } else if (payload.capturedAtEpochMs >= current.lastSeenAt) {
                connection.prepareStatement(
                    """
                    UPDATE active_mic_presence
                    SET last_seen_at=?,missing_since=NULL
                    WHERE organization_id=? AND room_id=? AND device_id=? AND ingkee_name=?
                    """.trimIndent(),
                ).use {
                    it.setLong(1, payload.capturedAtEpochMs)
                    it.setString(2, organizationId)
                    it.setString(3, roomId)
                    it.setString(4, deviceId)
                    it.setString(5, name)
                    it.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    UPDATE mic_segments
                    SET last_seen_at=?,duration_seconds=(?-started_at)/1000,updated_at=?
                    WHERE id=? AND state='active'
                    """.trimIndent(),
                ).use {
                    it.setLong(1, payload.capturedAtEpochMs)
                    it.setLong(2, payload.capturedAtEpochMs)
                    it.setLong(3, payload.capturedAtEpochMs)
                    it.setString(4, current.segmentId)
                    it.executeUpdate()
                }
            }
        }

        active.filter { it.ingkeeName !in visibleNames }.forEach { current ->
            if (payload.capturedAtEpochMs < current.lastSeenAt) return@forEach
            if (current.missingSince == null) {
                connection.prepareStatement(
                    """
                    UPDATE active_mic_presence SET missing_since=?
                    WHERE organization_id=? AND room_id=? AND device_id=? AND ingkee_name=?
                    """.trimIndent(),
                ).use {
                    it.setLong(1, payload.capturedAtEpochMs)
                    it.setString(2, organizationId)
                    it.setString(3, roomId)
                    it.setString(4, deviceId)
                    it.setString(5, current.ingkeeName)
                    it.executeUpdate()
                }
            } else if (payload.capturedAtEpochMs - current.missingSince >= DEBOUNCE_MS) {
                closePresence(connection, current, current.lastSeenAt, "closed", null)
            }
        }
    }

    private fun startPresence(
        connection: Connection,
        organizationId: String,
        roomId: String,
        deviceId: String,
        ingkeeName: String,
        capturedAt: Long,
    ) {
        val shiftId = connection.prepareStatement(
            """
            SELECT id FROM shifts
            WHERE organization_id=? AND room_id=? AND start_at<=? AND end_at>=?
              AND status NOT IN ('cancelled')
            ORDER BY start_at DESC LIMIT 1
            """.trimIndent(),
        ).use {
            it.setString(1, organizationId)
            it.setString(2, roomId)
            it.setLong(3, capturedAt)
            it.setLong(4, capturedAt)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        val binding = findApprovedBinding(connection, organizationId, roomId, ingkeeName, null)
        val queue = if (shiftId != null && binding != null) {
            connection.prepareStatement(
                """
                SELECT role,position FROM queue_entries
                WHERE organization_id=? AND room_id=? AND shift_id=? AND wechat_name=? AND state='queued'
                ORDER BY updated_at DESC LIMIT 1
                """.trimIndent(),
            ).use {
                it.setString(1, organizationId)
                it.setString(2, roomId)
                it.setString(3, shiftId)
                it.setString(4, binding.wechatName)
                it.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1) to rows.getInt(2) else null
                }
            }
        } else {
            null
        }
        val segmentId = UUID.randomUUID().toString()
        connection.prepareStatement(
            """
            INSERT INTO mic_segments(
                id,organization_id,room_id,shift_id,binding_id,wechat_name,ingkee_name,role,
                queue_position,started_at,last_seen_at,ended_at,duration_seconds,state,
                correction_reason,source_device_id,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(),
        ).use {
            it.setString(1, segmentId)
            it.setString(2, organizationId)
            it.setString(3, roomId)
            it.setNullableString(4, shiftId)
            it.setNullableString(5, binding?.id)
            it.setNullableString(6, binding?.wechatName)
            it.setString(7, ingkeeName.take(120))
            it.setString(8, queue?.first ?: "participant")
            it.setNullableInt(9, queue?.second)
            it.setLong(10, capturedAt)
            it.setLong(11, capturedAt)
            it.setNull(12, java.sql.Types.BIGINT)
            it.setLong(13, 0)
            it.setString(14, "active")
            it.setNull(15, java.sql.Types.VARCHAR)
            it.setString(16, deviceId)
            it.setLong(17, capturedAt)
            it.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO active_mic_presence(
                organization_id,room_id,device_id,ingkee_name,segment_id,last_seen_at,missing_since
            ) VALUES (?,?,?,?,?,?,NULL)
            """.trimIndent(),
        ).use {
            it.setString(1, organizationId)
            it.setString(2, roomId)
            it.setString(3, deviceId)
            it.setString(4, ingkeeName.take(120))
            it.setString(5, segmentId)
            it.setLong(6, capturedAt)
            it.executeUpdate()
        }
    }

    private fun closePresence(
        connection: Connection,
        presence: PresenceRow,
        endedAt: Long,
        state: String,
        reason: String?,
    ) {
        val durationSeconds = ((endedAt - presence.startedAt).coerceAtLeast(0)) / 1_000L
        connection.prepareStatement(
            """
            UPDATE mic_segments
            SET ended_at=?,last_seen_at=?,duration_seconds=?,state=?,correction_reason=?,updated_at=?
            WHERE id=? AND state='active'
            """.trimIndent(),
        ).use {
            it.setLong(1, endedAt)
            it.setLong(2, presence.lastSeenAt)
            it.setLong(3, durationSeconds)
            it.setString(4, state)
            it.setNullableString(5, reason)
            it.setLong(6, endedAt)
            it.setString(7, presence.segmentId)
            it.executeUpdate()
        }
        connection.prepareStatement(
            """
            DELETE FROM active_mic_presence
            WHERE organization_id=? AND room_id=? AND device_id=? AND ingkee_name=?
            """.trimIndent(),
        ).use {
            it.setString(1, presence.organizationId)
            it.setString(2, presence.roomId)
            it.setString(3, presence.deviceId)
            it.setString(4, presence.ingkeeName)
            it.executeUpdate()
        }
        if (presence.shiftId != null) {
            updateAttendance(connection, presence, durationSeconds, endedAt)
        }
    }

    private fun updateAttendance(
        connection: Connection,
        presence: PresenceRow,
        durationSeconds: Long,
        endedAt: Long,
    ) {
        val identityKey = presence.bindingId ?: "ingkee:${presence.ingkeeName}"
        val existingId = connection.prepareStatement(
            """
            SELECT id FROM attendance
            WHERE organization_id=? AND room_id=? AND shift_id=? AND identity_key=?
            """.trimIndent(),
        ).use {
            it.setString(1, presence.organizationId)
            it.setString(2, presence.roomId)
            it.setString(3, presence.shiftId)
            it.setString(4, identityKey)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        val hostSeconds = if (presence.role == "host") durationSeconds else 0
        val ordinarySeconds = if (presence.role == "host") 0 else durationSeconds
        if (existingId == null) {
            connection.prepareStatement(
                """
                INSERT INTO attendance(
                    id,organization_id,room_id,shift_id,identity_key,binding_id,wechat_name,
                    ingkee_name,first_seen_at,last_seen_at,ordinary_seconds,host_seconds,
                    segment_count,updated_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setString(2, presence.organizationId)
                it.setString(3, presence.roomId)
                it.setString(4, presence.shiftId)
                it.setString(5, identityKey)
                it.setNullableString(6, presence.bindingId)
                it.setNullableString(7, presence.wechatName)
                it.setString(8, presence.ingkeeName)
                it.setLong(9, presence.startedAt)
                it.setLong(10, endedAt)
                it.setLong(11, ordinarySeconds)
                it.setLong(12, hostSeconds)
                it.setInt(13, 1)
                it.setLong(14, endedAt)
                it.executeUpdate()
            }
        } else {
            connection.prepareStatement(
                """
                UPDATE attendance
                SET first_seen_at=CASE WHEN first_seen_at<? THEN first_seen_at ELSE ? END,
                    last_seen_at=CASE WHEN last_seen_at>? THEN last_seen_at ELSE ? END,
                    ordinary_seconds=ordinary_seconds+?,
                    host_seconds=host_seconds+?,
                    segment_count=segment_count+1,
                    binding_id=COALESCE(binding_id,?),
                    wechat_name=COALESCE(wechat_name,?),
                    updated_at=?
                WHERE id=?
                """.trimIndent(),
            ).use {
                it.setLong(1, presence.startedAt)
                it.setLong(2, presence.startedAt)
                it.setLong(3, endedAt)
                it.setLong(4, endedAt)
                it.setLong(5, ordinarySeconds)
                it.setLong(6, hostSeconds)
                it.setNullableString(7, presence.bindingId)
                it.setNullableString(8, presence.wechatName)
                it.setLong(9, endedAt)
                it.setString(10, existingId)
                it.executeUpdate()
            }
        }
    }

    private fun activePresence(
        connection: Connection,
        organizationId: String,
        roomId: String,
        deviceId: String,
    ): List<PresenceRow> = connection.prepareStatement(
        """
        SELECT p.organization_id,p.room_id,p.device_id,p.ingkee_name,p.segment_id,
               p.last_seen_at,p.missing_since,s.shift_id,s.binding_id,s.wechat_name,
               s.role,s.queue_position,s.started_at
        FROM active_mic_presence p JOIN mic_segments s ON s.id=p.segment_id
        WHERE p.organization_id=? AND p.room_id=? AND p.device_id=?
        """.trimIndent(),
    ).use {
        it.setString(1, organizationId)
        it.setString(2, roomId)
        it.setString(3, deviceId)
        it.executeQuery().use { rows ->
            val result = mutableListOf<PresenceRow>()
            while (rows.next()) {
                result += PresenceRow(
                    organizationId = rows.getString("organization_id"),
                    roomId = rows.getString("room_id"),
                    deviceId = rows.getString("device_id"),
                    ingkeeName = rows.getString("ingkee_name"),
                    segmentId = rows.getString("segment_id"),
                    lastSeenAt = rows.getLong("last_seen_at"),
                    missingSince = rows.getNullableLong("missing_since"),
                    shiftId = rows.getString("shift_id"),
                    bindingId = rows.getString("binding_id"),
                    wechatName = rows.getString("wechat_name"),
                    role = rows.getString("role"),
                    queuePosition = rows.getNullableInt("queue_position"),
                    startedAt = rows.getLong("started_at"),
                )
            }
            result
        }
    }

    private fun findApprovedBinding(
        connection: Connection,
        organizationId: String,
        roomId: String,
        ingkeeName: String?,
        wechatName: String?,
    ): BindingRow? {
        val condition = if (ingkeeName != null) "ingkee_name=?" else "wechat_name=?"
        return connection.prepareStatement(
            """
            SELECT id,wechat_name FROM member_platform_bindings
            WHERE organization_id=? AND room_id=? AND state='approved' AND $condition
            ORDER BY updated_at DESC LIMIT 1
            """.trimIndent(),
        ).use {
            it.setString(1, organizationId)
            it.setString(2, roomId)
            it.setString(3, ingkeeName ?: wechatName)
            it.executeQuery().use { rows ->
                if (rows.next()) BindingRow(rows.getString(1), rows.getString(2)) else null
            }
        }
    }

    private data class BindingRow(val id: String, val wechatName: String)

    private data class PresenceRow(
        val organizationId: String,
        val roomId: String,
        val deviceId: String,
        val ingkeeName: String,
        val segmentId: String,
        val lastSeenAt: Long,
        val missingSince: Long?,
        val shiftId: String?,
        val bindingId: String?,
        val wechatName: String?,
        val role: String,
        val queuePosition: Int?,
        val startedAt: Long,
    )

    companion object {
        private const val DEBOUNCE_MS = 10_000L
    }
}

private fun java.sql.ResultSet.getNullableLong(column: String): Long? =
    getLong(column).let { if (wasNull()) null else it }

private fun java.sql.ResultSet.getNullableInt(column: String): Int? =
    getInt(column).let { if (wasNull()) null else it }

private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, java.sql.Types.VARCHAR) else setString(index, value)
}

private fun java.sql.PreparedStatement.setNullableInt(index: Int, value: Int?) {
    if (value == null) setNull(index, java.sql.Types.INTEGER) else setInt(index, value)
}
