package com.local.interactionassistant.executor.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "candidates",
    indices = [
        Index(value = ["externalUserId"], unique = true),
        Index("platform"),
        Index("relationshipStage"),
        Index("priorityScore"),
    ],
)
data class CandidateEntity(
    @PrimaryKey val id: String,
    val externalUserId: String,
    val displayName: String,
    val note: String = "",
    val source: String = "manual",
    val gender: String? = null,
    val consumption: Long? = null,
    val templateId: String? = null,
    val imageCount: Int = 0,
    val state: String = "available",
    @ColumnInfo(defaultValue = "'ingkee'") val platform: String = "ingkee",
    @ColumnInfo(defaultValue = "'new_interaction'")
    val relationshipStage: String = RelationshipStage.NEW_INTERACTION.value,
    @ColumnInfo(defaultValue = "'manual_confirmed'")
    val contactEligibility: String = ContactEligibility.MANUAL_CONFIRMED.value,
    val lastInteractionAtEpochMs: Long? = null,
    val lastFollowUpAtEpochMs: Long? = null,
    val nextFollowUpAtEpochMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val priorityScore: Int = 0,
    val doNotContactReason: String? = null,
    @ColumnInfo(defaultValue = "0") val manualPriorityConfirmed: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "message_templates")
data class MessageTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val randomize: Boolean = true,
    val enabled: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "template_lines",
    foreignKeys = [
        ForeignKey(
            entity = MessageTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("templateId")],
)
data class TemplateLineEntity(
    @PrimaryKey val id: String,
    val templateId: String,
    val content: String,
    val position: Int,
)

@Serializable
@Entity(
    tableName = "media_assets",
    indices = [Index(value = ["sha256"], unique = true)],
)
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val privatePath: String,
    val mimeType: String,
    val sha256: String,
    val byteSize: Long,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = CandidateEntity::class,
            parentColumns = ["id"],
            childColumns = ["candidateId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("candidateId"),
        Index("externalUserId"),
        Index("state"),
        Index("runSessionId"),
        Index("retryOfTaskId"),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val candidateId: String,
    val externalUserId: String,
    val displayName: String,
    val note: String,
    val source: String,
    val gender: String? = null,
    val consumption: Long? = null,
    val templateId: String? = null,
    val messageText: String,
    val imageCount: Int = 0,
    val imagePolicy: String = "random",
    val state: String = "presented",
    val approvedAtEpochMs: Long? = null,
    val approvalExpiresAtEpochMs: Long? = null,
    val finalConfirmedAtEpochMs: Long? = null,
    val verificationSummary: String? = null,
    val failureReason: String? = null,
    val runSessionId: String? = null,
    val retryOfTaskId: String? = null,
    @ColumnInfo(defaultValue = "1") val attemptNumber: Int = 1,
    val failureCode: String? = null,
    @ColumnInfo(defaultValue = "0") val minDelaySeconds: Int = 0,
    @ColumnInfo(defaultValue = "3") val maxDelaySeconds: Int = 3,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "task_assets",
    primaryKeys = ["taskId", "assetId"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("taskId"), Index("assetId")],
)
data class TaskAssetEntity(
    val taskId: String,
    val assetId: String,
    val position: Int,
)

@Serializable
@Entity(tableName = "blacklist")
data class BlacklistEntity(
    @PrimaryKey val externalUserId: String,
    val displayName: String = "",
    val reason: String = "",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "send_history",
    indices = [Index("externalUserId"), Index(value = ["taskId"], unique = true)],
)
data class SendHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val externalUserId: String,
    val displayName: String,
    val messageText: String,
    val imageCount: Int,
    val sentAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "run_logs")
data class RunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val level: String,
    val type: String,
    val message: String,
    val taskId: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val approvalTtlSeconds: Int = 60,
    val minDelaySeconds: Int = 0,
    val maxDelaySeconds: Int = 3,
    val deduplicateSuccessfulUsers: Boolean = true,
    val clearPublishedImagesAfterSend: Boolean = true,
    @ColumnInfo(defaultValue = "0") val onboardingCompleted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val officialAutoNavigationEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "20") val dailyContactLimit: Int = 20,
    @ColumnInfo(defaultValue = "2") val perUserSevenDayLimit: Int = 2,
)

@Serializable
@Entity(
    tableName = "automation_profiles",
    indices = [Index(value = ["name"], unique = true), Index("isDefault")],
)
data class AutomationProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val templateId: String? = null,
    val genderFilter: String = "all",
    val minConsumption: Long? = null,
    val maxConsumption: Long? = null,
    val sourceFilter: String = "all",
    val unknownFieldPolicy: String = "retain",
    val imagePolicy: String = "none",
    val imageCount: Int = 0,
    val minDelaySeconds: Int = 0,
    val maxDelaySeconds: Int = 3,
    val deduplicateSuccessfulUsers: Boolean = true,
    val isDefault: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "profile_assets",
    primaryKeys = ["profileId", "assetId"],
    foreignKeys = [
        ForeignKey(
            entity = AutomationProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("profileId"), Index("assetId")],
)
data class ProfileAssetEntity(
    val profileId: String,
    val assetId: String,
    val position: Int,
)

@Serializable
@Entity(
    tableName = "block_rules",
    indices = [Index(value = ["type", "normalizedValue"], unique = true), Index("enabled")],
)
data class BlockRuleEntity(
    @PrimaryKey val id: String,
    val type: String,
    val value: String,
    val normalizedValue: String,
    val enabled: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "run_sessions",
    indices = [Index("profileId"), Index("state")],
)
data class RunSessionEntity(
    @PrimaryKey val id: String,
    val profileId: String?,
    val profileName: String,
    val state: String = "draft",
    val totalCount: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val startedAtEpochMs: Long? = null,
    val pausedAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
)

@Serializable
@Entity(
    tableName = "interaction_events",
    foreignKeys = [
        ForeignKey(
            entity = CandidateEntity::class,
            parentColumns = ["id"],
            childColumns = ["candidateId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("candidateId"), Index("type"), Index("occurredAtEpochMs")],
)
data class InteractionEventEntity(
    @PrimaryKey val id: String,
    val candidateId: String,
    val externalUserId: String,
    val type: String,
    val source: String,
    val summary: String = "",
    val occurredAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "relationship_stage_history",
    foreignKeys = [
        ForeignKey(
            entity = CandidateEntity::class,
            parentColumns = ["id"],
            childColumns = ["candidateId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("candidateId"), Index("changedAtEpochMs")],
)
data class RelationshipStageHistoryEntity(
    @PrimaryKey val id: String,
    val candidateId: String,
    val fromStage: String,
    val toStage: String,
    val reason: String,
    val changedAtEpochMs: Long = System.currentTimeMillis(),
)

data class TemplateWithLines(
    @androidx.room.Embedded val template: MessageTemplateEntity,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "templateId",
    )
    val lines: List<TemplateLineEntity>,
)

data class TaskAssetView(
    val id: String,
    val displayName: String,
    val privatePath: String,
    val mimeType: String,
    val sha256: String,
    val byteSize: Long,
    val position: Int,
)

data class ActiveTaskAssetView(
    val taskId: String,
    val id: String,
    val displayName: String,
    val privatePath: String,
    val mimeType: String,
    val sha256: String,
    val byteSize: Long,
    val position: Int,
)

@Dao
interface ExecutorDao {
    @Query("SELECT * FROM candidates ORDER BY createdAtEpochMs DESC")
    fun observeCandidates(): Flow<List<CandidateEntity>>

    @Query("SELECT * FROM candidates WHERE id = :id LIMIT 1")
    suspend fun getCandidate(id: String): CandidateEntity?

    @Query("SELECT * FROM candidates WHERE externalUserId = :externalUserId LIMIT 1")
    suspend fun getCandidateByExternalId(externalUserId: String): CandidateEntity?

    @Query("SELECT * FROM candidates ORDER BY createdAtEpochMs ASC")
    suspend fun getAllCandidates(): List<CandidateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCandidate(candidate: CandidateEntity)

    @Query("UPDATE candidates SET state = :state, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateCandidateState(id: String, state: String, now: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE candidates
        SET relationshipStage = :stage,
            contactEligibility = :eligibility,
            lastInteractionAtEpochMs = :lastInteractionAt,
            priorityScore = :priorityScore,
            manualPriorityConfirmed = :manualPriorityConfirmed,
            doNotContactReason = :doNotContactReason,
            updatedAtEpochMs = :now
        WHERE id = :id
        """,
    )
    suspend fun updateCandidateRelationship(
        id: String,
        stage: String,
        eligibility: String,
        lastInteractionAt: Long?,
        priorityScore: Int,
        manualPriorityConfirmed: Boolean,
        doNotContactReason: String?,
        now: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        UPDATE candidates
        SET lastFollowUpAtEpochMs = :lastFollowUpAt,
            nextFollowUpAtEpochMs = :nextFollowUpAt,
            updatedAtEpochMs = :now
        WHERE id = :id
        """,
    )
    suspend fun updateCandidateFollowUp(
        id: String,
        lastFollowUpAt: Long?,
        nextFollowUpAt: Long?,
        now: Long = System.currentTimeMillis(),
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInteractionEvent(event: InteractionEventEntity)

    @Query("SELECT * FROM interaction_events ORDER BY occurredAtEpochMs DESC")
    fun observeInteractionEvents(): Flow<List<InteractionEventEntity>>

    @Query("SELECT * FROM interaction_events ORDER BY occurredAtEpochMs DESC")
    suspend fun getAllInteractionEvents(): List<InteractionEventEntity>

    @Query("SELECT * FROM interaction_events WHERE candidateId = :candidateId ORDER BY occurredAtEpochMs DESC")
    suspend fun getInteractionEvents(candidateId: String): List<InteractionEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStageHistory(row: RelationshipStageHistoryEntity)

    @Query("SELECT * FROM relationship_stage_history ORDER BY changedAtEpochMs DESC")
    suspend fun getAllStageHistory(): List<RelationshipStageHistoryEntity>

    @Query(
        """
        SELECT COUNT(*) FROM send_history
        WHERE externalUserId = :externalUserId AND sentAtEpochMs >= :sinceEpochMs
        """,
    )
    suspend fun countSuccessfulSendsSinceForUser(externalUserId: String, sinceEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM send_history WHERE sentAtEpochMs >= :sinceEpochMs")
    suspend fun countSuccessfulSendsSince(sinceEpochMs: Long): Int

    @Query(
        """
        SELECT * FROM tasks
        WHERE state IN ('presented', 'approved', 'executing')
        ORDER BY createdAtEpochMs ASC
        """,
    )
    fun observeActiveTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY createdAtEpochMs DESC")
    fun observeAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY createdAtEpochMs ASC")
    suspend fun getAllTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTask(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE runSessionId = :sessionId ORDER BY createdAtEpochMs ASC")
    suspend fun getTasksForSession(sessionId: String): List<TaskEntity>

    @Query(
        """
        SELECT * FROM tasks
        WHERE runSessionId = :sessionId AND state = 'queued'
        ORDER BY createdAtEpochMs ASC
        LIMIT 1
        """,
    )
    suspend fun nextQueuedTask(sessionId: String): TaskEntity?

    @Query(
        """
        SELECT COUNT(*) FROM tasks
        WHERE runSessionId = :sessionId AND state IN ('presented', 'approved', 'executing')
        """,
    )
    suspend fun countInFlightTasks(sessionId: String): Int

    @Query(
        """
        SELECT * FROM tasks
        WHERE state = 'approved'
        ORDER BY approvedAtEpochMs ASC
        LIMIT 1
        """,
    )
    suspend fun nextApprovedTask(): TaskEntity?

    @Query(
        """
        SELECT COUNT(*) FROM tasks
        WHERE externalUserId = :externalUserId
          AND state IN ('queued', 'presented', 'approved', 'executing')
        """,
    )
    suspend fun countTasksForUser(externalUserId: String): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE state IN ('presented', 'approved', 'executing')")
    suspend fun countGlobalInFlightTasks(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Query(
        """
        UPDATE tasks
        SET state = :state,
            approvedAtEpochMs = :approvedAt,
            approvalExpiresAtEpochMs = :expiresAt,
            finalConfirmedAtEpochMs = :finalConfirmedAt,
            verificationSummary = :verificationSummary,
            failureReason = :failureReason,
            failureCode = :failureCode,
            updatedAtEpochMs = :now
        WHERE id = :taskId
        """,
    )
    suspend fun updateTaskState(
        taskId: String,
        state: String,
        approvedAt: Long? = null,
        expiresAt: Long? = null,
        finalConfirmedAt: Long? = null,
        verificationSummary: String? = null,
        failureReason: String? = null,
        failureCode: String? = null,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE tasks SET messageText = :message, updatedAtEpochMs = :now WHERE id = :taskId AND state IN ('queued', 'presented')")
    suspend fun updateTaskMessage(taskId: String, message: String, now: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskAssets(rows: List<TaskAssetEntity>)

    @Query("DELETE FROM task_assets WHERE taskId = :taskId")
    suspend fun deleteTaskAssets(taskId: String)

    @Query(
        """
        UPDATE tasks
        SET imageCount = :imageCount,
            imagePolicy = :imagePolicy,
            updatedAtEpochMs = :now
        WHERE id = :taskId
        """,
    )
    suspend fun updateTaskAssetMetadata(
        taskId: String,
        imageCount: Int,
        imagePolicy: String,
        now: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        SELECT ta.taskId, a.id, a.displayName, a.privatePath, a.mimeType,
               a.sha256, a.byteSize, ta.position
        FROM task_assets ta
        JOIN media_assets a ON a.id = ta.assetId
        JOIN tasks t ON t.id = ta.taskId
        WHERE t.state IN ('queued', 'presented', 'approved', 'executing')
        ORDER BY t.createdAtEpochMs ASC, ta.position ASC
        """,
    )
    fun observeActiveTaskAssets(): Flow<List<ActiveTaskAssetView>>

    @Query(
        """
        SELECT a.id, a.displayName, a.privatePath, a.mimeType, a.sha256, a.byteSize, ta.position
        FROM task_assets ta
        JOIN media_assets a ON a.id = ta.assetId
        WHERE ta.taskId = :taskId
        ORDER BY ta.position ASC
        """,
    )
    suspend fun getTaskAssets(taskId: String): List<TaskAssetView>

    @Query("SELECT * FROM task_assets ORDER BY taskId, position")
    suspend fun getAllTaskAssets(): List<TaskAssetEntity>

    @Transaction
    suspend fun replaceTaskAssets(
        taskId: String,
        assetIds: List<String>,
        imagePolicy: String,
    ) {
        val task = getTask(taskId) ?: error("任务不存在")
        check(task.state == "presented") { "只有待批准任务可以更换图片" }
        deleteTaskAssets(taskId)
        if (assetIds.isNotEmpty()) {
            insertTaskAssets(
                assetIds.mapIndexed { index, assetId ->
                    TaskAssetEntity(taskId = taskId, assetId = assetId, position = index)
                },
            )
        }
        updateTaskAssetMetadata(taskId, assetIds.size, imagePolicy)
    }

    @Transaction
    @Query("SELECT * FROM message_templates ORDER BY createdAtEpochMs ASC")
    fun observeTemplates(): Flow<List<TemplateWithLines>>

    @Transaction
    @Query("SELECT * FROM message_templates WHERE id = :id LIMIT 1")
    suspend fun getTemplate(id: String): TemplateWithLines?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(template: MessageTemplateEntity)

    @Query("DELETE FROM template_lines WHERE templateId = :templateId")
    suspend fun deleteTemplateLines(templateId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplateLines(lines: List<TemplateLineEntity>)

    @Query("DELETE FROM message_templates WHERE id = :id")
    suspend fun deleteTemplate(id: String)

    @Query("SELECT * FROM message_templates ORDER BY createdAtEpochMs ASC")
    suspend fun getAllTemplates(): List<MessageTemplateEntity>

    @Query("SELECT * FROM template_lines ORDER BY templateId, position")
    suspend fun getAllTemplateLines(): List<TemplateLineEntity>

    @Query("SELECT * FROM media_assets ORDER BY createdAtEpochMs DESC")
    fun observeAssets(): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets")
    suspend fun getAllAssets(): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getAssetByHash(sha256: String): MediaAssetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAsset(asset: MediaAssetEntity)

    @Query("SELECT COUNT(*) FROM task_assets WHERE assetId = :assetId")
    suspend fun countAssetReferences(assetId: String): Int

    @Query("DELETE FROM media_assets WHERE id = :id")
    suspend fun deleteAsset(id: String)

    @Query("SELECT * FROM blacklist ORDER BY createdAtEpochMs DESC")
    fun observeBlacklist(): Flow<List<BlacklistEntity>>

    @Query("SELECT COUNT(*) FROM blacklist WHERE externalUserId = :externalUserId")
    suspend fun countBlacklisted(externalUserId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlacklist(row: BlacklistEntity)

    @Query("DELETE FROM blacklist WHERE externalUserId = :externalUserId")
    suspend fun removeBlacklist(externalUserId: String)

    @Query("SELECT * FROM blacklist ORDER BY createdAtEpochMs ASC")
    suspend fun getAllBlacklist(): List<BlacklistEntity>

    @Query("SELECT * FROM send_history ORDER BY sentAtEpochMs DESC")
    fun observeHistory(): Flow<List<SendHistoryEntity>>

    @Query("SELECT * FROM send_history ORDER BY sentAtEpochMs DESC")
    suspend fun getHistory(): List<SendHistoryEntity>

    @Query("SELECT COUNT(*) FROM send_history WHERE externalUserId = :externalUserId")
    suspend fun countSuccessfulSends(externalUserId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistory(row: SendHistoryEntity)

    @Query("DELETE FROM send_history")
    suspend fun clearHistory()

    @Query("SELECT * FROM run_logs ORDER BY id DESC LIMIT :limit")
    fun observeLogs(limit: Int = 500): Flow<List<RunLogEntity>>

    @Query("SELECT * FROM run_logs ORDER BY id DESC")
    suspend fun getLogs(): List<RunLogEntity>

    @Insert
    suspend fun insertLog(row: RunLogEntity)

    @Query("DELETE FROM run_logs")
    suspend fun clearLogs()

    @Query("SELECT * FROM automation_profiles ORDER BY isDefault DESC, createdAtEpochMs ASC")
    fun observeProfiles(): Flow<List<AutomationProfileEntity>>

    @Query("SELECT * FROM automation_profiles ORDER BY isDefault DESC, createdAtEpochMs ASC")
    suspend fun getAllProfiles(): List<AutomationProfileEntity>

    @Query("SELECT * FROM automation_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfile(id: String): AutomationProfileEntity?

    @Query("SELECT * FROM automation_profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultProfile(): AutomationProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: AutomationProfileEntity)

    @Query("UPDATE automation_profiles SET isDefault = CASE WHEN id = :profileId THEN 1 ELSE 0 END")
    suspend fun setDefaultProfile(profileId: String)

    @Query("DELETE FROM automation_profiles WHERE id = :id AND isDefault = 0")
    suspend fun deleteProfile(id: String)

    @Query(
        """
        SELECT a.id, a.displayName, a.privatePath, a.mimeType, a.sha256, a.byteSize, pa.position
        FROM profile_assets pa
        JOIN media_assets a ON a.id = pa.assetId
        WHERE pa.profileId = :profileId
        ORDER BY pa.position ASC
        """,
    )
    suspend fun getProfileAssets(profileId: String): List<TaskAssetView>

    @Query("SELECT * FROM profile_assets ORDER BY profileId, position")
    suspend fun getAllProfileAssetRows(): List<ProfileAssetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfileAssets(rows: List<ProfileAssetEntity>)

    @Query("DELETE FROM profile_assets WHERE profileId = :profileId")
    suspend fun deleteProfileAssets(profileId: String)

    @Transaction
    suspend fun replaceProfileAssets(profileId: String, assetIds: List<String>) {
        deleteProfileAssets(profileId)
        if (assetIds.isNotEmpty()) {
            insertProfileAssets(
                assetIds.mapIndexed { index, assetId ->
                    ProfileAssetEntity(profileId, assetId, index)
                },
            )
        }
    }

    @Query("SELECT * FROM block_rules ORDER BY createdAtEpochMs DESC")
    fun observeBlockRules(): Flow<List<BlockRuleEntity>>

    @Query("SELECT * FROM block_rules ORDER BY createdAtEpochMs ASC")
    suspend fun getAllBlockRules(): List<BlockRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlockRule(rule: BlockRuleEntity)

    @Query("DELETE FROM block_rules WHERE id = :id")
    suspend fun deleteBlockRule(id: String)

    @Query("UPDATE block_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setBlockRuleEnabled(id: String, enabled: Boolean)

    @Query("SELECT * FROM run_sessions ORDER BY createdAtEpochMs DESC")
    fun observeRunSessions(): Flow<List<RunSessionEntity>>

    @Query(
        """
        SELECT * FROM run_sessions
        WHERE state IN ('draft', 'running', 'paused')
        ORDER BY createdAtEpochMs DESC
        LIMIT 1
        """,
    )
    suspend fun getActiveRunSession(): RunSessionEntity?

    @Query("SELECT * FROM run_sessions WHERE id = :id LIMIT 1")
    suspend fun getRunSession(id: String): RunSessionEntity?

    @Query("SELECT * FROM run_sessions ORDER BY createdAtEpochMs ASC")
    suspend fun getAllRunSessions(): List<RunSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunSession(session: RunSessionEntity)

    @Query(
        """
        UPDATE run_sessions
        SET state = :state,
            startedAtEpochMs = COALESCE(startedAtEpochMs, :startedAt),
            pausedAtEpochMs = :pausedAt,
            completedAtEpochMs = :completedAt
        WHERE id = :sessionId
        """,
    )
    suspend fun updateRunSessionState(
        sessionId: String,
        state: String,
        startedAt: Long? = null,
        pausedAt: Long? = null,
        completedAt: Long? = null,
    )

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: AppSettingsEntity)

    @Query("SELECT * FROM cloud_config WHERE id = 1 LIMIT 1")
    fun observeCloudConfig(): Flow<CloudConfigEntity?>

    @Query("SELECT * FROM cloud_config WHERE id = 1 LIMIT 1")
    suspend fun getCloudConfig(): CloudConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCloudConfig(config: CloudConfigEntity)

    @Query("SELECT * FROM cloud_tasks ORDER BY priority DESC, updatedAtEpochMs DESC")
    fun observeCloudTasks(): Flow<List<CloudTaskEntity>>

    @Query("SELECT * FROM cloud_tasks WHERE id = :id LIMIT 1")
    suspend fun getCloudTask(id: String): CloudTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCloudTasks(tasks: List<CloudTaskEntity>)

    @Query("DELETE FROM cloud_tasks")
    suspend fun clearCloudTasks()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueCloudRequest(request: CloudOutboxEntity)

    @Query("SELECT * FROM cloud_outbox ORDER BY createdAtEpochMs ASC")
    suspend fun getCloudOutbox(): List<CloudOutboxEntity>

    @Query("DELETE FROM cloud_outbox WHERE id = :id")
    suspend fun deleteCloudOutbox(id: String)

    @Query(
        """
        UPDATE cloud_outbox
        SET attemptCount = attemptCount + 1, lastError = :error
        WHERE id = :id
        """,
    )
    suspend fun markCloudOutboxFailed(id: String, error: String)

    @Query("DELETE FROM task_assets")
    suspend fun clearTaskAssets()

    @Query("DELETE FROM profile_assets")
    suspend fun clearProfileAssets()

    @Query("DELETE FROM send_history")
    suspend fun clearAllHistory()

    @Query("DELETE FROM run_logs")
    suspend fun clearAllLogs()

    @Query("DELETE FROM tasks")
    suspend fun clearTasks()

    @Query("DELETE FROM run_sessions")
    suspend fun clearRunSessions()

    @Query("DELETE FROM block_rules")
    suspend fun clearBlockRules()

    @Query("DELETE FROM blacklist")
    suspend fun clearBlacklist()

    @Query("DELETE FROM candidates")
    suspend fun clearCandidates()

    @Query("DELETE FROM interaction_events")
    suspend fun clearInteractionEvents()

    @Query("DELETE FROM relationship_stage_history")
    suspend fun clearStageHistory()

    @Query("DELETE FROM template_lines")
    suspend fun clearTemplateLines()

    @Query("DELETE FROM automation_profiles")
    suspend fun clearProfiles()

    @Query("DELETE FROM message_templates")
    suspend fun clearTemplates()

    @Query("DELETE FROM media_assets")
    suspend fun clearAssets()

    @Transaction
    suspend fun clearForRestore() {
        clearTaskAssets()
        clearProfileAssets()
        clearAllHistory()
        clearAllLogs()
        clearTasks()
        clearRunSessions()
        clearBlockRules()
        clearBlacklist()
        clearInteractionEvents()
        clearStageHistory()
        clearCandidates()
        clearTemplateLines()
        clearProfiles()
        clearTemplates()
        clearAssets()
    }

    @Transaction
    suspend fun replaceTemplate(template: MessageTemplateEntity, lines: List<TemplateLineEntity>) {
        upsertTemplate(template)
        deleteTemplateLines(template.id)
        insertTemplateLines(lines)
    }
}

@Database(
    entities = [
        CandidateEntity::class,
        MessageTemplateEntity::class,
        TemplateLineEntity::class,
        MediaAssetEntity::class,
        TaskEntity::class,
        TaskAssetEntity::class,
        BlacklistEntity::class,
        SendHistoryEntity::class,
        RunLogEntity::class,
        AppSettingsEntity::class,
        AutomationProfileEntity::class,
        ProfileAssetEntity::class,
        BlockRuleEntity::class,
        RunSessionEntity::class,
        InteractionEventEntity::class,
        RelationshipStageHistoryEntity::class,
        CloudConfigEntity::class,
        CloudTaskEntity::class,
        CloudOutboxEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class ExecutorDatabase : RoomDatabase() {
    abstract fun dao(): ExecutorDao

    companion object {
        fun create(context: Context): ExecutorDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ExecutorDatabase::class.java,
                "executor.db",
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                )
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS standalone_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        externalUserId TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        note TEXT NOT NULL,
                        state TEXT NOT NULL,
                        messageText TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        approvedAtEpochMs INTEGER,
                        approvalExpiresAtEpochMs INTEGER,
                        failureReason TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS standalone_settings (
                        id INTEGER NOT NULL PRIMARY KEY,
                        templateLines TEXT NOT NULL,
                        randomize INTEGER NOT NULL,
                        hourlyLimit INTEGER NOT NULL,
                        dailyLimit INTEGER NOT NULL,
                        approvalTtlSeconds INTEGER NOT NULL,
                        minDelaySeconds INTEGER NOT NULL,
                        maxDelaySeconds INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS standalone_send_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId TEXT NOT NULL,
                        externalUserId TEXT NOT NULL,
                        sentAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createV3Tables(db)
                val now = System.currentTimeMillis()
                val defaultTemplateId = "migrated-default"
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO message_templates
                    (id, name, randomize, enabled, createdAtEpochMs, updatedAtEpochMs)
                    SELECT '$defaultTemplateId', '默认话术', randomize, 1, $now, $now
                    FROM standalone_settings WHERE id = 1
                    """.trimIndent(),
                )
                val cursor = db.query("SELECT templateLines FROM standalone_settings WHERE id = 1")
                cursor.use {
                    if (it.moveToFirst()) {
                        it.getString(0).lineSequence()
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .forEachIndexed { index, content ->
                                db.execSQL(
                                    "INSERT INTO template_lines (id, templateId, content, position) VALUES (?, ?, ?, ?)",
                                    arrayOf<Any?>("migrated-line-$index", defaultTemplateId, content, index),
                                )
                            }
                    }
                }
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO candidates
                    (id, externalUserId, displayName, note, source, gender, consumption, templateId,
                     imageCount, state, createdAtEpochMs, updatedAtEpochMs)
                    SELECT 'migrated-candidate-' || id, externalUserId, displayName, note, 'manual',
                           NULL, NULL, '$defaultTemplateId', 0,
                           CASE WHEN state = 'sent' THEN 'completed' ELSE 'tasked' END,
                           createdAtEpochMs, updatedAtEpochMs
                    FROM standalone_tasks
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO tasks
                    (id, candidateId, externalUserId, displayName, note, source, gender, consumption,
                     templateId, messageText, imageCount, imagePolicy, state, approvedAtEpochMs,
                     approvalExpiresAtEpochMs, finalConfirmedAtEpochMs, verificationSummary,
                     failureReason, createdAtEpochMs, updatedAtEpochMs)
                    SELECT id, 'migrated-candidate-' || id, externalUserId, displayName, note, 'manual',
                           NULL, NULL, '$defaultTemplateId', messageText, 0, 'random',
                           CASE WHEN state = 'approved' OR state = 'executing' THEN 'presented' ELSE state END,
                           NULL, NULL, NULL, NULL, failureReason, createdAtEpochMs, updatedAtEpochMs
                    FROM standalone_tasks
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO send_history
                    (taskId, externalUserId, displayName, messageText, imageCount, sentAtEpochMs)
                    SELECT h.taskId, h.externalUserId,
                           COALESCE(t.displayName, h.externalUserId),
                           COALESCE(t.messageText, ''), 0, h.sentAtEpochMs
                    FROM standalone_send_history h
                    LEFT JOIN standalone_tasks t ON t.id = h.taskId
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO app_settings
                    (id, approvalTtlSeconds, minDelaySeconds, maxDelaySeconds,
                     deduplicateSuccessfulUsers, clearPublishedImagesAfterSend)
                    SELECT 1, approvalTtlSeconds,
                           CASE WHEN minDelaySeconds > 3 THEN 0 ELSE minDelaySeconds END,
                           CASE WHEN maxDelaySeconds > 3 THEN 3 ELSE maxDelaySeconds END,
                           1, 1
                    FROM standalone_settings WHERE id = 1
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO run_logs (level, type, message, taskId, createdAtEpochMs)
                    SELECT level, type, message, NULL, createdAtEpochMs FROM local_events
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE IF EXISTS cached_tasks")
                db.execSQL("DROP TABLE IF EXISTS local_events")
                db.execSQL("DROP TABLE IF EXISTS standalone_tasks")
                db.execSQL("DROP TABLE IF EXISTS standalone_settings")
                db.execSQL("DROP TABLE IF EXISTS standalone_send_history")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN runSessionId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN retryOfTaskId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN attemptNumber INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN failureCode TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN minDelaySeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN maxDelaySeconds INTEGER NOT NULL DEFAULT 3")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_runSessionId ON tasks(runSessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_retryOfTaskId ON tasks(retryOfTaskId)")

                db.execSQL("ALTER TABLE app_settings ADD COLUMN onboardingCompleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "ALTER TABLE app_settings ADD COLUMN officialAutoNavigationEnabled INTEGER NOT NULL DEFAULT 0",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS automation_profiles (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        templateId TEXT,
                        genderFilter TEXT NOT NULL,
                        minConsumption INTEGER,
                        maxConsumption INTEGER,
                        sourceFilter TEXT NOT NULL,
                        unknownFieldPolicy TEXT NOT NULL,
                        imagePolicy TEXT NOT NULL,
                        imageCount INTEGER NOT NULL,
                        minDelaySeconds INTEGER NOT NULL,
                        maxDelaySeconds INTEGER NOT NULL,
                        deduplicateSuccessfulUsers INTEGER NOT NULL,
                        isDefault INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_automation_profiles_name ON automation_profiles(name)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_automation_profiles_isDefault ON automation_profiles(isDefault)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS profile_assets (
                        profileId TEXT NOT NULL,
                        assetId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(profileId, assetId),
                        FOREIGN KEY(profileId) REFERENCES automation_profiles(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(assetId) REFERENCES media_assets(id)
                            ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_profile_assets_profileId ON profile_assets(profileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_profile_assets_assetId ON profile_assets(assetId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS block_rules (
                        id TEXT NOT NULL PRIMARY KEY,
                        type TEXT NOT NULL,
                        value TEXT NOT NULL,
                        normalizedValue TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_block_rules_type_normalizedValue ON block_rules(type, normalizedValue)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_block_rules_enabled ON block_rules(enabled)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS run_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        profileId TEXT,
                        profileName TEXT NOT NULL,
                        state TEXT NOT NULL,
                        totalCount INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        startedAtEpochMs INTEGER,
                        pausedAtEpochMs INTEGER,
                        completedAtEpochMs INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_run_sessions_profileId ON run_sessions(profileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_run_sessions_state ON run_sessions(state)")

                val now = System.currentTimeMillis()
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO automation_profiles
                    (id, name, templateId, genderFilter, minConsumption, maxConsumption,
                     sourceFilter, unknownFieldPolicy, imagePolicy, imageCount,
                     minDelaySeconds, maxDelaySeconds, deduplicateSuccessfulUsers,
                     isDefault, createdAtEpochMs, updatedAtEpochMs)
                    SELECT 'default-profile', '默认预设',
                           (SELECT id FROM message_templates WHERE enabled = 1
                            ORDER BY createdAtEpochMs ASC LIMIT 1),
                           'all', NULL, NULL, 'all', 'retain', 'none', 0,
                           minDelaySeconds, maxDelaySeconds, deduplicateSuccessfulUsers,
                           1, $now, $now
                    FROM app_settings WHERE id = 1
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE candidates ADD COLUMN platform TEXT NOT NULL DEFAULT 'ingkee'")
                db.execSQL(
                    "ALTER TABLE candidates ADD COLUMN relationshipStage TEXT NOT NULL DEFAULT 'new_interaction'",
                )
                db.execSQL(
                    "ALTER TABLE candidates ADD COLUMN contactEligibility TEXT NOT NULL DEFAULT 'manual_confirmed'",
                )
                db.execSQL("ALTER TABLE candidates ADD COLUMN lastInteractionAtEpochMs INTEGER")
                db.execSQL("ALTER TABLE candidates ADD COLUMN lastFollowUpAtEpochMs INTEGER")
                db.execSQL("ALTER TABLE candidates ADD COLUMN nextFollowUpAtEpochMs INTEGER")
                db.execSQL("ALTER TABLE candidates ADD COLUMN priorityScore INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE candidates ADD COLUMN doNotContactReason TEXT")
                db.execSQL(
                    "ALTER TABLE candidates ADD COLUMN manualPriorityConfirmed INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("UPDATE candidates SET platform = 'legacy', contactEligibility = 'ineligible'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_candidates_platform ON candidates(platform)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_candidates_relationshipStage ON candidates(relationshipStage)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_candidates_priorityScore ON candidates(priorityScore)",
                )

                db.execSQL("ALTER TABLE app_settings ADD COLUMN dailyContactLimit INTEGER NOT NULL DEFAULT 20")
                db.execSQL(
                    "ALTER TABLE app_settings ADD COLUMN perUserSevenDayLimit INTEGER NOT NULL DEFAULT 2",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS interaction_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        candidateId TEXT NOT NULL,
                        externalUserId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        source TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        occurredAtEpochMs INTEGER NOT NULL,
                        FOREIGN KEY(candidateId) REFERENCES candidates(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_interaction_events_candidateId ON interaction_events(candidateId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_interaction_events_type ON interaction_events(type)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_interaction_events_occurredAtEpochMs ON interaction_events(occurredAtEpochMs)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS relationship_stage_history (
                        id TEXT NOT NULL PRIMARY KEY,
                        candidateId TEXT NOT NULL,
                        fromStage TEXT NOT NULL,
                        toStage TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        changedAtEpochMs INTEGER NOT NULL,
                        FOREIGN KEY(candidateId) REFERENCES candidates(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_relationship_stage_history_candidateId ON relationship_stage_history(candidateId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_relationship_stage_history_changedAtEpochMs ON relationship_stage_history(changedAtEpochMs)",
                )
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cloud_config (
                        id INTEGER NOT NULL PRIMARY KEY,
                        baseUrl TEXT NOT NULL,
                        username TEXT NOT NULL,
                        accessToken TEXT NOT NULL,
                        accountId TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        role TEXT NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cloud_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        roomId TEXT,
                        customerId TEXT NOT NULL,
                        customerName TEXT NOT NULL,
                        title TEXT NOT NULL,
                        brief TEXT NOT NULL,
                        state TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        assignedAccountId TEXT,
                        resultChannel TEXT,
                        resultNote TEXT,
                        nextFollowUpAtEpochMs INTEGER,
                        valueLevel TEXT NOT NULL,
                        visibleRevenueCents INTEGER,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_tasks_state ON cloud_tasks(state)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cloud_tasks_customerId ON cloud_tasks(customerId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cloud_tasks_updatedAtEpochMs ON cloud_tasks(updatedAtEpochMs)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cloud_outbox (
                        id TEXT NOT NULL PRIMARY KEY,
                        method TEXT NOT NULL,
                        path TEXT NOT NULL,
                        bodyJson TEXT NOT NULL,
                        dedupeKey TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        lastError TEXT,
                        createdAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cloud_outbox_createdAtEpochMs ON cloud_outbox(createdAtEpochMs)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_cloud_outbox_dedupeKey ON cloud_outbox(dedupeKey)",
                )
            }
        }

        private fun createV3Tables(db: SupportSQLiteDatabase) {
            val statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS candidates (
                    id TEXT NOT NULL PRIMARY KEY, externalUserId TEXT NOT NULL, displayName TEXT NOT NULL,
                    note TEXT NOT NULL, source TEXT NOT NULL, gender TEXT, consumption INTEGER,
                    templateId TEXT, imageCount INTEGER NOT NULL, state TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL
                )
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS index_candidates_externalUserId ON candidates(externalUserId)",
                """
                CREATE TABLE IF NOT EXISTS message_templates (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, randomize INTEGER NOT NULL,
                    enabled INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS template_lines (
                    id TEXT NOT NULL PRIMARY KEY, templateId TEXT NOT NULL, content TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    FOREIGN KEY(templateId) REFERENCES message_templates(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """,
                "CREATE INDEX IF NOT EXISTS index_template_lines_templateId ON template_lines(templateId)",
                """
                CREATE TABLE IF NOT EXISTS media_assets (
                    id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, privatePath TEXT NOT NULL,
                    mimeType TEXT NOT NULL, sha256 TEXT NOT NULL, byteSize INTEGER NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL
                )
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS index_media_assets_sha256 ON media_assets(sha256)",
                """
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT NOT NULL PRIMARY KEY, candidateId TEXT NOT NULL, externalUserId TEXT NOT NULL,
                    displayName TEXT NOT NULL, note TEXT NOT NULL, source TEXT NOT NULL, gender TEXT,
                    consumption INTEGER, templateId TEXT, messageText TEXT NOT NULL, imageCount INTEGER NOT NULL,
                    imagePolicy TEXT NOT NULL, state TEXT NOT NULL, approvedAtEpochMs INTEGER,
                    approvalExpiresAtEpochMs INTEGER, finalConfirmedAtEpochMs INTEGER,
                    verificationSummary TEXT, failureReason TEXT, createdAtEpochMs INTEGER NOT NULL,
                    updatedAtEpochMs INTEGER NOT NULL,
                    FOREIGN KEY(candidateId) REFERENCES candidates(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """,
                "CREATE INDEX IF NOT EXISTS index_tasks_candidateId ON tasks(candidateId)",
                "CREATE INDEX IF NOT EXISTS index_tasks_externalUserId ON tasks(externalUserId)",
                "CREATE INDEX IF NOT EXISTS index_tasks_state ON tasks(state)",
                """
                CREATE TABLE IF NOT EXISTS task_assets (
                    taskId TEXT NOT NULL, assetId TEXT NOT NULL, position INTEGER NOT NULL,
                    PRIMARY KEY(taskId, assetId),
                    FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(assetId) REFERENCES media_assets(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """,
                "CREATE INDEX IF NOT EXISTS index_task_assets_taskId ON task_assets(taskId)",
                "CREATE INDEX IF NOT EXISTS index_task_assets_assetId ON task_assets(assetId)",
                """
                CREATE TABLE IF NOT EXISTS blacklist (
                    externalUserId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL,
                    reason TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS send_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, taskId TEXT NOT NULL,
                    externalUserId TEXT NOT NULL, displayName TEXT NOT NULL, messageText TEXT NOT NULL,
                    imageCount INTEGER NOT NULL, sentAtEpochMs INTEGER NOT NULL
                )
                """,
                "CREATE INDEX IF NOT EXISTS index_send_history_externalUserId ON send_history(externalUserId)",
                "CREATE UNIQUE INDEX IF NOT EXISTS index_send_history_taskId ON send_history(taskId)",
                """
                CREATE TABLE IF NOT EXISTS run_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, level TEXT NOT NULL, type TEXT NOT NULL,
                    message TEXT NOT NULL, taskId TEXT, createdAtEpochMs INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS app_settings (
                    id INTEGER NOT NULL PRIMARY KEY, approvalTtlSeconds INTEGER NOT NULL,
                    minDelaySeconds INTEGER NOT NULL, maxDelaySeconds INTEGER NOT NULL,
                    deduplicateSuccessfulUsers INTEGER NOT NULL,
                    clearPublishedImagesAfterSend INTEGER NOT NULL
                )
                """,
            )
            statements.forEach { db.execSQL(it.trimIndent()) }
        }
    }
}
