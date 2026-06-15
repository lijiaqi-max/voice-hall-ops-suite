package com.local.micqueueassistant.data

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
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AppDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrateOneToTwoPreservesRoleAndAddsCloudSettings() {
        helper.createDatabase(DB_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO app_config (
                    id, role, groupTitle, ownerWechatName, robotAutoReplyEnabled,
                    wechatCalibrationStatus, ingkeeCalibrationStatus, serverPort,
                    collectorHost, pairingCode, pinnedCertificateSha256,
                    foregroundServiceEnabled
                ) VALUES (
                    1, 'wechat_bot', '测试群', '管理员', 0,
                    'pending', 'pending', 18443,
                    '', '', '', 0
                )
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        )
        db.query(
            """
            SELECT role, cloudBaseUrl, cloudRoomId, cloudDeviceId,
                   cloudLastSyncAtEpochMs, cloudSyncEnabled
            FROM app_config WHERE id = 1
            """.trimIndent(),
        ).use {
            it.moveToFirst()
            assertEquals("wechat_bot", it.getString(0))
            assertEquals("", it.getString(1))
            assertEquals("", it.getString(2))
            assertEquals("", it.getString(3))
            assertNull(it.getString(4))
            assertEquals(0, it.getInt(5))
        }
        db.close()
    }

    companion object {
        private const val DB_NAME = "mic-queue-migration-v1"
    }
}
