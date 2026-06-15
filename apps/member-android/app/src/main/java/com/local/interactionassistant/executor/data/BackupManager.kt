package com.local.interactionassistant.executor.data

import com.local.interactionassistant.executor.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val dao: ExecutorDao,
    private val assetLibrary: AssetLibrary,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun export(output: OutputStream) {
        val snapshot = BackupSnapshot(
            candidates = dao.getAllCandidates(),
            tasks = dao.getAllTasks(),
            taskAssets = dao.getAllTaskAssets(),
            templates = dao.getAllTemplates(),
            templateLines = dao.getAllTemplateLines(),
            assets = dao.getAllAssets(),
            blacklist = dao.getAllBlacklist(),
            history = dao.getHistory(),
            logs = dao.getLogs(),
            settings = dao.getSettings() ?: AppSettingsEntity(),
            profiles = dao.getAllProfiles(),
            profileAssets = dao.getAllProfileAssetRows(),
            blockRules = dao.getAllBlockRules(),
            runSessions = dao.getAllRunSessions(),
            interactionEvents = dao.getAllInteractionEvents(),
            relationshipStageHistory = dao.getAllStageHistory(),
        )
        val dataBytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        val manifest = BackupManifest(
            formatVersion = FORMAT_VERSION,
            appVersion = BuildConfig.VERSION_NAME,
            createdAtEpochMs = System.currentTimeMillis(),
            dataSha256 = dataBytes.sha256(),
            assetHashes = snapshot.assets.map(MediaAssetEntity::sha256).sorted(),
            plaintextWarning = "This ZIP contains plaintext local data. Store it securely.",
        )
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(dataBytes)
            zip.closeEntry()
            snapshot.assets.distinctBy(MediaAssetEntity::sha256).forEach { asset ->
                val file = File(asset.privatePath)
                check(file.isFile) { "素材文件缺失：${asset.displayName}" }
                zip.putNextEntry(ZipEntry("assets/${asset.sha256}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    suspend fun restore(input: InputStream, replace: Boolean): BackupRestoreResult {
        val entries = readEntries(input)
        val manifestBytes = entries["manifest.json"] ?: error("备份缺少 manifest.json")
        val dataBytes = entries["data.json"] ?: error("备份缺少 data.json")
        val manifest = json.decodeFromString<BackupManifest>(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.formatVersion in 1..FORMAT_VERSION) { "不支持的备份格式版本" }
        check(manifest.dataSha256 == dataBytes.sha256()) { "data.json 校验失败" }
        val snapshot = json.decodeFromString<BackupSnapshot>(dataBytes.toString(Charsets.UTF_8))
        snapshot.assets.forEach { asset ->
            val bytes = entries["assets/${asset.sha256}"] ?: error("备份素材缺失：${asset.sha256}")
            check(bytes.sha256() == asset.sha256) { "素材校验失败：${asset.sha256}" }
        }

        if (replace) {
            dao.getAllAssets().forEach(assetLibrary::delete)
            dao.clearForRestore()
        }

        val existingTemplateIds = dao.getAllTemplates().mapTo(mutableSetOf(), MessageTemplateEntity::id)
        snapshot.templates.forEach { template ->
            if (replace || template.id !in existingTemplateIds) {
                dao.upsertTemplate(template)
                dao.deleteTemplateLines(template.id)
                dao.insertTemplateLines(snapshot.templateLines.filter { it.templateId == template.id })
                existingTemplateIds += template.id
            }
        }

        val assetIdMap = mutableMapOf<String, String>()
        var importedAssets = 0
        snapshot.assets.forEach { source ->
            val existing = dao.getAssetByHash(source.sha256)
            if (existing != null) {
                assetIdMap[source.id] = existing.id
            } else {
                val imported = assetLibrary.importBytes(
                    displayName = source.displayName,
                    mimeType = source.mimeType,
                    bytes = entries.getValue("assets/${source.sha256}"),
                    expectedSha256 = source.sha256,
                )
                dao.insertAsset(imported)
                assetIdMap[source.id] = imported.id
                importedAssets += 1
            }
        }

        val existingCandidates = dao.getAllCandidates()
        val candidateByExternalId = existingCandidates.associateBy {
            it.externalUserId.trim().lowercase()
        }.toMutableMap()
        val usedCandidateIds = existingCandidates.mapTo(mutableSetOf(), CandidateEntity::id)
        val candidateIdMap = mutableMapOf<String, String>()
        var importedCandidates = 0
        snapshot.candidates.forEach { source ->
            val normalizedSource = if (manifest.formatVersion == 1) {
                source.copy(
                    platform = "legacy",
                    contactEligibility = ContactEligibility.INELIGIBLE.value,
                )
            } else {
                source
            }
            val key = normalizedSource.externalUserId.trim().lowercase()
            val existing = candidateByExternalId[key]
            if (existing != null) {
                candidateIdMap[source.id] = existing.id
            } else {
                val targetId = normalizedSource.id.takeUnless(usedCandidateIds::contains)
                    ?: UUID.randomUUID().toString()
                val imported = normalizedSource.copy(id = targetId)
                dao.upsertCandidate(imported)
                candidateByExternalId[key] = imported
                usedCandidateIds += targetId
                candidateIdMap[source.id] = targetId
                importedCandidates += 1
            }
        }

        snapshot.interactionEvents.forEach { source ->
            val candidateId = candidateIdMap[source.candidateId] ?: return@forEach
            dao.insertInteractionEvent(source.copy(candidateId = candidateId))
        }
        snapshot.relationshipStageHistory.forEach { source ->
            val candidateId = candidateIdMap[source.candidateId] ?: return@forEach
            dao.insertStageHistory(source.copy(candidateId = candidateId))
        }

        snapshot.blacklist.forEach { dao.upsertBlacklist(it) }
        snapshot.blockRules.forEach { dao.upsertBlockRule(it) }

        val existingProfileIds = dao.getAllProfiles().mapTo(mutableSetOf(), AutomationProfileEntity::id)
        val restoredProfileIds = mutableSetOf<String>()
        var importedProfiles = 0
        snapshot.profiles.forEach { profile ->
            if (replace || profile.id !in existingProfileIds) {
                dao.upsertProfile(profile.copy(isDefault = false))
                existingProfileIds += profile.id
                restoredProfileIds += profile.id
                importedProfiles += 1
            }
        }
        snapshot.profileAssets.groupBy(ProfileAssetEntity::profileId).forEach { (profileId, rows) ->
            if (profileId in restoredProfileIds) {
                dao.replaceProfileAssets(
                    profileId,
                    rows.sortedBy(ProfileAssetEntity::position).mapNotNull { assetIdMap[it.assetId] },
                )
            }
        }
        snapshot.profiles.firstOrNull(AutomationProfileEntity::isDefault)?.let { importedDefault ->
            if (importedDefault.id in existingProfileIds) dao.setDefaultProfile(importedDefault.id)
        }

        val existingSessionIds = dao.getAllRunSessions().mapTo(mutableSetOf(), RunSessionEntity::id)
        snapshot.runSessions.forEach { source ->
            if (source.id !in existingSessionIds) {
                val restored = if (source.state in setOf("draft", "running", "paused")) {
                    source.copy(
                        state = "cancelled",
                        pausedAtEpochMs = null,
                        completedAtEpochMs = System.currentTimeMillis(),
                    )
                } else {
                    source
                }
                dao.upsertRunSession(restored)
                existingSessionIds += source.id
            }
        }

        val existingTaskIds = dao.getAllTasks().mapTo(mutableSetOf(), TaskEntity::id)
        val restoredTaskIds = mutableSetOf<String>()
        var importedTasks = 0
        snapshot.tasks.forEach { source ->
            if (source.id !in existingTaskIds) {
                val candidateId = candidateIdMap[source.candidateId] ?: return@forEach
                val restoredState = if (source.state in setOf("queued", "presented", "approved", "executing")) {
                    "failed"
                } else {
                    source.state
                }
                dao.upsertTask(
                    source.copy(
                        candidateId = candidateId,
                        state = restoredState,
                        failureReason = if (restoredState == "failed" && source.state != "failed") {
                            "从备份恢复的未完成任务"
                        } else {
                            source.failureReason
                        },
                        failureCode = if (restoredState == "failed" && source.state != "failed") {
                            "restored_incomplete"
                        } else {
                            source.failureCode
                        },
                    ),
                )
                existingTaskIds += source.id
                restoredTaskIds += source.id
                importedTasks += 1
            }
        }
        snapshot.taskAssets.groupBy(TaskAssetEntity::taskId).forEach { (taskId, rows) ->
            if (taskId in restoredTaskIds) {
                val mapped = rows.sortedBy(TaskAssetEntity::position).mapNotNull { row ->
                    assetIdMap[row.assetId]?.let { TaskAssetEntity(taskId, it, row.position) }
                }
                if (mapped.isNotEmpty()) dao.insertTaskAssets(mapped)
            }
        }

        var importedHistory = 0
        val existingHistoryTaskIds = dao.getHistory().mapTo(mutableSetOf(), SendHistoryEntity::taskId)
        snapshot.history.forEach { history ->
            if (history.taskId !in existingHistoryTaskIds) {
                dao.insertHistory(history.copy(id = 0))
                existingHistoryTaskIds += history.taskId
                importedHistory += 1
            }
        }
        snapshot.logs.forEach { dao.insertLog(it.copy(id = 0)) }
        dao.saveSettings(
            snapshot.settings.copy(
                id = 1,
                officialAutoNavigationEnabled = false,
                dailyContactLimit = 20,
                perUserSevenDayLimit = 2,
            ),
        )

        return BackupRestoreResult(
            candidates = importedCandidates,
            tasks = importedTasks,
            history = importedHistory,
            assets = importedAssets,
            profiles = importedProfiles,
            blockRules = snapshot.blockRules.size,
        )
    }

    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var totalSize = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                check(name == "manifest.json" || name == "data.json" || name.matches(ASSET_ENTRY)) {
                    "备份包含不允许的条目"
                }
                val bytes = zip.readBytesLimited(MAX_ENTRY_BYTES)
                totalSize += bytes.size
                check(totalSize <= MAX_TOTAL_BYTES) { "备份文件过大" }
                entries[name] = bytes
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            check(output.size() <= limit) { "备份条目过大" }
        }
        return output.toByteArray()
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val FORMAT_VERSION = 2
        private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        private val ASSET_ENTRY = Regex("""assets/[0-9a-f]{64}""")
    }
}
