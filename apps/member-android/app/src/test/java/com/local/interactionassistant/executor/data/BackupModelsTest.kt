package com.local.interactionassistant.executor.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupModelsTest {
    @Test
    fun snapshotRoundTripPreservesV4Fields() {
        val snapshot = BackupSnapshot(
            candidates = listOf(
                CandidateEntity("candidate-1", "user-1", "测试用户"),
            ),
            tasks = listOf(
                TaskEntity(
                    id = "task-1",
                    candidateId = "candidate-1",
                    externalUserId = "user-1",
                    displayName = "测试用户",
                    note = "",
                    source = "manual",
                    messageText = "你好",
                    runSessionId = "session-1",
                    retryOfTaskId = "old-task",
                    attemptNumber = 2,
                    failureCode = "unknown_page",
                    minDelaySeconds = 1,
                    maxDelaySeconds = 2,
                ),
            ),
            taskAssets = emptyList(),
            templates = emptyList(),
            templateLines = emptyList(),
            assets = emptyList(),
            blacklist = emptyList(),
            history = emptyList(),
            logs = emptyList(),
            settings = AppSettingsEntity(),
            profiles = emptyList(),
            profileAssets = emptyList(),
            blockRules = emptyList(),
            runSessions = listOf(
                RunSessionEntity("session-1", null, "默认预设", state = "paused", totalCount = 1),
            ),
        )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(snapshot)
        val decoded = json.decodeFromString<BackupSnapshot>(encoded)
        assertEquals(2, decoded.tasks.single().attemptNumber)
        assertEquals("paused", decoded.runSessions.single().state)
        assertFalse(encoded.contains("authorization_code"))
    }
}
