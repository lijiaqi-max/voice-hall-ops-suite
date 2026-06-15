package com.local.interactionassistant.executor

import android.content.Context
import android.net.Uri
import com.local.interactionassistant.executor.automation.AutomationCandidate
import com.local.interactionassistant.executor.automation.AutomationCoordinator
import com.local.interactionassistant.executor.automation.AutomationResult
import com.local.interactionassistant.executor.automation.ExecutionPayload
import com.local.interactionassistant.executor.data.AppSettingsEntity
import com.local.interactionassistant.executor.data.AssetLibrary
import com.local.interactionassistant.executor.data.AutomationProfileEntity
import com.local.interactionassistant.executor.data.BackupManager
import com.local.interactionassistant.executor.data.BackupRestoreResult
import com.local.interactionassistant.executor.data.BlacklistEntity
import com.local.interactionassistant.executor.data.BlockRuleEntity
import com.local.interactionassistant.executor.data.CandidateEntity
import com.local.interactionassistant.executor.data.CandidateImportPreview
import com.local.interactionassistant.executor.data.CandidateImportPreviewRow
import com.local.interactionassistant.executor.data.ContactEligibility
import com.local.interactionassistant.executor.data.CsvCodec
import com.local.interactionassistant.executor.data.DailyContactStats
import com.local.interactionassistant.executor.data.ExecutorDatabase
import com.local.interactionassistant.executor.data.InteractionEventEntity
import com.local.interactionassistant.executor.data.InteractionType
import com.local.interactionassistant.executor.data.MediaAssetEntity
import com.local.interactionassistant.executor.data.MessageTemplateEntity
import com.local.interactionassistant.executor.data.RunLogEntity
import com.local.interactionassistant.executor.data.RunSessionEntity
import com.local.interactionassistant.executor.data.RelationshipStage
import com.local.interactionassistant.executor.data.RelationshipStageHistoryEntity
import com.local.interactionassistant.executor.data.SendHistoryEntity
import com.local.interactionassistant.executor.data.TaskAssetEntity
import com.local.interactionassistant.executor.data.TaskEntity
import com.local.interactionassistant.executor.data.TemplateLineEntity
import com.local.interactionassistant.executor.data.TemplateWithLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.random.Random

class LocalRepository(
    private val context: Context,
    database: ExecutorDatabase,
) {
    private val dao = database.dao()
    private val assetLibrary = AssetLibrary(context)
    private val backupManager = BackupManager(dao, assetLibrary)
    private val executionMutex = Mutex()

    val candidates = dao.observeCandidates()
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeTasks = dao.observeActiveTasks()
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeTaskAssets = dao.observeActiveTaskAssets()
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allTasks = dao.observeAllTasks()
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val templates = dao.observeTemplates()
        .map { rows -> rows.map { it.copy(lines = it.lines.sortedBy(TemplateLineEntity::position)) } }
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val assets = dao.observeAssets()
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val blacklist = dao.observeBlacklist()
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val profiles = dao.observeProfiles()
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val blockRules = dao.observeBlockRules()
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val runSessions = dao.observeRunSessions()
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val runDashboard = combine(runSessions, allTasks) { sessions, tasks ->
        val session = sessions.firstOrNull { it.state in setOf("draft", "running", "paused") }
            ?: sessions.firstOrNull()
        val rows = session?.let { active -> tasks.filter { it.runSessionId == active.id } }.orEmpty()
        com.local.interactionassistant.executor.data.RunDashboard(
            session = session,
            total = rows.size,
            queued = rows.count { it.state == "queued" },
            presented = rows.count { it.state == "presented" },
            approved = rows.count { it.state == "approved" },
            executing = rows.count { it.state == "executing" },
            sent = rows.count { it.state == "sent" },
            failed = rows.count { it.state == "failed" },
            skipped = rows.count { it.state == "skipped" },
            currentUser = rows.firstOrNull {
                it.state in setOf("presented", "approved", "executing")
            }?.displayName,
        )
    }.stateIn(
        ExecutorApp.applicationScope,
        SharingStarted.WhileSubscribed(5_000),
        com.local.interactionassistant.executor.data.RunDashboard(
            null, 0, 0, 0, 0, 0, 0, 0, 0, null,
        ),
    )
    val history = dao.observeHistory()
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val interactionEvents = dao.observeInteractionEvents()
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val logs = dao.observeLogs()
        .stateIn(ExecutorApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = dao.observeSettings()
        .map { it ?: AppSettingsEntity() }
        .stateIn(
            ExecutorApp.applicationScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettingsEntity(),
        )
    val dailyContactStats = combine(history, settings) { rows, currentSettings ->
        DailyContactStats(
            sentToday = rows.count { it.sentAtEpochMs >= startOfTodayEpochMs() },
            dailyLimit = currentSettings.dailyContactLimit,
        )
    }.stateIn(
        ExecutorApp.applicationScope,
        SharingStarted.WhileSubscribed(5_000),
        DailyContactStats(0, AppSettingsEntity().dailyContactLimit),
    )

    suspend fun initialize() {
        if (dao.getSettings() == null) dao.saveSettings(AppSettingsEntity())
        if (dao.getAllTemplates().isEmpty()) {
            saveTemplate(
                id = DEFAULT_TEMPLATE_ID,
                name = "评论感谢",
                lines = STARTER_TEMPLATES.getValue("评论感谢"),
                randomize = true,
            ).getOrThrow()
        }
        seedStarterTemplates()
        seedStarterAssets()
        if (dao.getDefaultProfile() == null) {
            val current = dao.getSettings() ?: AppSettingsEntity()
            dao.upsertProfile(
                AutomationProfileEntity(
                    id = DEFAULT_PROFILE_ID,
                    name = "默认预设",
                    templateId = dao.getAllTemplates().firstOrNull()?.id,
                    minDelaySeconds = current.minDelaySeconds,
                    maxDelaySeconds = current.maxDelaySeconds,
                    deduplicateSuccessfulUsers = current.deduplicateSuccessfulUsers,
                    isDefault = true,
                ),
            )
        }
    }

    private suspend fun seedStarterTemplates() {
        val existingNames = dao.getAllTemplates().mapTo(mutableSetOf(), MessageTemplateEntity::name)
        STARTER_TEMPLATES.forEach { (name, lines) ->
            if (name !in existingNames) {
                saveTemplate(
                    id = "starter-${name.hashCode().toUInt().toString(16)}",
                    name = name,
                    lines = lines,
                    randomize = true,
                ).getOrThrow()
            }
        }
    }

    private suspend fun seedStarterAssets() = withContext(Dispatchers.IO) {
        STARTER_ASSETS.forEach { (filename, displayName) ->
            val bytes = context.assets.open("starter_assets/$filename").use { it.readBytes() }
            val imported = assetLibrary.importBytes(displayName, "image/png", bytes)
            if (dao.getAssetByHash(imported.sha256) == null) {
                dao.insertAsset(imported)
            } else {
                assetLibrary.delete(imported)
            }
        }
    }

    suspend fun addCandidate(
        externalUserId: String,
        displayName: String,
        note: String,
        source: String = "manual",
        gender: String? = null,
        consumption: Long? = null,
        templateId: String? = null,
        imageCount: Int = 0,
        interactionType: String? = null,
        interactionSummary: String = "",
    ): Result<String> = runCatching {
        val id = externalUserId.trim()
        val name = displayName.trim()
        require(id.length in 3..40) { "用户 ID 需要 3–40 个字符" }
        require(name.length in 1..40) { "昵称需要 1–40 个字符" }
        require(imageCount in 0..3) { "图片数量必须为 0–3" }
        check(dao.countBlacklisted(id) == 0) { "该用户已在黑名单中" }
        check(StandalonePolicy.matchingBlockRule(id, name, dao.getAllBlockRules()) == null) {
            "该用户命中屏蔽规则"
        }
        check(dao.getCandidateByExternalId(id) == null) { "该用户已在候选列表中" }
        val candidate = CandidateEntity(
                id = UUID.randomUUID().toString(),
                externalUserId = id,
                displayName = name,
                note = note.trim(),
                source = source,
                gender = gender,
                consumption = consumption,
                templateId = templateId,
                imageCount = imageCount,
                platform = "ingkee",
                relationshipStage = interactionType?.let(StandalonePolicy::stageForInteraction)
                    ?: RelationshipStage.NEW_INTERACTION.value,
                contactEligibility = when {
                    interactionType != null -> StandalonePolicy.contactEligibilityFor(interactionType)
                    source == "manual" -> ContactEligibility.MANUAL_CONFIRMED.value
                    else -> ContactEligibility.INELIGIBLE.value
                },
                lastInteractionAtEpochMs = interactionType?.let { System.currentTimeMillis() },
            )
        dao.upsertCandidate(candidate)
        if (interactionType != null) {
            recordInteraction(candidate.id, interactionType, interactionSummary, source)
        } else if (source == "manual") {
            recordInteraction(
                candidate.id,
                InteractionType.MANUAL_CONFIRMED.value,
                "用户人工确认已有关系",
                source,
            )
        }
        log("info", "candidate.created", "$name ($id) 已加入候选")
        "候选已添加"
    }

    suspend fun importCsv(content: String): Result<String> = runCatching {
        val parsed = CsvCodec.parseCandidates(content)
        var imported = 0
        var duplicates = 0
        var blocked = 0
        parsed.rows.forEach { row ->
            if (dao.countBlacklisted(row.externalUserId) > 0) {
                blocked += 1
            } else if (dao.getCandidateByExternalId(row.externalUserId) != null) {
                duplicates += 1
            } else {
                addCandidate(
                    externalUserId = row.externalUserId,
                    displayName = row.displayName,
                    note = row.note,
                    source = "csv",
                    gender = row.gender,
                    consumption = row.consumption,
                    templateId = row.templateId,
                    imageCount = row.imageCount,
                ).getOrThrow()
                imported += 1
            }
        }
        val errorSuffix = if (parsed.errors.isEmpty()) "" else "，无效 ${parsed.errors.size} 条"
        "导入 $imported 条，重复 $duplicates 条，黑名单 $blocked 条$errorSuffix"
    }

    suspend fun previewCandidates(
        content: String,
        csv: Boolean,
    ): Result<CandidateImportPreview> = runCatching {
        val parsed = if (csv) CsvCodec.parseCandidates(content) else CsvCodec.parsePastedCandidates(content)
        val existingIds = dao.getAllCandidates()
            .mapTo(mutableSetOf()) { it.externalUserId.trim().lowercase() }
        val blacklistedIds = dao.getAllBlacklist()
            .mapTo(mutableSetOf()) { it.externalUserId.trim().lowercase() }
        val rules = dao.getAllBlockRules()
        val rows = mutableListOf<CandidateImportPreviewRow>()
        parsed.rows.forEachIndexed { index, candidate ->
            val normalizedId = candidate.externalUserId.trim().lowercase()
            val rule = StandalonePolicy.matchingBlockRule(
                candidate.externalUserId,
                candidate.displayName,
                rules,
            )
            val status: String
            val reason: String
            when {
                normalizedId in blacklistedIds -> {
                    status = "blocked"
                    reason = "命中黑名单"
                }
                rule != null -> {
                    status = "blocked"
                    reason = "命中屏蔽规则：${rule.value}"
                }
                normalizedId in existingIds -> {
                    status = "duplicate"
                    reason = "用户 ID 重复"
                }
                else -> {
                    status = "valid"
                    reason = "可导入"
                    existingIds += normalizedId
                }
            }
            rows += CandidateImportPreviewRow(
                lineNumber = index + if (csv) 2 else 1,
                candidate = candidate,
                status = status,
                reason = reason,
            )
        }
        parsed.errors.forEachIndexed { index, error ->
            rows += CandidateImportPreviewRow(
                lineNumber = index + 1,
                candidate = null,
                status = "invalid",
                reason = error,
            )
        }
        CandidateImportPreview(rows.sortedBy(CandidateImportPreviewRow::lineNumber))
    }

    suspend fun commitImportPreview(preview: CandidateImportPreview): Result<String> = runCatching {
        var imported = 0
        preview.rows.filter { it.status == "valid" }.forEach { previewRow ->
            val row = previewRow.candidate ?: return@forEach
            addCandidate(
                externalUserId = row.externalUserId,
                displayName = row.displayName,
                note = row.note,
                source = "csv",
                gender = row.gender,
                consumption = row.consumption,
                templateId = row.templateId,
                imageCount = row.imageCount,
            ).getOrThrow()
            imported += 1
        }
        "已导入 $imported 条候选"
    }

    suspend fun saveProfile(
        id: String? = null,
        name: String,
        templateId: String?,
        genderFilter: String,
        minConsumption: Long?,
        maxConsumption: Long?,
        sourceFilter: String,
        unknownFieldPolicy: String,
        imagePolicy: String,
        imageCount: Int,
        minDelaySeconds: Int,
        maxDelaySeconds: Int,
        deduplicateSuccessfulUsers: Boolean,
        assetIds: List<String> = emptyList(),
    ): Result<String> = runCatching {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "预设名称不能为空" }
        require(genderFilter in setOf("all", "male", "female")) { "性别筛选无效" }
        require(sourceFilter in setOf("all", "manual", "csv", "scan")) { "来源筛选无效" }
        require(unknownFieldPolicy in setOf("retain", "exclude")) { "未知字段策略无效" }
        require(imagePolicy in setOf("none", "random", "specified")) { "图片策略无效" }
        require(imageCount in 0..3) { "图片数量必须为 0–3" }
        require(minConsumption == null || maxConsumption == null || minConsumption <= maxConsumption) {
            "公开互动值区间无效"
        }
        StandalonePolicy.randomDelaySeconds(minDelaySeconds, maxDelaySeconds, Random(1))
        val normalizedAssets = StandalonePolicy.normalizeAssetSelection(assetIds)
        if (imagePolicy == "specified") {
            require(normalizedAssets.size == imageCount) { "指定图片数量必须与预设图片数量一致" }
        }
        if (templateId != null) check(dao.getTemplate(templateId) != null) { "话术模板不存在" }
        val profileId = id ?: UUID.randomUUID().toString()
        val existing = id?.let { dao.getProfile(it) }
        val now = System.currentTimeMillis()
        dao.upsertProfile(
            AutomationProfileEntity(
                id = profileId,
                name = cleanName,
                templateId = templateId,
                genderFilter = genderFilter,
                minConsumption = minConsumption,
                maxConsumption = maxConsumption,
                sourceFilter = sourceFilter,
                unknownFieldPolicy = unknownFieldPolicy,
                imagePolicy = imagePolicy,
                imageCount = if (imagePolicy == "none") 0 else imageCount,
                minDelaySeconds = minDelaySeconds,
                maxDelaySeconds = maxDelaySeconds,
                deduplicateSuccessfulUsers = deduplicateSuccessfulUsers,
                isDefault = existing?.isDefault ?: false,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
            ),
        )
        dao.replaceProfileAssets(
            profileId,
            if (imagePolicy == "specified") normalizedAssets else emptyList(),
        )
        log("info", "profile.saved", "运行预设 $cleanName 已保存")
        "运行预设已保存"
    }

    suspend fun setDefaultProfile(profileId: String): Result<String> = runCatching {
        val profile = dao.getProfile(profileId) ?: error("运行预设不存在")
        dao.setDefaultProfile(profile.id)
        log("info", "profile.default", "${profile.name} 已设为默认预设")
        "默认预设已更新"
    }

    suspend fun getProfileAssetIds(profileId: String): List<String> =
        dao.getProfileAssets(profileId).map { it.id }

    suspend fun deleteProfile(profileId: String): Result<String> = runCatching {
        val profile = dao.getProfile(profileId) ?: error("运行预设不存在")
        check(!profile.isDefault) { "默认预设不能删除" }
        check(dao.getActiveRunSession()?.profileId != profileId) { "活动批次正在使用该预设" }
        dao.deleteProfile(profileId)
        log("info", "profile.deleted", "${profile.name} 已删除")
        "运行预设已删除"
    }

    suspend fun addBlockRule(type: String, value: String): Result<String> = runCatching {
        val normalized = StandalonePolicy.normalizeBlockValue(type, value)
        dao.upsertBlockRule(
            BlockRuleEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                value = value.trim(),
                normalizedValue = normalized,
            ),
        )
        log("info", "block_rule.created", "已添加屏蔽规则")
        "屏蔽规则已添加"
    }

    suspend fun deleteBlockRule(id: String): Result<String> = runCatching {
        dao.deleteBlockRule(id)
        log("info", "block_rule.deleted", "已删除屏蔽规则")
        "屏蔽规则已删除"
    }

    suspend fun setBlockRuleEnabled(id: String, enabled: Boolean): Result<String> = runCatching {
        dao.setBlockRuleEnabled(id, enabled)
        "屏蔽规则已${if (enabled) "启用" else "停用"}"
    }

    suspend fun importLatestScan(): Result<String> = runCatching {
        val scan = AutomationCoordinator.latestScan.value
            ?: error("尚未扫描到目标 App 推荐或搜索页面")
        check(scan.candidates.isNotEmpty()) { scan.reason }
        importAutomationCandidates(scan.candidates)
    }

    suspend fun scanNow(): Result<String> = runCatching {
        val scan = AutomationCoordinator.scan()
        check(scan.candidates.isNotEmpty()) { scan.reason }
        importAutomationCandidates(scan.candidates)
    }

    private suspend fun importAutomationCandidates(rows: List<AutomationCandidate>): String {
        var imported = 0
        var updated = 0
        var skipped = 0
        rows.forEach { row ->
            val existing = dao.getCandidateByExternalId(row.externalUserId)
            if (existing != null && existing.platform == "ingkee") {
                recordInteraction(
                    existing.id,
                    row.interactionType,
                    row.interactionSummary,
                    "scan",
                )
                updated += 1
                return@forEach
            }
            val result = addCandidate(
                externalUserId = row.externalUserId,
                displayName = row.displayName,
                note = row.note,
                source = "scan",
                gender = row.gender,
                consumption = row.consumption,
                interactionType = row.interactionType,
                interactionSummary = row.interactionSummary,
            )
            if (result.isSuccess) imported += 1 else skipped += 1
        }
        return "直播互动新增 $imported 位，更新 $updated 位，跳过 $skipped 位"
    }

    suspend fun skipCandidate(candidateId: String): Result<String> = runCatching {
        val candidate = dao.getCandidate(candidateId) ?: error("候选不存在")
        dao.updateCandidateState(candidateId, "skipped")
        log("info", "candidate.skipped", "${candidate.displayName} 已跳过")
        "候选已跳过"
    }

    suspend fun blacklistCandidate(candidateId: String): Result<String> = runCatching {
        val candidate = dao.getCandidate(candidateId) ?: error("候选不存在")
        dao.upsertBlacklist(
            BlacklistEntity(
                externalUserId = candidate.externalUserId,
                displayName = candidate.displayName,
                reason = "用户手动加入",
            ),
        )
        dao.updateCandidateState(candidate.id, "blocked")
        updateRelationshipStage(
            candidate.id,
            RelationshipStage.DO_NOT_CONTACT.value,
            "加入黑名单",
        ).getOrThrow()
        log("info", "blacklist.added", "${candidate.displayName} 已加入黑名单")
        "已加入黑名单"
    }

    suspend fun removeBlacklist(externalUserId: String): Result<String> = runCatching {
        dao.removeBlacklist(externalUserId)
        log("info", "blacklist.removed", "$externalUserId 已移出黑名单")
        "已移出黑名单"
    }

    suspend fun createTask(candidateId: String): Result<String> = runCatching {
        val candidate = dao.getCandidate(candidateId) ?: error("候选不存在")
        check(dao.countGlobalInFlightTasks() == 0) { "请先处理当前展示任务" }
        check(candidate.state == "available") { "候选当前不可创建任务" }
        check(candidate.platform == "ingkee") { "旧平台数据仅可查看、导出或删除" }
        check(candidate.contactEligibility in CONTACT_ELIGIBLE_STATES) { "该用户尚无互动或人工关系确认" }
        check(candidate.relationshipStage != RelationshipStage.DO_NOT_CONTACT.value) { "该用户已禁止联系" }
        check(dao.countBlacklisted(candidate.externalUserId) == 0) { "该用户已在黑名单中" }
        check(dao.countTasksForUser(candidate.externalUserId) == 0) { "该用户已有待处理任务" }
        ensureContactAllowed(candidate.externalUserId)
        val template = selectTemplate(candidate.templateId)
        val message = StandalonePolicy.chooseMessage(
            template.lines.map(TemplateLineEntity::content),
            template.template.randomize,
        )
        val selectedAssets = dao.getAllAssets().shuffled().take(candidate.imageCount)
        check(selectedAssets.size == candidate.imageCount) {
            "素材库只有 ${selectedAssets.size} 张可用图片，需要 ${candidate.imageCount} 张"
        }
        val taskId = UUID.randomUUID().toString()
        val task = TaskEntity(
            id = taskId,
            candidateId = candidate.id,
            externalUserId = candidate.externalUserId,
            displayName = candidate.displayName,
            note = candidate.note,
            source = candidate.source,
            gender = candidate.gender,
            consumption = candidate.consumption,
            templateId = template.template.id,
            messageText = message,
            imageCount = candidate.imageCount,
            imagePolicy = "random",
            state = "queued",
            minDelaySeconds = settings.value.minDelaySeconds,
            maxDelaySeconds = settings.value.maxDelaySeconds,
        )
        dao.upsertTask(task)
        if (selectedAssets.isNotEmpty()) {
            dao.insertTaskAssets(
                selectedAssets.mapIndexed { index, asset ->
                    TaskAssetEntity(taskId, asset.id, index)
                },
            )
        }
        dao.updateTaskState(taskId, "presented")
        dao.updateCandidateState(candidate.id, "tasked")
        log("info", "task.created", "${candidate.displayName} 的任务已创建", taskId)
        "任务已创建，话术和图片已固定"
    }

    suspend fun startRunSession(profileId: String): Result<String> = runCatching {
        check(dao.getActiveRunSession() == null) { "已有活动批次，请先完成或取消" }
        check(dao.countGlobalInFlightTasks() == 0) { "请先处理当前展示任务" }
        val profile = dao.getProfile(profileId) ?: error("运行预设不存在")
        val blacklistIds = dao.getAllBlacklist()
            .mapTo(mutableSetOf()) { it.externalUserId.trim().lowercase() }
        val rules = dao.getAllBlockRules()
        val candidates = dao.getAllCandidates().filter { candidate ->
            candidate.state == "available" &&
                candidate.platform == "ingkee" &&
                candidate.contactEligibility in CONTACT_ELIGIBLE_STATES &&
                candidate.relationshipStage != RelationshipStage.DO_NOT_CONTACT.value &&
                candidate.externalUserId.trim().lowercase() !in blacklistIds &&
                StandalonePolicy.matchingBlockRule(
                    candidate.externalUserId,
                    candidate.displayName,
                    rules,
                ) == null &&
                StandalonePolicy.candidateMatches(
                    candidate = candidate,
                    gender = profile.genderFilter,
                    minConsumption = profile.minConsumption,
                    maxConsumption = profile.maxConsumption,
                    source = profile.sourceFilter,
                    unknownFieldPolicy = profile.unknownFieldPolicy,
                )
        }.filter { candidate ->
            contactLimitStatus(candidate.externalUserId).allowed
        }.take(dailyContactStats.value.remaining)
        check(candidates.isNotEmpty()) { "没有符合当前预设的可用候选" }

        val allAssets = dao.getAllAssets()
        val specifiedAssets = if (profile.imagePolicy == "specified") {
            dao.getProfileAssets(profile.id)
        } else {
            emptyList()
        }
        if (profile.imagePolicy == "random") {
            check(allAssets.size >= profile.imageCount) {
                "素材库图片不足，需要 ${profile.imageCount} 张"
            }
        }
        if (profile.imagePolicy == "specified") {
            check(specifiedAssets.size == profile.imageCount) { "预设指定图片不完整" }
        }

        data class PreparedTask(
            val candidate: CandidateEntity,
            val task: TaskEntity,
            val assetIds: List<String>,
        )

        val sessionId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val prepared = candidates.mapIndexed { index, candidate ->
            val template = selectTemplate(profile.templateId ?: candidate.templateId)
            val message = StandalonePolicy.chooseMessage(
                template.lines.map(TemplateLineEntity::content),
                template.template.randomize,
            )
            val selectedAssetIds = when (profile.imagePolicy) {
                "random" -> allAssets.shuffled().take(profile.imageCount).map(MediaAssetEntity::id)
                "specified" -> specifiedAssets.map { it.id }
                else -> emptyList()
            }
            val taskId = UUID.randomUUID().toString()
            PreparedTask(
                candidate = candidate,
                task = TaskEntity(
                    id = taskId,
                    candidateId = candidate.id,
                    externalUserId = candidate.externalUserId,
                    displayName = candidate.displayName,
                    note = candidate.note,
                    source = candidate.source,
                    gender = candidate.gender,
                    consumption = candidate.consumption,
                    templateId = template.template.id,
                    messageText = message,
                    imageCount = selectedAssetIds.size,
                    imagePolicy = profile.imagePolicy,
                    state = "queued",
                    runSessionId = sessionId,
                    minDelaySeconds = profile.minDelaySeconds,
                    maxDelaySeconds = profile.maxDelaySeconds,
                    createdAtEpochMs = createdAt + index,
                    updatedAtEpochMs = createdAt + index,
                ),
                assetIds = selectedAssetIds,
            )
        }

        dao.upsertRunSession(
            RunSessionEntity(
                id = sessionId,
                profileId = profile.id,
                profileName = profile.name,
                state = "draft",
                totalCount = prepared.size,
                createdAtEpochMs = createdAt,
            ),
        )
        prepared.forEach { row ->
            dao.upsertTask(row.task)
            if (row.assetIds.isNotEmpty()) {
                dao.insertTaskAssets(
                    row.assetIds.mapIndexed { position, assetId ->
                        TaskAssetEntity(row.task.id, assetId, position)
                    },
                )
            }
            dao.updateCandidateState(row.candidate.id, "tasked")
        }
        StandalonePolicy.requireSessionTransition("draft", "running")
        dao.updateRunSessionState(
            sessionId = sessionId,
            state = "running",
            startedAt = System.currentTimeMillis(),
        )
        advanceRunSession(sessionId)
        log("info", "run.started", "批次 ${profile.name} 已启动，共 ${prepared.size} 条")
        "批次已启动，共 ${prepared.size} 条任务"
    }

    suspend fun pauseRunSession(sessionId: String): Result<String> = runCatching {
        val session = dao.getRunSession(sessionId) ?: error("批次不存在")
        StandalonePolicy.requireSessionTransition(session.state, "paused")
        dao.updateRunSessionState(
            sessionId = session.id,
            state = "paused",
            startedAt = session.startedAtEpochMs,
            pausedAt = System.currentTimeMillis(),
        )
        log("info", "run.paused", "批次 ${session.profileName} 已暂停")
        "批次已暂停；已批准或正在发送的任务不受影响"
    }

    suspend fun resumeRunSession(sessionId: String): Result<String> = runCatching {
        val session = dao.getRunSession(sessionId) ?: error("批次不存在")
        StandalonePolicy.requireSessionTransition(session.state, "running")
        dao.updateRunSessionState(
            sessionId = session.id,
            state = "running",
            startedAt = session.startedAtEpochMs,
        )
        advanceRunSession(session.id)
        log("info", "run.resumed", "批次 ${session.profileName} 已恢复")
        "批次已恢复"
    }

    suspend fun cancelRunSession(sessionId: String): Result<String> = runCatching {
        val session = dao.getRunSession(sessionId) ?: error("批次不存在")
        StandalonePolicy.requireSessionTransition(session.state, "cancelled")
        val tasks = dao.getTasksForSession(session.id)
        check(tasks.none { it.state in setOf("approved", "executing") }) {
            "当前任务已批准或正在发送，请先处理该任务"
        }
        tasks.filter { it.state in setOf("queued", "presented") }.forEach { task ->
            dao.updateTaskState(
                taskId = task.id,
                state = "skipped",
                failureReason = "批次已取消",
                failureCode = "session_cancelled",
            )
            dao.updateCandidateState(task.candidateId, "available")
        }
        dao.updateRunSessionState(
            sessionId = session.id,
            state = "cancelled",
            startedAt = session.startedAtEpochMs,
            completedAt = System.currentTimeMillis(),
        )
        log("info", "run.cancelled", "批次 ${session.profileName} 已取消")
        "批次已取消"
    }

    suspend fun retryFailedTask(taskId: String): Result<String> = runCatching {
        val original = dao.getTask(taskId) ?: error("原任务不存在")
        check(original.state == "failed") { "仅失败任务可以重试" }
        check(dao.getActiveRunSession() == null && dao.countGlobalInFlightTasks() == 0) {
            "请先处理当前任务或活动批次"
        }
        check(dao.countTasksForUser(original.externalUserId) == 0) { "该用户已有待处理任务" }
        val retryId = UUID.randomUUID().toString()
        dao.upsertTask(
            original.copy(
                id = retryId,
                state = "presented",
                approvedAtEpochMs = null,
                approvalExpiresAtEpochMs = null,
                finalConfirmedAtEpochMs = null,
                verificationSummary = null,
                failureReason = null,
                runSessionId = null,
                retryOfTaskId = original.retryOfTaskId ?: original.id,
                attemptNumber = original.attemptNumber + 1,
                failureCode = null,
                createdAtEpochMs = System.currentTimeMillis(),
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        val assets = dao.getTaskAssets(original.id)
        if (assets.isNotEmpty()) {
            dao.insertTaskAssets(
                assets.mapIndexed { index, asset -> TaskAssetEntity(retryId, asset.id, index) },
            )
        }
        log("info", "task.retry_created", "${original.displayName} 已创建第 ${original.attemptNumber + 1} 次尝试", retryId)
        "失败任务已重新创建，原话术和图片保持不变"
    }

    suspend fun updateTaskMessage(taskId: String, message: String): Result<String> = runCatching {
        val cleanMessage = message.trim()
        require(cleanMessage.isNotEmpty()) { "话术不能为空" }
        val task = dao.getTask(taskId) ?: error("任务不存在")
        check(task.state == "presented") { "只有待批准任务可以修改话术" }
        dao.updateTaskMessage(taskId, cleanMessage)
        log("info", "task.edited", "任务话术已更新", taskId)
        "任务话术已更新"
    }

    suspend fun saveAndApproveTask(taskId: String, message: String): Result<String> = runCatching {
        val cleanMessage = message.trim()
        require(cleanMessage.isNotEmpty()) { "话术不能为空" }
        val task = dao.getTask(taskId) ?: error("任务不存在")
        check(task.state == "presented") { "任务当前不可批准" }
        if (cleanMessage != task.messageText) {
            dao.updateTaskMessage(taskId, cleanMessage)
            log("info", "task.edited", "批准前已保存当前话术", taskId)
        }
        approveTask(taskId).getOrThrow()
    }

    suspend fun updateTaskAssets(taskId: String, assetIds: List<String>): Result<String> = runCatching {
        val selectedIds = StandalonePolicy.normalizeAssetSelection(assetIds)
        val availableIds = dao.getAllAssets().mapTo(mutableSetOf(), MediaAssetEntity::id)
        check(selectedIds.all(availableIds::contains)) { "选择的图片已不存在，请重新选择" }
        dao.replaceTaskAssets(taskId, selectedIds, imagePolicy = "specified")
        log("info", "task.assets_selected", "任务已指定 ${selectedIds.size} 张图片", taskId)
        "任务图片已更新为 ${selectedIds.size} 张"
    }

    suspend fun randomizeTaskAssets(taskId: String): Result<String> = runCatching {
        val task = dao.getTask(taskId) ?: error("任务不存在")
        check(task.state == "presented") { "只有待批准任务可以更换图片" }
        check(task.imageCount > 0) { "当前任务图片数量为 0" }
        val selectedIds = dao.getAllAssets()
            .shuffled()
            .take(task.imageCount)
            .map(MediaAssetEntity::id)
        check(selectedIds.size == task.imageCount) {
            "素材库只有 ${selectedIds.size} 张可用图片，需要 ${task.imageCount} 张"
        }
        dao.replaceTaskAssets(taskId, selectedIds, imagePolicy = "random")
        log("info", "task.assets_randomized", "任务已随机更换 ${selectedIds.size} 张图片", taskId)
        "已随机更换 ${selectedIds.size} 张图片"
    }

    suspend fun approveTask(taskId: String): Result<String> = runCatching {
        val task = dao.getTask(taskId) ?: error("任务不存在")
        check(task.state == "presented") { "任务当前不可批准" }
        check(AutomationCoordinator.connected.value) { "请先开启无障碍服务" }
        ensureContactAllowed(task.externalUserId)
        val risks = StandalonePolicy.messageRisks(task.messageText)
        check(risks.isEmpty()) { "话术风险提示：${risks.joinToString { it.label }}" }
        val now = System.currentTimeMillis()
        val expiresAt = now + settings.value.approvalTtlSeconds * 1_000L
        dao.updateTaskState(
            taskId = taskId,
            state = "approved",
            approvedAt = now,
            expiresAt = expiresAt,
        )
        log("info", "task.approved", "${task.displayName} 已完成第一次确认", taskId)
        "第一次确认完成，请在 ${settings.value.approvalTtlSeconds} 秒内打开正确聊天页"
    }

    suspend fun skipTask(taskId: String): Result<String> = runCatching {
        val task = dao.getTask(taskId) ?: error("任务不存在")
        dao.updateTaskState(
            taskId,
            "skipped",
            failureReason = "用户跳过",
            failureCode = "user_skipped",
        )
        log("info", "task.skipped", "${task.displayName} 已跳过", taskId)
        task.runSessionId?.let { advanceRunSession(it) }
        "任务已跳过"
    }

    suspend fun executionCandidate(): ExecutionPayload? = executionMutex.withLock {
        val task = dao.nextApprovedTask() ?: return null
        val now = System.currentTimeMillis()
        if (StandalonePolicy.approvalExpired(task.approvalExpiresAtEpochMs, now)) {
            AutomationCoordinator.updateFailureCode("approval_expired")
            dao.updateTaskState(
                task.id,
                "failed",
                failureReason = "批准已过期",
                failureCode = "approval_expired",
            )
            log("warn", "task.expired", "${task.displayName} 的批准已过期", task.id)
            task.runSessionId?.let { advanceRunSession(it) }
            return null
        }
        val files = dao.getTaskAssets(task.id)
        if (files.size != task.imageCount) {
            AutomationCoordinator.updateFailureCode("asset_missing")
            dao.updateTaskState(
                task.id,
                "failed",
                failureReason = "任务图片缺失",
                failureCode = "asset_missing",
            )
            log("error", "task.asset_missing", "${task.displayName} 的任务图片缺失", task.id)
            task.runSessionId?.let { advanceRunSession(it) }
            return null
        }
        ExecutionPayload(
            task,
            files,
            settings.value.copy(
                minDelaySeconds = task.minDelaySeconds,
                maxDelaySeconds = task.maxDelaySeconds,
            ),
        )
    }

    suspend fun recordVerified(taskId: String, summary: String) {
        val task = dao.getTask(taskId) ?: return
        if (task.state == "approved") {
            AutomationCoordinator.updateFailureCode("approval_expired")
            dao.updateTaskState(
                taskId = task.id,
                state = "approved",
                approvedAt = task.approvedAtEpochMs,
                expiresAt = task.approvalExpiresAtEpochMs,
                verificationSummary = summary,
            )
        }
    }

    suspend fun finalConfirm(taskId: String): Result<ExecutionPayload> = runCatching {
        val task = dao.getTask(taskId) ?: error("任务不存在")
        check(task.state == "approved") { "任务已处理或状态已变化" }
        val now = System.currentTimeMillis()
        check(!StandalonePolicy.approvalExpired(task.approvalExpiresAtEpochMs, now)) { "批准已过期" }
        val taskAssets = dao.getTaskAssets(task.id)
        check(taskAssets.size == task.imageCount) { "任务图片缺失" }
        dao.updateTaskState(
            taskId = task.id,
            state = "executing",
            approvedAt = task.approvedAtEpochMs,
            expiresAt = task.approvalExpiresAtEpochMs,
            finalConfirmedAt = now,
            verificationSummary = task.verificationSummary,
        )
        log("info", "task.final_confirmed", "${task.displayName} 已完成第二次确认", task.id)
        ExecutionPayload(
            task.copy(state = "executing", finalConfirmedAtEpochMs = now),
            taskAssets,
            settings.value.copy(
                minDelaySeconds = task.minDelaySeconds,
                maxDelaySeconds = task.maxDelaySeconds,
            ),
        )
    }

    suspend fun cancelFinalConfirmation(taskId: String, reason: String = "用户取消第二次确认") {
        val task = dao.getTask(taskId) ?: return
        dao.updateTaskState(task.id, "presented", failureReason = reason)
        log("info", "task.confirm_cancelled", "${task.displayName}: $reason", task.id)
    }

    suspend fun expireFinalConfirmation(taskId: String) {
        val task = dao.getTask(taskId) ?: return
        if (task.state == "approved") {
            dao.updateTaskState(
                task.id,
                "failed",
                failureReason = "批准已过期",
                failureCode = "approval_expired",
            )
            log("warn", "task.expired", "${task.displayName} 的批准已过期", task.id)
            task.runSessionId?.let { advanceRunSession(it) }
        }
    }

    suspend fun finishExecution(payload: ExecutionPayload, result: AutomationResult) {
        val task = payload.task
        if (result.success) {
            AutomationCoordinator.updateFailureCode(null)
            dao.updateTaskState(
                taskId = task.id,
                state = "sent",
                approvedAt = task.approvedAtEpochMs,
                expiresAt = task.approvalExpiresAtEpochMs,
                finalConfirmedAt = task.finalConfirmedAtEpochMs,
                verificationSummary = result.verificationSummary,
            )
            dao.insertHistory(
                SendHistoryEntity(
                    taskId = task.id,
                    externalUserId = task.externalUserId,
                    displayName = task.displayName,
                    messageText = task.messageText,
                    imageCount = task.imageCount,
                ),
            )
            dao.updateCandidateFollowUp(
                id = task.candidateId,
                lastFollowUpAt = System.currentTimeMillis(),
                nextFollowUpAt = null,
            )
            log("info", "task.sent", "${task.displayName}: ${result.reason}", task.id)
        } else {
            val failureCode = StandalonePolicy.failureCode(result.reason)
            AutomationCoordinator.updateFailureCode(failureCode)
            dao.updateTaskState(
                taskId = task.id,
                state = "failed",
                approvedAt = task.approvedAtEpochMs,
                expiresAt = task.approvalExpiresAtEpochMs,
                finalConfirmedAt = task.finalConfirmedAtEpochMs,
                verificationSummary = result.verificationSummary,
                failureReason = result.reason,
                failureCode = failureCode,
            )
            log("warn", "task.failed", "${task.displayName}: ${result.reason}", task.id)
        }
        task.runSessionId?.let { advanceRunSession(it) }
    }

    suspend fun saveTemplate(
        id: String? = null,
        name: String,
        lines: List<String>,
        randomize: Boolean,
    ): Result<String> = runCatching {
        val cleanLines = lines.map(String::trim).filter(String::isNotBlank)
        require(name.trim().isNotEmpty()) { "模板名称不能为空" }
        StandalonePolicy.chooseMessage(cleanLines, randomize = false)
        val templateId = id ?: UUID.randomUUID().toString()
        val existing = id?.let { dao.getTemplate(it) }
        val now = System.currentTimeMillis()
        dao.replaceTemplate(
            MessageTemplateEntity(
                id = templateId,
                name = name.trim(),
                randomize = randomize,
                enabled = true,
                createdAtEpochMs = existing?.template?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
            ),
            cleanLines.mapIndexed { index, line ->
                TemplateLineEntity(UUID.randomUUID().toString(), templateId, line, index)
            },
        )
        log("info", "template.saved", "话术模板 ${name.trim()} 已保存")
        "话术模板已保存"
    }

    suspend fun deleteTemplate(id: String): Result<String> = runCatching {
        check(templates.value.size > 1) { "至少保留一个话术模板" }
        dao.deleteTemplate(id)
        log("info", "template.deleted", "话术模板已删除")
        "话术模板已删除"
    }

    suspend fun importAssets(uris: List<Uri>): Result<String> = runCatching {
        var imported = 0
        var duplicates = 0
        withContext(Dispatchers.IO) {
            uris.forEach { uri ->
                val asset = assetLibrary.import(uri)
                if (dao.getAssetByHash(asset.sha256) != null) {
                    assetLibrary.delete(asset)
                    duplicates += 1
                } else {
                    dao.insertAsset(asset)
                    imported += 1
                }
            }
        }
        log("info", "asset.imported", "导入 $imported 张图片，重复 $duplicates 张")
        "导入 $imported 张图片，跳过重复 $duplicates 张"
    }

    suspend fun deleteAsset(id: String): Result<String> = runCatching {
        check(dao.countAssetReferences(id) == 0) { "该素材已被任务引用，不能删除" }
        val asset = dao.getAllAssets().firstOrNull { it.id == id } ?: error("素材不存在")
        dao.deleteAsset(id)
        withContext(Dispatchers.IO) { assetLibrary.delete(asset) }
        log("info", "asset.deleted", "${asset.displayName} 已删除")
        "素材已删除"
    }

    suspend fun saveSettings(value: AppSettingsEntity): Result<String> = runCatching {
        require(value.approvalTtlSeconds == 60) { "批准有效期固定为 60 秒" }
        StandalonePolicy.randomDelaySeconds(value.minDelaySeconds, value.maxDelaySeconds, Random(1))
        require(value.dailyContactLimit == 20) { "每日联系上限固定为 20 条" }
        require(value.perUserSevenDayLimit == 2) { "同一用户 7 天联系上限固定为 2 条" }
        val safeValue = value.copy(
            id = 1,
            officialAutoNavigationEnabled = false,
            dailyContactLimit = 20,
            perUserSevenDayLimit = 2,
        )
        dao.saveSettings(safeValue)
        dao.getDefaultProfile()?.let { profile ->
            dao.upsertProfile(
                profile.copy(
                    minDelaySeconds = safeValue.minDelaySeconds,
                    maxDelaySeconds = safeValue.maxDelaySeconds,
                    deduplicateSuccessfulUsers = safeValue.deduplicateSuccessfulUsers,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        log("info", "settings.saved", "本地设置已保存")
        "设置已保存"
    }

    suspend fun exportHistory(output: OutputStream, json: Boolean): Result<String> = runCatching {
        val rows = dao.getHistory()
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            if (json) {
                writer.write("[")
                rows.forEachIndexed { index, row ->
                    if (index > 0) writer.write(",")
                    writer.write(
                        """{"task_id":"${row.taskId.jsonEscape()}","external_user_id":"${row.externalUserId.jsonEscape()}","display_name":"${row.displayName.jsonEscape()}","message":"${row.messageText.jsonEscape()}","image_count":${row.imageCount},"sent_at":"${Instant.ofEpochMilli(row.sentAtEpochMs)}"}""",
                    )
                }
                writer.write("]")
            } else {
                writer.write(
                    CsvCodec.encode(
                        listOf(listOf("task_id", "external_user_id", "display_name", "message", "image_count", "sent_at")) +
                            rows.map { row ->
                                listOf(
                                    row.taskId,
                                    row.externalUserId,
                                    row.displayName,
                                    row.messageText,
                                    row.imageCount.toString(),
                                    Instant.ofEpochMilli(row.sentAtEpochMs).toString(),
                                )
                            },
                    ),
                )
            }
        }
        "发送历史已导出"
    }

    suspend fun exportLogs(output: OutputStream): Result<String> = runCatching {
        val rows = dao.getLogs()
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(
                CsvCodec.encode(
                    listOf(listOf("level", "type", "message", "task_id", "created_at")) +
                        rows.map { row ->
                            listOf(
                                row.level,
                                row.type,
                                row.message,
                                row.taskId.orEmpty(),
                                Instant.ofEpochMilli(row.createdAtEpochMs).toString(),
                            )
                        },
                ),
            )
        }
        "运行日志已导出"
    }

    suspend fun exportBackup(output: OutputStream): Result<String> = runCatching {
        withContext(Dispatchers.IO) { backupManager.export(output) }
        log("info", "backup.exported", "本地明文 ZIP 备份已导出")
        "备份已导出。ZIP 为明文数据，请安全保管"
    }

    suspend fun restoreBackup(
        input: InputStream,
        replace: Boolean,
    ): Result<String> = runCatching {
        check(dao.getActiveRunSession() == null && dao.countGlobalInFlightTasks() == 0) {
            "请先完成或取消当前任务和活动批次"
        }
        val result: BackupRestoreResult = withContext(Dispatchers.IO) {
            backupManager.restore(input, replace)
        }
        initialize()
        log(
            "info",
            "backup.restored",
            "备份已${if (replace) "覆盖" else "合并"}恢复：候选 ${result.candidates}，任务 ${result.tasks}，素材 ${result.assets}",
        )
        "恢复完成：候选 ${result.candidates}，任务 ${result.tasks}，历史 ${result.history}，素材 ${result.assets}"
    }

    suspend fun exportDiagnostics(output: OutputStream): Result<String> = runCatching {
        val latestFailure = dao.getAllTasks().firstOrNull { it.failureCode != null }?.failureCode
        val currentSession = dao.getActiveRunSession()
        val text = buildString {
            appendLine("{")
            appendLine("""  "app_version": "${BuildConfig.VERSION_NAME}",""")
            appendLine("""  "database_version": 5,""")
            appendLine("""  "accessibility_connected": ${AutomationCoordinator.connected.value},""")
            appendLine("""  "adapter_status": "${AutomationCoordinator.adapterStatus.value.jsonEscape()}",""")
            appendLine("""  "target_package": "com.meelive.ingkee",""")
            appendLine("""  "target_version": "9.8.60",""")
            appendLine("""  "official_auto_search_verified": false,""")
            appendLine("""  "official_auto_search_enabled": false,""")
            appendLine("""  "active_session_state": ${currentSession?.state?.let { "\"${it.jsonEscape()}\"" } ?: "null"},""")
            appendLine("""  "latest_failure_code": ${latestFailure?.let { "\"${it.jsonEscape()}\"" } ?: "null"},""")
            appendLine("""  "candidate_count": ${dao.getAllCandidates().size},""")
            appendLine("""  "task_count": ${dao.getAllTasks().size},""")
            appendLine("""  "asset_count": ${dao.getAllAssets().size}""")
            appendLine("}")
        }
        output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        "脱敏诊断 JSON 已导出"
    }

    suspend fun completeOnboarding(): Result<String> = runCatching {
        val current = dao.getSettings() ?: AppSettingsEntity()
        dao.saveSettings(current.copy(onboardingCompleted = true, officialAutoNavigationEnabled = false))
        "首次运行检查已完成"
    }

    suspend fun clearHistoryAndLogs(): Result<String> = runCatching {
        dao.clearHistory()
        dao.clearLogs()
        log("info", "data.cleared", "发送历史和旧日志已彻底清除")
        "发送历史和日志已清除"
    }

    suspend fun updateRelationshipStage(
        candidateId: String,
        stage: String,
        reason: String = "用户手动调整",
    ): Result<String> = runCatching {
        val target = RelationshipStage.entries.firstOrNull { it.value == stage }
            ?: error("关系阶段无效")
        val candidate = dao.getCandidate(candidateId) ?: error("关系用户不存在")
        StandalonePolicy.requireRelationshipStageChange(target.value, candidate.priorityScore)
        val eligibility = if (target == RelationshipStage.DO_NOT_CONTACT) {
            ContactEligibility.DO_NOT_CONTACT.value
        } else if (candidate.contactEligibility in setOf(
                ContactEligibility.DO_NOT_CONTACT.value,
                ContactEligibility.INELIGIBLE.value,
            )
        ) {
            ContactEligibility.MANUAL_CONFIRMED.value
        } else {
            candidate.contactEligibility
        }
        dao.updateCandidateRelationship(
            id = candidate.id,
            stage = target.value,
            eligibility = eligibility,
            lastInteractionAt = candidate.lastInteractionAtEpochMs,
            priorityScore = candidate.priorityScore,
            manualPriorityConfirmed = target == RelationshipStage.PRIORITY,
            doNotContactReason = if (target == RelationshipStage.DO_NOT_CONTACT) reason else null,
        )
        dao.insertStageHistory(
            RelationshipStageHistoryEntity(
                id = UUID.randomUUID().toString(),
                candidateId = candidate.id,
                fromStage = candidate.relationshipStage,
                toStage = target.value,
                reason = reason,
            ),
        )
        "关系阶段已更新"
    }

    suspend fun recordInteraction(
        candidateId: String,
        interactionType: String,
        summary: String,
        source: String,
        occurredAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        val candidate = dao.getCandidate(candidateId) ?: return
        val bucket = occurredAtEpochMs / 30_000L
        val eventId = UUID.nameUUIDFromBytes(
            "${candidate.externalUserId}|$interactionType|${summary.trim()}|$bucket"
                .toByteArray(StandardCharsets.UTF_8),
        ).toString()
        dao.insertInteractionEvent(
            InteractionEventEntity(
                id = eventId,
                candidateId = candidate.id,
                externalUserId = candidate.externalUserId,
                type = interactionType,
                source = source,
                summary = summary.take(160),
                occurredAtEpochMs = occurredAtEpochMs,
            ),
        )
        val events = dao.getInteractionEvents(candidate.id)
        val recent = events.filter { it.occurredAtEpochMs >= occurredAtEpochMs - THIRTY_DAYS_MS }
        val lastInteraction = events.maxOfOrNull(InteractionEventEntity::occurredAtEpochMs)
        val breakdown = StandalonePolicy.priorityBreakdown(
            nowEpochMs = occurredAtEpochMs,
            lastInteractionAtEpochMs = lastInteraction,
            interactionCountInThirtyDays = recent.size,
            followed = events.any { it.type == InteractionType.FOLLOW.value },
            giftEventCount = recent.count { it.type == InteractionType.GIFT.value },
        )
        val lockedStage = candidate.relationshipStage in setOf(
            RelationshipStage.PRIORITY.value,
            RelationshipStage.DO_NOT_CONTACT.value,
        )
        val nextStage = if (lockedStage) {
            candidate.relationshipStage
        } else {
            StandalonePolicy.stageForInteraction(interactionType)
        }
        dao.updateCandidateRelationship(
            id = candidate.id,
            stage = nextStage,
            eligibility = StandalonePolicy.contactEligibilityFor(
                interactionType,
                candidate.relationshipStage == RelationshipStage.DO_NOT_CONTACT.value,
            ),
            lastInteractionAt = lastInteraction,
            priorityScore = breakdown.total,
            manualPriorityConfirmed = candidate.manualPriorityConfirmed,
            doNotContactReason = candidate.doNotContactReason,
        )
        if (nextStage != candidate.relationshipStage) {
            dao.insertStageHistory(
                RelationshipStageHistoryEntity(
                    id = UUID.randomUUID().toString(),
                    candidateId = candidate.id,
                    fromStage = candidate.relationshipStage,
                    toStage = nextStage,
                    reason = "互动事件：$interactionType",
                    changedAtEpochMs = occurredAtEpochMs,
                ),
            )
        }
    }

    private suspend fun contactLimitStatus(externalUserId: String) =
        StandalonePolicy.contactLimitStatus(
            userCountInSevenDays = dao.countSuccessfulSendsSinceForUser(
                externalUserId,
                System.currentTimeMillis() - SEVEN_DAYS_MS,
            ),
            globalCountToday = dao.countSuccessfulSendsSince(startOfTodayEpochMs()),
            perUserLimit = settings.value.perUserSevenDayLimit,
            dailyLimit = settings.value.dailyContactLimit,
        )

    private suspend fun ensureContactAllowed(externalUserId: String) {
        val status = contactLimitStatus(externalUserId)
        check(status.allowed) { status.reason }
    }

    fun assetLibrary(): AssetLibrary = assetLibrary

    private suspend fun advanceRunSession(sessionId: String) {
        val session = dao.getRunSession(sessionId) ?: return
        if (session.state != "running") return
        if (dao.countInFlightTasks(sessionId) > 0) return
        val next = dao.nextQueuedTask(sessionId)
        if (next != null) {
            dao.updateTaskState(next.id, "presented")
            log("info", "run.presented", "批次展示下一位候选：${next.displayName}", next.id)
            return
        }
        StandalonePolicy.requireSessionTransition(session.state, "completed")
        dao.updateRunSessionState(
            sessionId = session.id,
            state = "completed",
            startedAt = session.startedAtEpochMs,
            completedAt = System.currentTimeMillis(),
        )
        log("info", "run.completed", "批次 ${session.profileName} 已完成")
    }

    private suspend fun selectTemplate(requestedId: String?): TemplateWithLines {
        val requested = requestedId?.let { dao.getTemplate(it) }
        return requested?.takeIf { it.template.enabled && it.lines.isNotEmpty() }
            ?: templates.value.firstOrNull { it.template.enabled && it.lines.isNotEmpty() }
            ?: error("没有可用话术模板")
    }

    private suspend fun log(level: String, type: String, message: String, taskId: String? = null) {
        dao.insertLog(RunLogEntity(level = level, type = type, message = message, taskId = taskId))
    }

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    companion object {
        private const val DEFAULT_TEMPLATE_ID = "default-template"
        private const val DEFAULT_PROFILE_ID = "default-profile"
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1_000
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1_000
        private val CONTACT_ELIGIBLE_STATES = setOf(
            ContactEligibility.INTERACTION.value,
            ContactEligibility.MANUAL_CONFIRMED.value,
        )
        private val STARTER_TEMPLATES = linkedMapOf(
            "评论感谢" to listOf(
                "谢谢你今天在直播间陪我聊天，你的留言我看到啦。",
                "刚才的互动很开心，谢谢你愿意来直播间说说话。",
            ),
            "关注感谢" to listOf(
                "谢谢你的关注，之后开播时欢迎再来坐坐。",
                "收到你的关注啦，很高兴以后还能在直播间见到你。",
            ),
            "送礼感谢" to listOf(
                "谢谢你刚才的心意，量力而行就好，来陪伴已经很开心。",
                "礼物收到啦，谢谢支持，也记得照顾好自己、理性互动。",
            ),
            "欢迎回访" to listOf(
                "欢迎回来，今天看到熟悉的名字很亲切。",
                "又见到你啦，最近过得怎么样？有空来直播间聊聊天。",
            ),
            "开播提醒" to listOf(
                "今晚有空会开播，方便时来听听歌聊聊天，不用特意赶。",
                "稍后准备开播，路过的话欢迎进来坐坐。",
            ),
            "日常问候" to listOf(
                "最近还好吗？忙完也记得休息，祝你今天顺利。",
                "节日快乐，愿你最近的生活轻松顺心，有空再见。",
            ),
        )
        private val STARTER_ASSETS = listOf(
            "thanks.png" to "感谢陪伴.png",
            "welcome-back.png" to "欢迎回来.png",
            "tonight.png" to "今晚见.png",
            "daily-greeting.png" to "日常问候.png",
            "seasonal-blessing.png" to "节日祝福.png",
            "next-time.png" to "下次直播见.png",
        )
    }
}

private fun startOfTodayEpochMs(): Long =
    ZonedDateTime.now(ZoneId.systemDefault())
        .toLocalDate()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
