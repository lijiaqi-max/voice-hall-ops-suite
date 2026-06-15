package com.local.interactionassistant.executor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.interactionassistant.executor.cloud.TaskSubmission
import com.local.interactionassistant.executor.data.CloudConfigEntity
import com.local.interactionassistant.executor.data.CloudTaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemberCloudViewModel : ViewModel() {
    private val repository = ExecutorApp.instance.cloudRepository

    val config: StateFlow<CloudConfigEntity?> = repository.config.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )
    val tasks: StateFlow<List<CloudTaskEntity>> = repository.tasks.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun login(baseUrl: String, username: String, password: String) =
        launch("登录成功，任务已同步") { repository.login(baseUrl, username, password) }

    fun logout() = launch("已退出账号") { repository.logout() }

    fun sync() = launch("同步完成") { repository.sync() }

    fun claim(taskId: String) = launch("作业已领取") { repository.claim(taskId) }

    fun start(taskId: String) = launch("已开始作业") { repository.start(taskId) }

    fun submit(taskId: String, channel: String, note: String, nextFollowUpAt: Long?) =
        launch("作业已提交审核") {
            repository.submit(taskId, TaskSubmission(channel, note, nextFollowUpAt))
        }

    fun uploadLegacy() = launch("旧关系数据已上传到迁移暂存区") {
        repository.uploadLegacyPreview()
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun launch(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching { block() }
                .onSuccess { _message.value = success }
                .onFailure { _message.value = it.message ?: "操作失败" }
            _busy.value = false
        }
    }
}
