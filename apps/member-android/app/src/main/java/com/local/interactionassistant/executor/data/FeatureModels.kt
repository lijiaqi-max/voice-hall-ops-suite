package com.local.interactionassistant.executor.data

data class CandidateImportPreviewRow(
    val lineNumber: Int,
    val candidate: CandidateCsvRow?,
    val status: String,
    val reason: String,
)

data class CandidateImportPreview(
    val rows: List<CandidateImportPreviewRow>,
) {
    val validCount: Int get() = rows.count { it.status == "valid" }
    val duplicateCount: Int get() = rows.count { it.status == "duplicate" }
    val blockedCount: Int get() = rows.count { it.status == "blocked" }
    val errorCount: Int get() = rows.count { it.status == "invalid" }
}

data class RunDashboard(
    val session: RunSessionEntity?,
    val total: Int,
    val queued: Int,
    val presented: Int,
    val approved: Int,
    val executing: Int,
    val sent: Int,
    val failed: Int,
    val skipped: Int,
    val currentUser: String?,
)

data class DailyContactStats(
    val sentToday: Int,
    val dailyLimit: Int,
) {
    val remaining: Int get() = (dailyLimit - sentToday).coerceAtLeast(0)
}

data class BackupRestoreResult(
    val candidates: Int,
    val tasks: Int,
    val history: Int,
    val assets: Int,
    val profiles: Int,
    val blockRules: Int,
)

data class FirstRunCheck(
    val key: String,
    val title: String,
    val ready: Boolean,
    val detail: String,
)
