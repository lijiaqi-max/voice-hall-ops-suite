package com.local.interactionassistant.executor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.interactionassistant.executor.automation.AutomationCoordinator
import com.local.interactionassistant.executor.data.AppSettingsEntity
import com.local.interactionassistant.executor.data.CandidateEntity
import com.local.interactionassistant.executor.data.CandidateImportPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ExecutorApp).repository
    val candidates = repository.candidates
    val tasks = repository.activeTasks
    val taskAssets = repository.activeTaskAssets
    val allTasks = repository.allTasks
    val templates = repository.templates
    val assets = repository.assets
    val history = repository.history
    val interactionEvents = repository.interactionEvents
    val logs = repository.logs
    val settings = repository.settings
    val blacklist = repository.blacklist
    val profiles = repository.profiles
    val blockRules = repository.blockRules
    val runDashboard = repository.runDashboard
    val runSessions = repository.runSessions
    val dailyContactStats = repository.dailyContactStats
    val accessibilityConnected = AutomationCoordinator.connected
    val latestScan = AutomationCoordinator.latestScan
    val adapterStatus = AutomationCoordinator.adapterStatus
    val adapterPageType = AutomationCoordinator.pageType
    val adapterCapabilities = AutomationCoordinator.capabilities
    val lastFailureCode = AutomationCoordinator.lastFailureCode

    private val _actionMessage = MutableStateFlow("")
    val actionMessage = _actionMessage.asStateFlow()
    private val _manualExternalId = MutableStateFlow("")
    val manualExternalId = _manualExternalId.asStateFlow()
    private val _manualDisplayName = MutableStateFlow("")
    val manualDisplayName = _manualDisplayName.asStateFlow()
    private val _manualNote = MutableStateFlow("")
    val manualNote = _manualNote.asStateFlow()
    private val _manualGender = MutableStateFlow("unknown")
    val manualGender = _manualGender.asStateFlow()
    private val _manualConsumption = MutableStateFlow("")
    val manualConsumption = _manualConsumption.asStateFlow()
    private val _manualImageCount = MutableStateFlow("0")
    val manualImageCount = _manualImageCount.asStateFlow()
    private val _manualTemplateId = MutableStateFlow("")
    val manualTemplateId = _manualTemplateId.asStateFlow()
    private val _pasteContent = MutableStateFlow("")
    val pasteContent = _pasteContent.asStateFlow()
    private val _importPreview = MutableStateFlow<CandidateImportPreview?>(null)
    val importPreview = _importPreview.asStateFlow()
    private val _profileEditorAssetIds = MutableStateFlow<Set<String>>(emptySet())
    val profileEditorAssetIds = _profileEditorAssetIds.asStateFlow()

    private val _filterGender = MutableStateFlow("all")
    val filterGender = _filterGender.asStateFlow()
    private val _filterSource = MutableStateFlow("all")
    val filterSource = _filterSource.asStateFlow()
    private val _filterMinConsumption = MutableStateFlow("")
    val filterMinConsumption = _filterMinConsumption.asStateFlow()
    private val _filterMaxConsumption = MutableStateFlow("")
    val filterMaxConsumption = _filterMaxConsumption.asStateFlow()

    val filteredCandidates = combine(
        candidates,
        _filterGender,
        _filterSource,
        _filterMinConsumption,
        _filterMaxConsumption,
    ) { rows, gender, source, min, max ->
        rows.filter { candidate ->
            StandalonePolicy.candidateMatches(
                    candidate = candidate,
                    gender = gender,
                    minConsumption = min.toLongOrNull(),
                    maxConsumption = max.toLongOrNull(),
                    source = source,
                )
        }.sortedByDescending(CandidateEntity::priorityScore)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setManualExternalId(value: String) { _manualExternalId.value = value }
    fun setManualDisplayName(value: String) { _manualDisplayName.value = value }
    fun setManualNote(value: String) { _manualNote.value = value }
    fun setManualGender(value: String) { _manualGender.value = value }
    fun setManualConsumption(value: String) { _manualConsumption.value = value }
    fun setManualImageCount(value: String) { _manualImageCount.value = value.filter(Char::isDigit).take(1) }
    fun setManualTemplateId(value: String) { _manualTemplateId.value = value }
    fun setPasteContent(value: String) { _pasteContent.value = value }
    fun setFilterGender(value: String) { _filterGender.value = value }
    fun setFilterSource(value: String) { _filterSource.value = value }
    fun setFilterMinConsumption(value: String) { _filterMinConsumption.value = value }
    fun setFilterMaxConsumption(value: String) { _filterMaxConsumption.value = value }

    fun addManualCandidate() {
        viewModelScope.launch {
            val result = repository.addCandidate(
                externalUserId = _manualExternalId.value,
                displayName = _manualDisplayName.value,
                note = _manualNote.value,
                source = "manual",
                gender = _manualGender.value.takeUnless { it == "unknown" },
                consumption = _manualConsumption.value.toLongOrNull(),
                templateId = _manualTemplateId.value.ifBlank { null },
                imageCount = _manualImageCount.value.toIntOrNull() ?: 0,
            )
            show(result, "添加候选失败")
            if (result.isSuccess) {
                _manualExternalId.value = ""
                _manualDisplayName.value = ""
                _manualNote.value = ""
                _manualConsumption.value = ""
                _manualImageCount.value = "0"
                _manualTemplateId.value = ""
            }
        }
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: error("无法读取 CSV 文件")
                val preview = repository.previewCandidates(text, csv = true).getOrThrow()
                _importPreview.value = preview
                "导入预览：有效 ${preview.validCount}，重复 ${preview.duplicateCount}，屏蔽 ${preview.blockedCount}，错误 ${preview.errorCount}"
            }
            show(result, "CSV 预览失败")
        }
    }

    fun previewPastedCandidates() {
        viewModelScope.launch {
            val result = repository.previewCandidates(_pasteContent.value, csv = false)
            result.onSuccess { _importPreview.value = it }
            show(
                result.map {
                    "粘贴预览：有效 ${it.validCount}，重复 ${it.duplicateCount}，屏蔽 ${it.blockedCount}，错误 ${it.errorCount}"
                },
                "粘贴内容预览失败",
            )
        }
    }

    fun commitImportPreview() {
        val preview = _importPreview.value ?: return
        viewModelScope.launch {
            val result = repository.commitImportPreview(preview)
            show(result, "导入候选失败")
            if (result.isSuccess) {
                _importPreview.value = null
                _pasteContent.value = ""
            }
        }
    }

    fun clearImportPreview() {
        _importPreview.value = null
    }

    fun scanNow() {
        viewModelScope.launch { show(repository.scanNow(), "页面扫描失败") }
    }

    fun importLatestScan() {
        viewModelScope.launch { show(repository.importLatestScan(), "扫描候选导入失败") }
    }

    fun createTask(candidateId: String) {
        viewModelScope.launch { show(repository.createTask(candidateId), "创建任务失败") }
    }

    fun skipCandidate(candidateId: String) {
        viewModelScope.launch { show(repository.skipCandidate(candidateId), "跳过候选失败") }
    }

    fun blacklistCandidate(candidateId: String) {
        viewModelScope.launch { show(repository.blacklistCandidate(candidateId), "加入黑名单失败") }
    }

    fun updateRelationshipStage(candidateId: String, stage: String) {
        viewModelScope.launch {
            show(repository.updateRelationshipStage(candidateId, stage), "更新关系阶段失败")
        }
    }

    fun removeBlacklist(externalUserId: String) {
        viewModelScope.launch { show(repository.removeBlacklist(externalUserId), "移出黑名单失败") }
    }

    fun editTask(taskId: String, message: String) {
        viewModelScope.launch { show(repository.updateTaskMessage(taskId, message), "更新话术失败") }
    }

    fun approveTask(taskId: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repository.approveTask(taskId)
            show(result, "批准任务失败")
            onComplete(result.isSuccess)
        }
    }

    fun saveAndApproveTask(taskId: String, message: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repository.saveAndApproveTask(taskId, message)
            show(result, "保存并批准任务失败")
            onComplete(result.isSuccess)
            if (result.isSuccess) {
                delay(800)
                val task = allTasks.value.firstOrNull { it.id == taskId }
                if (task != null) {
                    val navigation = AutomationCoordinator.navigate(task.externalUserId)
                    _actionMessage.value = navigation.reason
                }
            }
        }
    }

    fun updateTaskAssets(taskId: String, assetIds: List<String>) {
        viewModelScope.launch {
            show(repository.updateTaskAssets(taskId, assetIds), "更新任务图片失败")
        }
    }

    fun randomizeTaskAssets(taskId: String) {
        viewModelScope.launch {
            show(repository.randomizeTaskAssets(taskId), "随机更换任务图片失败")
        }
    }

    fun skipTask(taskId: String) {
        viewModelScope.launch { show(repository.skipTask(taskId), "跳过任务失败") }
    }

    fun startRunSession(profileId: String) {
        viewModelScope.launch { show(repository.startRunSession(profileId), "启动批次失败") }
    }

    fun pauseRunSession(sessionId: String) {
        viewModelScope.launch { show(repository.pauseRunSession(sessionId), "暂停批次失败") }
    }

    fun resumeRunSession(sessionId: String) {
        viewModelScope.launch { show(repository.resumeRunSession(sessionId), "恢复批次失败") }
    }

    fun cancelRunSession(sessionId: String) {
        viewModelScope.launch { show(repository.cancelRunSession(sessionId), "取消批次失败") }
    }

    fun retryFailedTask(taskId: String) {
        viewModelScope.launch { show(repository.retryFailedTask(taskId), "创建重试任务失败") }
    }

    fun saveProfile(
        id: String?,
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
        assetIds: List<String>,
    ) {
        viewModelScope.launch {
            show(
                repository.saveProfile(
                    id,
                    name,
                    templateId,
                    genderFilter,
                    minConsumption,
                    maxConsumption,
                    sourceFilter,
                    unknownFieldPolicy,
                    imagePolicy,
                    imageCount,
                    minDelaySeconds,
                    maxDelaySeconds,
                    deduplicateSuccessfulUsers,
                    assetIds,
                ),
                "保存运行预设失败",
            )
        }
    }

    fun setDefaultProfile(profileId: String) {
        viewModelScope.launch { show(repository.setDefaultProfile(profileId), "设置默认预设失败") }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch { show(repository.deleteProfile(profileId), "删除运行预设失败") }
    }

    fun loadProfileEditorAssets(profileId: String?) {
        if (profileId == null) {
            _profileEditorAssetIds.value = emptySet()
            return
        }
        viewModelScope.launch {
            _profileEditorAssetIds.value = repository.getProfileAssetIds(profileId).toSet()
        }
    }

    fun toggleProfileEditorAsset(assetId: String, selected: Boolean) {
        _profileEditorAssetIds.value = if (selected) {
            (_profileEditorAssetIds.value + assetId).take(3).toSet()
        } else {
            _profileEditorAssetIds.value - assetId
        }
    }

    fun addBlockRule(type: String, value: String) {
        viewModelScope.launch { show(repository.addBlockRule(type, value), "添加屏蔽规则失败") }
    }

    fun deleteBlockRule(id: String) {
        viewModelScope.launch { show(repository.deleteBlockRule(id), "删除屏蔽规则失败") }
    }

    fun setBlockRuleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            show(repository.setBlockRuleEnabled(id, enabled), "更新屏蔽规则失败")
        }
    }

    fun saveTemplate(
        id: String?,
        name: String,
        lines: String,
        randomize: Boolean,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = repository.saveTemplate(id, name, lines.lineSequence().toList(), randomize)
            show(result, "保存模板失败")
            onComplete(result.isSuccess)
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch { show(repository.deleteTemplate(id), "删除模板失败") }
    }

    fun importAssets(uris: List<Uri>) {
        viewModelScope.launch { show(repository.importAssets(uris), "导入图片失败") }
    }

    fun deleteAsset(id: String) {
        viewModelScope.launch { show(repository.deleteAsset(id), "删除图片失败") }
    }

    fun saveSettings(value: AppSettingsEntity) {
        viewModelScope.launch { show(repository.saveSettings(value), "保存设置失败") }
    }

    fun exportHistory(uri: Uri, json: Boolean) {
        viewModelScope.launch {
            val result = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                    repository.exportHistory(output, json).getOrThrow()
                } ?: error("无法创建导出文件")
            }
            show(result, "导出历史失败")
        }
    }

    fun exportLogs(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                    repository.exportLogs(output).getOrThrow()
                } ?: error("无法创建导出文件")
            }
            show(result, "导出日志失败")
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                    repository.exportBackup(output).getOrThrow()
                } ?: error("无法创建备份文件")
            }
            show(result, "导出备份失败")
        }
    }

    fun restoreBackup(uri: Uri, replace: Boolean) {
        viewModelScope.launch {
            val result = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    repository.restoreBackup(input, replace).getOrThrow()
                } ?: error("无法读取备份文件")
            }
            show(result, "恢复备份失败")
        }
    }

    fun exportDiagnostics(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                    repository.exportDiagnostics(output).getOrThrow()
                } ?: error("无法创建诊断文件")
            }
            show(result, "导出诊断失败")
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { show(repository.completeOnboarding(), "更新教程状态失败") }
    }

    fun clearHistoryAndLogs() {
        viewModelScope.launch { show(repository.clearHistoryAndLogs(), "清除数据失败") }
    }

    fun clearActionMessage() {
        _actionMessage.value = ""
    }

    private fun <T> show(result: Result<T>, fallback: String) {
        _actionMessage.value = result.fold(
            onSuccess = { it.toString() },
            onFailure = { it.message ?: fallback },
        )
    }
}
