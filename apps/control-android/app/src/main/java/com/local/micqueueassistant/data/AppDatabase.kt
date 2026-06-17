package com.local.micqueueassistant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey val id: Int = 1,
    val role: String = "unselected",
    val groupTitle: String = "",
    val ownerWechatName: String = "",
    val robotAutoReplyEnabled: Boolean = false,
    val wechatCalibrationStatus: String = "微信自动回复待校准",
    val ingkeeCalibrationStatus: String = "映客麦位识别待校准",
    val serverPort: Int = 8765,
    val collectorHost: String = "",
    val pairingCode: String = "",
    val pinnedCertificateSha256: String = "",
    val foregroundServiceEnabled: Boolean = false,
    val cloudBaseUrl: String = "",
    val cloudRoomId: String = "",
    val cloudDeviceId: String = "",
    val cloudDeviceRole: String = "",
    val wechatServerCapabilityEnabled: Boolean = false,
    val ingkeeServerCapabilityEnabled: Boolean = false,
    val wechatOfficialReplyEnabled: Boolean = false,
    val ingkeeOfficialCaptureEnabled: Boolean = false,
    val cloudLastSyncAtEpochMs: Long? = null,
    val cloudSyncEnabled: Boolean = false,
)

@Entity(tableName = "admins", indices = [Index(value = ["wechatName"], unique = true)])
data class AdminEntity(
    @PrimaryKey val id: String,
    val wechatName: String,
    val enabled: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "member_bindings",
    indices = [
        Index(value = ["wechatName"], unique = true),
        Index(value = ["ingkeeName"], unique = true),
        Index("state"),
    ],
)
data class MemberBindingEntity(
    @PrimaryKey val id: String,
    val wechatName: String,
    val ingkeeName: String,
    val state: String = "pending",
    val approvedBy: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "shifts",
    indices = [Index("state"), Index("startAtEpochMs"), Index("endAtEpochMs")],
)
data class ShiftEntity(
    @PrimaryKey val id: String,
    val label: String,
    val startAtEpochMs: Long,
    val endAtEpochMs: Long,
    val capacity: Int,
    val cutoffAtEpochMs: Long,
    val state: String = "draft",
    val createdBy: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "queue_entries",
    indices = [
        Index("shiftId"),
        Index("wechatName"),
        Index(value = ["shiftId", "wechatName", "state"]),
    ],
)
data class QueueEntryEntity(
    @PrimaryKey val id: String,
    val shiftId: String,
    val wechatName: String,
    val role: String = "participant",
    val position: Int,
    val state: String = "queued",
    val createdBy: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "seat_snapshots",
    indices = [Index(value = ["eventId"], unique = true), Index("capturedAtEpochMs")],
)
data class SeatSnapshotEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val seatsJson: String,
    val source: String,
    val pageStatus: String,
    val capturedAtEpochMs: Long,
)

@Entity(
    tableName = "mic_segments",
    indices = [
        Index("shiftId"),
        Index("ingkeeName"),
        Index("bindingId"),
        Index("state"),
        Index("startedAtEpochMs"),
    ],
)
data class MicSegmentEntity(
    @PrimaryKey val id: String,
    val shiftId: String?,
    val bindingId: String?,
    val wechatName: String?,
    val ingkeeName: String,
    val role: String,
    val queuePosition: Int?,
    val startedAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val durationSeconds: Long,
    val state: String = "active",
    val correctionReason: String? = null,
)

@Entity(
    tableName = "command_records",
    indices = [Index(value = ["fingerprint"], unique = true), Index("receivedAtEpochMs")],
)
data class CommandRecordEntity(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val groupTitle: String,
    val senderWechatName: String,
    val rawText: String,
    val commandType: String,
    val accepted: Boolean,
    val resultMessage: String,
    val receivedAtEpochMs: Long,
)

@Entity(
    tableName = "reply_queue",
    indices = [Index(value = ["sourceCommandId"], unique = true), Index("state")],
)
data class ReplyEntity(
    @PrimaryKey val id: String,
    val sourceCommandId: String,
    val groupTitle: String,
    val message: String,
    val state: String = "pending",
    val attemptCount: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val failureReason: String? = null,
)

@Entity(
    tableName = "collector_events",
    indices = [Index(value = ["eventId"], unique = true), Index("state")],
)
data class CollectorEventEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val type: String,
    val payloadJson: String,
    val state: String = "pending",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val sentAtEpochMs: Long? = null,
)

@Entity(tableName = "paired_devices", indices = [Index(value = ["deviceId"], unique = true)])
data class PairedDeviceEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val displayName: String,
    val role: String,
    val certificateSha256: String,
    val pairedAtEpochMs: Long = System.currentTimeMillis(),
    val lastSeenAtEpochMs: Long? = null,
)

@Entity(tableName = "export_tasks", indices = [Index("state")])
data class ExportTaskEntity(
    @PrimaryKey val id: String,
    val periodType: String,
    val periodKey: String,
    val state: String = "pending",
    val fileName: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val completedAtEpochMs: Long? = null,
    val failureReason: String? = null,
)

@Entity(tableName = "audit_logs", indices = [Index("createdAtEpochMs"), Index("type")])
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val level: String,
    val type: String,
    val message: String,
    val relatedId: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Dao
interface AppDao {
    @Query("SELECT * FROM app_config WHERE id = 1")
    fun observeConfig(): Flow<AppConfigEntity?>

    @Query("SELECT * FROM app_config WHERE id = 1")
    suspend fun getConfig(): AppConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConfig(config: AppConfigEntity)

    @Query("SELECT * FROM admins ORDER BY createdAtEpochMs")
    fun observeAdmins(): Flow<List<AdminEntity>>

    @Query("SELECT COUNT(*) FROM admins WHERE wechatName = :name AND enabled = 1")
    suspend fun isAdmin(name: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdmin(admin: AdminEntity)

    @Query("DELETE FROM admins WHERE id = :id")
    suspend fun deleteAdmin(id: String)

    @Query("SELECT * FROM member_bindings ORDER BY updatedAtEpochMs DESC")
    fun observeBindings(): Flow<List<MemberBindingEntity>>

    @Query("SELECT * FROM member_bindings WHERE wechatName = :name LIMIT 1")
    suspend fun bindingForWechat(name: String): MemberBindingEntity?

    @Query("SELECT * FROM member_bindings WHERE ingkeeName = :name AND state = 'approved' LIMIT 1")
    suspend fun approvedBindingForIngkee(name: String): MemberBindingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBinding(binding: MemberBindingEntity)

    @Query(
        """
        UPDATE member_bindings
        SET state = :state, approvedBy = :approvedBy, updatedAtEpochMs = :now
        WHERE id = :id
        """,
    )
    suspend fun updateBindingState(
        id: String,
        state: String,
        approvedBy: String?,
        now: Long = System.currentTimeMillis(),
    )

    @Query("SELECT * FROM shifts ORDER BY startAtEpochMs DESC")
    fun observeShifts(): Flow<List<ShiftEntity>>

    @Query("SELECT * FROM shifts ORDER BY startAtEpochMs DESC")
    suspend fun getAllShifts(): List<ShiftEntity>

    @Query("SELECT * FROM shifts WHERE id = :id")
    suspend fun getShift(id: String): ShiftEntity?

    @Query("SELECT * FROM shifts WHERE state = 'open' ORDER BY startAtEpochMs LIMIT 1")
    suspend fun getOpenShift(): ShiftEntity?

    @Query(
        """
        SELECT * FROM shifts
        WHERE startAtEpochMs <= :atEpochMs AND endAtEpochMs >= :atEpochMs
          AND state IN ('open', 'closed')
        ORDER BY startAtEpochMs DESC LIMIT 1
        """,
    )
    suspend fun getShiftAt(atEpochMs: Long): ShiftEntity?

    @Query(
        """
        SELECT * FROM shifts
        WHERE state NOT IN ('cancelled', 'completed')
          AND startAtEpochMs < :endAt AND endAtEpochMs > :startAt
        LIMIT 1
        """,
    )
    suspend fun findOverlappingShift(startAt: Long, endAt: Long): ShiftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveShift(shift: ShiftEntity)

    @Query("UPDATE shifts SET state = :state, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateShiftState(id: String, state: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM queue_entries WHERE shiftId = :shiftId ORDER BY position")
    fun observeQueue(shiftId: String): Flow<List<QueueEntryEntity>>

    @Query("SELECT * FROM queue_entries WHERE shiftId = :shiftId ORDER BY position")
    suspend fun getQueue(shiftId: String): List<QueueEntryEntity>

    @Query("SELECT * FROM queue_entries ORDER BY createdAtEpochMs")
    suspend fun getAllQueueEntries(): List<QueueEntryEntity>

    @Query(
        """
        SELECT * FROM queue_entries
        WHERE shiftId = :shiftId AND wechatName = :name AND state = 'queued'
        LIMIT 1
        """,
    )
    suspend fun activeQueueEntry(shiftId: String, name: String): QueueEntryEntity?

    @Query(
        """
        SELECT COUNT(*) FROM queue_entries
        WHERE shiftId = :shiftId AND wechatName = :name AND state = 'queued'
        """,
    )
    suspend fun countActiveQueueEntry(shiftId: String, name: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveQueueEntry(entry: QueueEntryEntity)

    @Update
    suspend fun updateQueueEntries(entries: List<QueueEntryEntity>)

    @Query(
        """
        UPDATE queue_entries SET state = 'cancelled', updatedAtEpochMs = :now
        WHERE shiftId = :shiftId AND wechatName = :name AND state = 'queued'
        """,
    )
    suspend fun cancelQueueEntry(
        shiftId: String,
        name: String,
        now: Long = System.currentTimeMillis(),
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSnapshot(snapshot: SeatSnapshotEntity): Long

    @Query("SELECT * FROM seat_snapshots ORDER BY capturedAtEpochMs DESC LIMIT 100")
    fun observeSnapshots(): Flow<List<SeatSnapshotEntity>>

    @Query("SELECT * FROM mic_segments ORDER BY startedAtEpochMs DESC")
    fun observeSegments(): Flow<List<MicSegmentEntity>>

    @Query("SELECT * FROM mic_segments WHERE state = 'active'")
    suspend fun activeSegments(): List<MicSegmentEntity>

    @Query(
        """
        SELECT * FROM mic_segments
        WHERE ingkeeName = :ingkeeName AND state = 'active'
        LIMIT 1
        """,
    )
    suspend fun activeSegmentFor(ingkeeName: String): MicSegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSegment(segment: MicSegmentEntity)

    @Query(
        """
        UPDATE mic_segments
        SET lastSeenAtEpochMs = :atEpochMs,
            durationSeconds = (:atEpochMs - startedAtEpochMs) / 1000
        WHERE id = :id AND state = 'active'
        """,
    )
    suspend fun touchSegment(id: String, atEpochMs: Long)

    @Query(
        """
        UPDATE mic_segments
        SET endedAtEpochMs = :endedAtEpochMs,
            lastSeenAtEpochMs = :lastSeenAtEpochMs,
            durationSeconds = (:endedAtEpochMs - startedAtEpochMs) / 1000,
            state = :state,
            correctionReason = :reason
        WHERE id = :id AND state = 'active'
        """,
    )
    suspend fun closeSegment(
        id: String,
        endedAtEpochMs: Long,
        lastSeenAtEpochMs: Long,
        state: String,
        reason: String?,
    )

    @Query(
        """
        UPDATE mic_segments
        SET endedAtEpochMs = lastSeenAtEpochMs,
            durationSeconds = (lastSeenAtEpochMs - startedAtEpochMs) / 1000,
            state = 'uncertain',
            correctionReason = :reason
        WHERE state = 'active'
        """,
    )
    suspend fun markAllActiveSegmentsUncertain(reason: String)

    @Query(
        """
        UPDATE mic_segments
        SET endedAtEpochMs = startedAtEpochMs + (:durationSeconds * 1000),
            lastSeenAtEpochMs = startedAtEpochMs + (:durationSeconds * 1000),
            durationSeconds = :durationSeconds,
            state = 'corrected',
            correctionReason = :reason
        WHERE id = :id
        """,
    )
    suspend fun correctSegment(id: String, durationSeconds: Long, reason: String): Int

    @Query(
        """
        UPDATE mic_segments
        SET bindingId = :bindingId, wechatName = :wechatName,
            role = :role, queuePosition = :queuePosition
        WHERE ingkeeName = :ingkeeName AND bindingId IS NULL
        """,
    )
    suspend fun backfillBinding(
        ingkeeName: String,
        bindingId: String,
        wechatName: String,
        role: String,
        queuePosition: Int?,
    )

    @Query(
        """
        SELECT * FROM mic_segments
        WHERE startedAtEpochMs < :endAt
          AND COALESCE(endedAtEpochMs, lastSeenAtEpochMs) >= :startAt
        ORDER BY startedAtEpochMs
        """,
    )
    suspend fun segmentsBetween(startAt: Long, endAt: Long): List<MicSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCommand(command: CommandRecordEntity): Long

    @Query(
        """
        UPDATE command_records
        SET accepted = :accepted, resultMessage = :resultMessage
        WHERE id = :id
        """,
    )
    suspend fun updateCommandResult(id: String, accepted: Boolean, resultMessage: String)

    @Query("SELECT * FROM command_records ORDER BY receivedAtEpochMs DESC LIMIT 100")
    fun observeCommands(): Flow<List<CommandRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReply(reply: ReplyEntity): Long

    @Query("SELECT * FROM reply_queue WHERE state = 'pending' ORDER BY createdAtEpochMs LIMIT 1")
    suspend fun nextPendingReply(): ReplyEntity?

    @Query(
        """
        UPDATE reply_queue
        SET state = :state, attemptCount = attemptCount + :attemptIncrement,
            updatedAtEpochMs = :now, failureReason = :failureReason
        WHERE id = :id
        """,
    )
    suspend fun updateReplyState(
        id: String,
        state: String,
        attemptIncrement: Int = 0,
        failureReason: String? = null,
        now: Long = System.currentTimeMillis(),
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectorEvent(event: CollectorEventEntity): Long

    @Query("SELECT * FROM collector_events WHERE state = 'pending' ORDER BY createdAtEpochMs")
    suspend fun pendingCollectorEvents(): List<CollectorEventEntity>

    @Query("UPDATE collector_events SET state = 'sent', sentAtEpochMs = :now WHERE id = :id")
    suspend fun markCollectorEventSent(id: String, now: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePairedDevice(device: PairedDeviceEntity)

    @Query("SELECT * FROM paired_devices ORDER BY pairedAtEpochMs DESC")
    fun observePairedDevices(): Flow<List<PairedDeviceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveExportTask(task: ExportTaskEntity)

    @Query("SELECT * FROM export_tasks ORDER BY createdAtEpochMs DESC")
    fun observeExportTasks(): Flow<List<ExportTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun log(row: AuditLogEntity)

    @Query("SELECT * FROM audit_logs ORDER BY createdAtEpochMs DESC LIMIT 200")
    fun observeLogs(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs ORDER BY createdAtEpochMs")
    suspend fun getAllLogs(): List<AuditLogEntity>

    @Transaction
    suspend fun replaceQueue(entries: List<QueueEntryEntity>) {
        updateQueueEntries(entries)
    }
}

@Database(
    entities = [
        AppConfigEntity::class,
        AdminEntity::class,
        MemberBindingEntity::class,
        ShiftEntity::class,
        QueueEntryEntity::class,
        SeatSnapshotEntity::class,
        MicSegmentEntity::class,
        CommandRecordEntity::class,
        ReplyEntity::class,
        CollectorEventEntity::class,
        PairedDeviceEntity::class,
        ExportTaskEntity::class,
        AuditLogEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "mic-queue-assistant.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_config ADD COLUMN cloudBaseUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_config ADD COLUMN cloudRoomId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_config ADD COLUMN cloudDeviceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_config ADD COLUMN cloudLastSyncAtEpochMs INTEGER")
                db.execSQL(
                    "ALTER TABLE app_config ADD COLUMN cloudSyncEnabled INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_config ADD COLUMN cloudDeviceRole TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "ALTER TABLE app_config ADD COLUMN wechatServerCapabilityEnabled INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE app_config ADD COLUMN ingkeeServerCapabilityEnabled INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE app_config ADD COLUMN wechatOfficialReplyEnabled INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE app_config ADD COLUMN ingkeeOfficialCaptureEnabled INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
