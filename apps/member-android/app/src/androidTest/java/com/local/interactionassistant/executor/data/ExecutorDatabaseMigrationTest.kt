package com.local.interactionassistant.executor.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExecutorDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ExecutorDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrateTwoToThreePreservesStandaloneData() {
        helper.createDatabase(DB_NAME, 2).apply {
            execSQL(
                """
                INSERT INTO standalone_settings
                (id, templateLines, randomize, hourlyLimit, dailyLimit, approvalTtlSeconds,
                 minDelaySeconds, maxDelaySeconds)
                VALUES (1, '第一条
                第二条', 1, 20, 80, 60, 3, 8)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO standalone_tasks
                (id, externalUserId, displayName, note, state, messageText, createdAtEpochMs,
                 updatedAtEpochMs, approvedAtEpochMs, approvalExpiresAtEpochMs, failureReason)
                VALUES ('task-1', 'user-1', '测试用户', '备注', 'sent', '你好', 100, 200, NULL, NULL, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO standalone_send_history
                (taskId, externalUserId, sentAtEpochMs)
                VALUES ('task-1', 'user-1', 300)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            3,
            true,
            ExecutorDatabase.MIGRATION_2_3,
        )
        db.query("SELECT COUNT(*) FROM candidates").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT messageText, state FROM tasks WHERE id = 'task-1'").use {
            it.moveToFirst()
            assertEquals("你好", it.getString(0))
            assertEquals("sent", it.getString(1))
        }
        db.query("SELECT COUNT(*) FROM send_history").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT minDelaySeconds, maxDelaySeconds FROM app_settings WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
            assertEquals(3, it.getInt(1))
        }
        db.close()
    }

    @Test
    fun migrateThreeToFourPreservesDataAndCreatesDefaultProfile() {
        helper.createDatabase(DB_NAME_V3, 3).apply {
            execSQL(
                """
                INSERT INTO message_templates
                (id, name, randomize, enabled, createdAtEpochMs, updatedAtEpochMs)
                VALUES ('template-1', '默认话术', 1, 1, 100, 100)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO candidates
                (id, externalUserId, displayName, note, source, gender, consumption, templateId,
                 imageCount, state, createdAtEpochMs, updatedAtEpochMs)
                VALUES ('candidate-1', 'user-1', '测试用户', '', 'manual', NULL, NULL,
                        'template-1', 0, 'tasked', 100, 100)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO tasks
                (id, candidateId, externalUserId, displayName, note, source, gender, consumption,
                 templateId, messageText, imageCount, imagePolicy, state, approvedAtEpochMs,
                 approvalExpiresAtEpochMs, finalConfirmedAtEpochMs, verificationSummary,
                 failureReason, createdAtEpochMs, updatedAtEpochMs)
                VALUES ('task-1', 'candidate-1', 'user-1', '测试用户', '', 'manual', NULL, NULL,
                        'template-1', '你好', 0, 'none', 'failed', NULL, NULL, NULL, NULL,
                        '旧失败', 100, 200)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO app_settings
                (id, approvalTtlSeconds, minDelaySeconds, maxDelaySeconds,
                 deduplicateSuccessfulUsers, clearPublishedImagesAfterSend)
                VALUES (1, 60, 1, 2, 1, 1)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME_V3,
            4,
            true,
            ExecutorDatabase.MIGRATION_3_4,
        )
        db.query(
            """
            SELECT runSessionId, retryOfTaskId, attemptNumber, failureCode,
                   minDelaySeconds, maxDelaySeconds
            FROM tasks WHERE id = 'task-1'
            """.trimIndent(),
        ).use {
            it.moveToFirst()
            assertNull(it.getString(0))
            assertNull(it.getString(1))
            assertEquals(1, it.getInt(2))
            assertNull(it.getString(3))
            assertEquals(0, it.getInt(4))
            assertEquals(3, it.getInt(5))
        }
        db.query(
            """
            SELECT name, minDelaySeconds, maxDelaySeconds, deduplicateSuccessfulUsers,
                   isDefault
            FROM automation_profiles WHERE id = 'default-profile'
            """.trimIndent(),
        ).use {
            it.moveToFirst()
            assertEquals("默认预设", it.getString(0))
            assertEquals(1, it.getInt(1))
            assertEquals(2, it.getInt(2))
            assertEquals(1, it.getInt(3))
            assertEquals(1, it.getInt(4))
        }
        db.close()
    }

    @Test
    fun migrateFourToFivePreservesDataAndMarksLegacyPlatform() {
        helper.createDatabase(DB_NAME_V4, 4).apply {
            execSQL(
                """
                INSERT INTO candidates
                (id, externalUserId, displayName, note, source, gender, consumption, templateId,
                 imageCount, state, createdAtEpochMs, updatedAtEpochMs)
                VALUES ('candidate-1', 'legacy-user', '旧用户', '', 'manual', NULL, NULL,
                        NULL, 0, 'available', 100, 100)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO app_settings
                (id, approvalTtlSeconds, minDelaySeconds, maxDelaySeconds,
                 deduplicateSuccessfulUsers, clearPublishedImagesAfterSend,
                 onboardingCompleted, officialAutoNavigationEnabled)
                VALUES (1, 60, 0, 3, 1, 1, 1, 0)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME_V4,
            5,
            true,
            ExecutorDatabase.MIGRATION_4_5,
        )
        db.query(
            """
            SELECT platform, relationshipStage, contactEligibility, priorityScore
            FROM candidates WHERE id = 'candidate-1'
            """.trimIndent(),
        ).use {
            it.moveToFirst()
            assertEquals("legacy", it.getString(0))
            assertEquals("new_interaction", it.getString(1))
            assertEquals("ineligible", it.getString(2))
            assertEquals(0, it.getInt(3))
        }
        db.query("SELECT dailyContactLimit, perUserSevenDayLimit FROM app_settings WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(20, it.getInt(0))
            assertEquals(2, it.getInt(1))
        }
        db.query("SELECT COUNT(*) FROM interaction_events").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrateFiveToSixPreservesRelationsAndCreatesCloudQueue() {
        helper.createDatabase(DB_NAME_V5, 5).apply {
            execSQL(
                """
                INSERT INTO candidates
                (id, externalUserId, displayName, note, source, gender, consumption, templateId,
                 imageCount, state, createdAtEpochMs, updatedAtEpochMs, platform,
                 relationshipStage, contactEligibility, lastInteractionAtEpochMs,
                 lastContactAtEpochMs, nextReminderAtEpochMs, priorityScore, doNotContactReason)
                VALUES ('candidate-cloud', 'ingkee-100', '迁移成员', '', 'manual', NULL, NULL,
                        NULL, 0, 'available', 100, 100, 'ingkee',
                        'active', 'eligible', 100, NULL, NULL, 60, NULL)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME_V5,
            6,
            true,
            ExecutorDatabase.MIGRATION_5_6,
        )
        db.query("SELECT displayName, platform FROM candidates WHERE id = 'candidate-cloud'").use {
            it.moveToFirst()
            assertEquals("迁移成员", it.getString(0))
            assertEquals("ingkee", it.getString(1))
        }
        listOf("cloud_config", "cloud_tasks", "cloud_outbox").forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
        db.close()
    }

    companion object {
        private const val DB_NAME = "migration-test"
        private const val DB_NAME_V3 = "migration-test-v3"
        private const val DB_NAME_V4 = "migration-test-v4"
        private const val DB_NAME_V5 = "migration-test-v5"
    }
}
