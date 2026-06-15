package com.local.micqueueassistant

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.micqueueassistant.automation.AutomationCoordinator
import com.local.micqueueassistant.domain.IncomingGroupMessage
import com.local.micqueueassistant.transport.PairingTransportManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as MicQueueApp).repository
    val config = repository.config
    val admins = repository.admins
    val bindings = repository.bindings
    val shifts = repository.shifts
    val segments = repository.segments
    val snapshots = repository.snapshots
    val commands = repository.commands
    val exports = repository.exports
    val logs = repository.logs
    val pairedDevices = repository.pairedDevices
    val accessibilityConnected = AutomationCoordinator.connected
    val automationStatus = AutomationCoordinator.status
    val pageType = AutomationCoordinator.pageType
    val visibleSeats = AutomationCoordinator.visibleSeats
    val lastReply = AutomationCoordinator.lastReply
    val transportState = PairingTransportManager.state
    val transportDetail = PairingTransportManager.detail
    val observedFingerprint = PairingTransportManager.observedFingerprint
    val serverFingerprint = PairingTransportManager.serverFingerprint

    private val selectedShiftId = MutableStateFlow<String?>(null)
    val queue = selectedShiftId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeQueue(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow("")
    val message = _message.asStateFlow()

    fun chooseRole(role: String) = runAction { repository.chooseRole(role) }

    fun selectShift(id: String) {
        selectedShiftId.value = id
    }

    fun saveRobotConfig(group: String, owner: String, port: Int) =
        runAction { repository.updateRobotConfig(group, owner, port) }

    fun saveCollectorConfig(host: String, port: Int, code: String) =
        runAction { repository.updateCollectorConfig(host, port, code) }

    fun enrollCloud(baseUrl: String, roomId: String, token: String, deviceName: String) =
        runAction {
            PairingTransportManager.enroll(
                getApplication(),
                baseUrl,
                roomId,
                token,
                deviceName,
            )
        }

    fun disconnectCloud() =
        runAction { PairingTransportManager.disconnect(getApplication()) }

    fun addAdmin(name: String) = runAction { repository.addAdmin(name) }
    fun removeAdmin(id: String) = runAction { repository.removeAdmin(id) }

    fun approveBinding(id: String) = viewModelScope.launch {
        val owner = config.value.ownerWechatName
        show(repository.approveBinding(id, owner))
    }

    fun rejectBinding(id: String) = viewModelScope.launch {
        val owner = config.value.ownerWechatName
        show(repository.rejectBinding(id, owner))
    }

    fun correctSegment(id: String, durationSeconds: Long, reason: String) =
        runAction { repository.correctSegment(id, durationSeconds, reason) }

    fun confirmFingerprint() {
        PairingTransportManager.confirmObservedFingerprint()
        _message.value = "证书指纹确认中，将使用固定指纹重新连接"
    }

    fun simulateCommand(sender: String, text: String) = viewModelScope.launch {
        val group = config.value.groupTitle.ifBlank { "模拟排麦群" }
        show(
            repository.processIncomingMessage(
                IncomingGroupMessage(group, sender, text, System.currentTimeMillis()),
            ).map { it ?: "未知内容未处理" },
        )
    }

    fun exportXlsx(uri: Uri, type: String, key: String) = viewModelScope.launch {
        val result = runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                repository.exportXlsx(output, type, key).getOrThrow()
            } ?: error("无法创建 Excel 文件")
        }
        show(result)
    }

    fun clearMessage() {
        _message.value = ""
    }

    private fun runAction(block: suspend () -> Result<String>) {
        viewModelScope.launch { show(block()) }
    }

    private fun show(result: Result<*>) {
        _message.value = result.fold(
            onSuccess = { it?.toString().orEmpty() },
            onFailure = { it.message ?: "操作失败" },
        )
    }
}
