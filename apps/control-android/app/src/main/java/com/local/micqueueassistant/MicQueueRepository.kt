package com.local.micqueueassistant

import android.content.Context
import com.local.micqueueassistant.data.AdminEntity
import com.local.micqueueassistant.data.AppConfigEntity
import com.local.micqueueassistant.data.AppDatabase
import com.local.micqueueassistant.data.AuditLogEntity
import com.local.micqueueassistant.data.CommandRecordEntity
import com.local.micqueueassistant.data.CollectorEventEntity
import com.local.micqueueassistant.data.ExportTaskEntity
import com.local.micqueueassistant.data.MemberBindingEntity
import com.local.micqueueassistant.data.MicSegmentEntity
import com.local.micqueueassistant.data.QueueEntryEntity
import com.local.micqueueassistant.data.PairedDeviceEntity
import com.local.micqueueassistant.data.ReplyEntity
import com.local.micqueueassistant.data.SeatSnapshotEntity
import com.local.micqueueassistant.data.ShiftEntity
import com.local.micqueueassistant.domain.CommandParser
import com.local.micqueueassistant.domain.DeviceRole
import com.local.micqueueassistant.domain.GroupCommand
import com.local.micqueueassistant.domain.IncomingGroupMessage
import com.local.micqueueassistant.domain.QueuePolicy
import com.local.micqueueassistant.domain.QueueRole
import com.local.micqueueassistant.domain.SeatSnapshotPayload
import com.local.micqueueassistant.domain.SegmentState
import com.local.micqueueassistant.domain.ShiftState
import com.local.micqueueassistant.domain.TimingEngine
import com.local.micqueueassistant.report.XlsxReportWriter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class MicQueueRepository(
    private val context: Context,
    database: AppDatabase,
) {
    private val dao = database.dao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val timingEngine = TimingEngine(10_000L)

    val config = dao.observeConfig()
        .map { it ?: AppConfigEntity() }
        .stateIn(MicQueueApp.applicationScope, SharingStarted.WhileSubscribed(5_000), AppConfigEntity())
    val admins = dao.observeAdmins()
        .stateIn(MicQueueApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val bindings = dao.observeBindings()
        .stateIn(MicQueueApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shifts = dao.observeShifts()
        .stateIn(MicQueueApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val segments = dao.observeSegments()
        .stateIn(MicQueueApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val snapshots = dao.observeSnapshots()
        .stateIn(MicQueueApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val commands = dao.observeCommands()
        .stateIn(MicQueueApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val exports = dao.observeExportTasks()
        .stateIn(MicQueueApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val logs = dao.observeLogs()
        .stateIn(MicQueueApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pairedDevices = dao.observePairedDevices()
        .stateIn(MicQueueApp.applicationScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun initialize() {
        if (dao.getConfig() == null) dao.saveConfig(AppConfigEntity())
        dao.markAllActiveSegmentsUncertain("应用重启，无法确认离线期间的麦位状态")
    }

    suspend fun chooseRole(role: String): Result<String> = runCatching {
        require(role in DeviceRole.entries.map(DeviceRole::value)) { "设备角色无效" }
        require(role != DeviceRole.UNSELECTED.value) { "请选择设备角色" }
        val current = dao.getConfig() ?: AppConfigEntity()
        dao.saveConfig(current.copy(role = role, foregroundServiceEnabled = false))
        log("role.changed", "设备角色已切换为 $role")
        "设备角色已保存"
    }

    suspend fun updateRobotConfig(
        groupTitle: String,
        ownerWechatName: String,
        port: Int,
    ): Result<String> = runCatching {
        require(groupTitle.trim().length in 2..80) { "群名称长度必须为 2–80" }
        require(ownerWechatName.trim().length in 1..40) { "主人微信昵称不能为空" }
        require(port in 1024..65535) { "端口必须为 1024–65535" }
        val current = dao.getConfig() ?: AppConfigEntity()
        dao.saveConfig(
            current.copy(
                groupTitle = groupTitle.trim(),
                ownerWechatName = ownerWechatName.trim(),
                serverPort = port,
            ),
        )
        dao.saveAdmin(
            AdminEntity(
                id = stableId("admin", ownerWechatName.trim()),
                wechatName = ownerWechatName.trim(),
            ),
        )
        "机器人端配置已保存"
    }

    suspend fun updateCollectorConfig(
        host: String,
        port: Int,
        pairingCode: String,
    ): Result<String> = runCatching {
        require(host.trim().isNotEmpty()) { "机器人端 IP 不能为空" }
        require(port in 1024..65535) { "端口必须为 1024–65535" }
        require(pairingCode.matches(Regex("\\d{6}"))) { "配对码必须为 6 位数字" }
        val current = dao.getConfig() ?: AppConfigEntity()
        dao.saveConfig(
            current.copy(
                collectorHost = host.trim(),
                serverPort = port,
                pairingCode = pairingCode,
            ),
        )
        "采集端连接配置已保存"
    }

    suspend fun saveCloudRegistration(
        baseUrl: String,
        roomId: String,
        deviceId: String,
    ): Result<String> = runCatching {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://")) { "正式环境必须使用 HTTPS 地址" }
        require(roomId.isNotBlank()) { "厅房 ID 不能为空" }
        require(deviceId.isNotBlank()) { "设备 ID 不能为空" }
        val current = dao.getConfig() ?: AppConfigEntity()
        dao.saveConfig(
            current.copy(
                cloudBaseUrl = normalized,
                cloudRoomId = roomId.trim(),
                cloudDeviceId = deviceId,
                cloudSyncEnabled = true,
            ),
        )
        "厅控设备已接入私有云"
    }

    suspend fun markCloudSync(now: Long = System.currentTimeMillis()) {
        val current = dao.getConfig() ?: AppConfigEntity()
        dao.saveConfig(current.copy(cloudLastSyncAtEpochMs = now, cloudSyncEnabled = true))
    }

    suspend fun disableCloudSync() {
        val current = dao.getConfig() ?: AppConfigEntity()
        dao.saveConfig(
            current.copy(
                cloudDeviceId = "",
                cloudSyncEnabled = false,
                cloudLastSyncAtEpochMs = null,
            ),
        )
    }

    suspend fun setPairingCode(code: String): Result<String> = runCatching {
        require(code.matches(Regex("\\d{6}"))) { "配对码必须为 6 位数字" }
        val current = dao.getConfig() ?: AppConfigEntity()
        dao.saveConfig(current.copy(pairingCode = code))
        "配对码已更新"
    }

    suspend fun confirmCertificateFingerprint(fingerprint: String): Result<String> = runCatching {
        require(fingerprint.matches(Regex("[0-9A-Fa-f]{64}"))) { "证书指纹无效" }
        val current = dao.getConfig() ?: AppConfigEntity()
        dao.saveConfig(current.copy(pinnedCertificateSha256 = fingerprint.lowercase()))
        "证书指纹已固定"
    }

    suspend fun setForegroundServiceEnabled(enabled: Boolean) {
        val current = dao.getConfig() ?: AppConfigEntity()
        dao.saveConfig(current.copy(foregroundServiceEnabled = enabled))
    }

    suspend fun addAdmin(name: String): Result<String> = runCatching {
        val clean = name.trim().removePrefix("@")
        require(clean.length in 1..40) { "管理员昵称无效" }
        dao.saveAdmin(AdminEntity(stableId("admin", clean), clean))
        "管理员已添加"
    }

    suspend fun removeAdmin(id: String): Result<String> = runCatching {
        dao.deleteAdmin(id)
        "管理员已移除"
    }

    suspend fun processIncomingMessage(message: IncomingGroupMessage): Result<String?> = runCatching {
        val current = dao.getConfig() ?: AppConfigEntity()
        if (current.role != DeviceRole.ROBOT.value) return@runCatching null
        if (message.groupTitle != current.groupTitle) return@runCatching null
        val parsed = CommandParser.parse(message.text)
        if (parsed.command == null && parsed.error == null) return@runCatching null
        val fingerprint = messageFingerprint(message)
        val commandId = stableId("command", fingerprint)
        val isAdmin = dao.isAdmin(message.senderWechatName) > 0
        val inserted = dao.insertCommand(
            CommandRecordEntity(
                id = commandId,
                fingerprint = fingerprint,
                groupTitle = message.groupTitle,
                senderWechatName = message.senderWechatName,
                rawText = message.text,
                commandType = parsed.command?.javaClass?.simpleName ?: "invalid",
                accepted = false,
                resultMessage = "处理中",
                receivedAtEpochMs = message.observedAtEpochMs,
            ),
        )
        if (inserted == -1L) return@runCatching null
        val execution = if (parsed.error != null) {
            Result.failure(IllegalArgumentException(parsed.error))
        } else {
            runCatching {
                executeCommand(
                    parsed.command!!,
                    message.senderWechatName,
                    isAdmin,
                    message.observedAtEpochMs,
                )
            }
        }
        val response = execution.fold(
            onSuccess = { it },
            onFailure = { it.message ?: "指令处理失败" },
        )
        dao.updateCommandResult(commandId, execution.isSuccess, response)
        dao.insertReply(
            ReplyEntity(
                id = UUID.randomUUID().toString(),
                sourceCommandId = commandId,
                groupTitle = message.groupTitle,
                message = response,
            ),
        )
        response
    }

    private suspend fun executeCommand(
        command: GroupCommand,
        sender: String,
        isAdmin: Boolean,
        now: Long,
    ): String = when (command) {
        is GroupCommand.OpenShift -> {
            requireAdmin(isAdmin)
            check(dao.findOverlappingShift(command.startAtEpochMs, command.endAtEpochMs) == null) {
                "该时段与现有班次重叠"
            }
            val shift = ShiftEntity(
                id = UUID.randomUUID().toString(),
                label = shiftLabel(command.startAtEpochMs, command.endAtEpochMs),
                startAtEpochMs = command.startAtEpochMs,
                endAtEpochMs = command.endAtEpochMs,
                capacity = command.capacity,
                cutoffAtEpochMs = command.cutoffAtEpochMs,
                state = ShiftState.OPEN.value,
                createdBy = sender,
            )
            dao.saveShift(shift)
            QueuePolicy.formatQueue(shift, emptyList())
        }
        is GroupCommand.Join -> {
            val shift = resolveShift(command.shiftLabel) ?: error("没有可补排的开放班次")
            check(shift.state == ShiftState.OPEN.value) { "当前班次已截止" }
            check(now <= shift.cutoffAtEpochMs) { "当前班次已超过补排截止时间" }
            val queue = dao.getQueue(shift.id)
            val updated = QueuePolicy.insertParticipant(
                existing = queue,
                shiftId = shift.id,
                name = sender,
                capacity = shift.capacity,
                id = UUID.randomUUID().toString(),
                createdBy = sender,
                now = now,
            )
            dao.saveQueueEntry(updated.last())
            QueuePolicy.formatQueue(shift, dao.getQueue(shift.id))
        }
        GroupCommand.Cancel -> {
            val shift = dao.getOpenShift() ?: error("没有开放班次")
            check(dao.cancelQueueEntry(shift.id, sender) > 0) { "你不在当前麦序中" }
            val compacted = QueuePolicy.compact(dao.getQueue(shift.id), now)
            dao.replaceQueue(compacted)
            QueuePolicy.formatQueue(shift, dao.getQueue(shift.id))
        }
        is GroupCommand.InsertHost -> {
            requireAdmin(isAdmin)
            val shift = dao.getOpenShift() ?: error("没有开放班次")
            val updated = QueuePolicy.insertHost(
                existing = dao.getQueue(shift.id),
                shiftId = shift.id,
                name = command.wechatName,
                requestedPosition = command.position,
                id = UUID.randomUUID().toString(),
                createdBy = sender,
                now = now,
            )
            dao.replaceQueue(updated.filter { it.id != updated.last().id })
            dao.saveQueueEntry(updated.last())
            QueuePolicy.formatQueue(shift, dao.getQueue(shift.id))
        }
        GroupCommand.CloseShift -> {
            requireAdmin(isAdmin)
            val shift = dao.getOpenShift() ?: error("没有开放班次")
            dao.updateShiftState(shift.id, ShiftState.CLOSED.value)
            QueuePolicy.formatQueue(shift.copy(state = ShiftState.CLOSED.value), dao.getQueue(shift.id))
        }
        GroupCommand.ShowQueue -> {
            val shift = dao.getOpenShift()
                ?: dao.getAllShifts().firstOrNull { it.state == ShiftState.CLOSED.value }
                ?: error("暂无班次")
            QueuePolicy.formatQueue(shift, dao.getQueue(shift.id))
        }
        is GroupCommand.Bind -> {
            val existing = dao.bindingForWechat(sender)
            val binding = MemberBindingEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                wechatName = sender,
                ingkeeName = command.ingkeeName,
                state = "pending",
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
            )
            dao.saveBinding(binding)
            "绑定申请已记录：微信 @$sender → 映客 ${command.ingkeeName}，等待管理员确认"
        }
        is GroupCommand.Total -> {
            val target = command.targetWechatName ?: sender
            if (target != sender) requireAdmin(isAdmin)
            formatTotal(target)
        }
        is GroupCommand.Export -> {
            requireAdmin(isAdmin)
            val task = ExportTaskEntity(
                id = UUID.randomUUID().toString(),
                periodType = command.periodType,
                periodKey = command.periodKey,
                fileName = "麦时统计-${command.periodType}-${command.periodKey}.xlsx",
            )
            dao.saveExportTask(task)
            "导表任务已创建，请在机器人端“报表”页选择保存位置并导出 ${task.fileName}"
        }
        GroupCommand.Help -> HELP_TEXT
    }

    suspend fun approveBinding(id: String, adminName: String): Result<String> = runCatching {
        check(dao.isAdmin(adminName) > 0) { "只有管理员可以确认绑定" }
        val binding = bindings.value.firstOrNull { it.id == id } ?: error("绑定不存在")
        dao.updateBindingState(id, "approved", adminName)
        val queue = shifts.value.firstOrNull()?.let { dao.activeQueueEntry(it.id, binding.wechatName) }
        dao.backfillBinding(
            ingkeeName = binding.ingkeeName,
            bindingId = binding.id,
            wechatName = binding.wechatName,
            role = queue?.role ?: QueueRole.PARTICIPANT.value,
            queuePosition = queue?.position,
        )
        "绑定已确认，未匹配麦时已回填"
    }

    suspend fun rejectBinding(id: String, adminName: String): Result<String> = runCatching {
        check(dao.isAdmin(adminName) > 0) { "只有管理员可以拒绝绑定" }
        dao.updateBindingState(id, "rejected", adminName)
        "绑定已拒绝"
    }

    suspend fun applySeatSnapshot(payload: SeatSnapshotPayload): Result<String> = runCatching {
        val inserted = dao.insertSnapshot(
            SeatSnapshotEntity(
                id = UUID.randomUUID().toString(),
                eventId = payload.eventId,
                seatsJson = json.encodeToString(payload.seats),
                source = payload.source,
                pageStatus = payload.pageStatus,
                capturedAtEpochMs = payload.capturedAtEpochMs,
            ),
        )
        if (inserted == -1L) return@runCatching "重复麦位事件已忽略"
        val readable = payload.pageStatus == "voice_room"
        val actions = if (readable) {
            timingEngine.acceptSnapshot(
                payload.seats.map { it.ingkeeName }.toSet(),
                payload.capturedAtEpochMs,
                true,
            )
        } else {
            timingEngine.reset()
        }
        actions.forEach { action ->
            when (action.type) {
                "start" -> startSegment(action.ingkeeName, action.atEpochMs)
                "seen" -> dao.activeSegmentFor(action.ingkeeName)?.let {
                    dao.touchSegment(it.id, action.atEpochMs)
                }
                "end" -> dao.activeSegmentFor(action.ingkeeName)?.let {
                    dao.closeSegment(
                        id = it.id,
                        endedAtEpochMs = action.atEpochMs,
                        lastSeenAtEpochMs = it.lastSeenAtEpochMs,
                        state = SegmentState.CLOSED.value,
                        reason = null,
                    )
                }
                "uncertain" -> dao.activeSegmentFor(action.ingkeeName)?.let {
                    dao.closeSegment(
                        id = it.id,
                        endedAtEpochMs = it.lastSeenAtEpochMs,
                        lastSeenAtEpochMs = it.lastSeenAtEpochMs,
                        state = SegmentState.UNCERTAIN.value,
                        reason = "页面不可读或采集连接中断",
                    )
                }
            }
        }
        "麦位快照已处理：${payload.seats.size} 个可见席位"
    }

    private suspend fun startSegment(ingkeeName: String, atEpochMs: Long) {
        if (dao.activeSegmentFor(ingkeeName) != null) return
        val binding = dao.approvedBindingForIngkee(ingkeeName)
        val shift = dao.getShiftAt(atEpochMs)
        val queue = if (binding != null && shift != null) {
            dao.activeQueueEntry(shift.id, binding.wechatName)
        } else {
            null
        }
        dao.saveSegment(
            MicSegmentEntity(
                id = UUID.randomUUID().toString(),
                shiftId = shift?.id,
                bindingId = binding?.id,
                wechatName = binding?.wechatName,
                ingkeeName = ingkeeName,
                role = queue?.role ?: QueueRole.PARTICIPANT.value,
                queuePosition = queue?.position,
                startedAtEpochMs = atEpochMs,
                lastSeenAtEpochMs = atEpochMs,
                endedAtEpochMs = null,
                durationSeconds = 0,
            ),
        )
    }

    suspend fun markTransportInterrupted(reason: String) {
        timingEngine.reset()
        dao.markAllActiveSegmentsUncertain(reason)
        log("transport.interrupted", reason, level = "warning")
    }

    suspend fun enqueueCollectorEvent(
        eventId: String,
        payloadJson: String,
    ): CollectorEventEntity {
        val row = CollectorEventEntity(
            id = UUID.randomUUID().toString(),
            eventId = eventId,
            type = "seat_snapshot",
            payloadJson = payloadJson,
        )
        val inserted = dao.insertCollectorEvent(row)
        return if (inserted == -1L) {
            dao.pendingCollectorEvents().firstOrNull { it.eventId == eventId } ?: row
        } else {
            row
        }
    }

    suspend fun pendingCollectorEvents(): List<CollectorEventEntity> =
        dao.pendingCollectorEvents()

    suspend fun markCollectorEventSent(id: String) = dao.markCollectorEventSent(id)

    suspend fun registerPairedDevice(
        deviceId: String,
        displayName: String,
        role: String,
        certificateSha256: String,
    ) {
        dao.savePairedDevice(
            PairedDeviceEntity(
                id = stableId("device", deviceId),
                deviceId = deviceId,
                displayName = displayName,
                role = role,
                certificateSha256 = certificateSha256,
                lastSeenAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun nextPendingReply(): ReplyEntity? = dao.nextPendingReply()

    suspend fun updateReplyState(
        id: String,
        state: String,
        incrementAttempt: Boolean = false,
        failureReason: String? = null,
    ) {
        dao.updateReplyState(id, state, if (incrementAttempt) 1 else 0, failureReason)
    }

    suspend fun getQueue(shiftId: String): List<QueueEntryEntity> = dao.getQueue(shiftId)

    fun observeQueue(shiftId: String): Flow<List<QueueEntryEntity>> = dao.observeQueue(shiftId)

    suspend fun segmentsBetween(startAt: Long, endAt: Long): List<MicSegmentEntity> =
        dao.segmentsBetween(startAt, endAt)

    suspend fun saveExportTask(task: ExportTaskEntity) = dao.saveExportTask(task)

    suspend fun exportXlsx(
        output: OutputStream,
        periodType: String,
        periodKey: String,
    ): Result<String> = runCatching {
        val period = XlsxReportWriter.resolvePeriod(periodType, periodKey)
        val rows = dao.segmentsBetween(period.startAtEpochMs, period.endAtEpochMs)
        XlsxReportWriter.write(
            output = output,
            period = period,
            segments = rows,
            shifts = dao.getAllShifts(),
            queueEntries = dao.getAllQueueEntries(),
            auditLogs = dao.getAllLogs(),
        )
        dao.saveExportTask(
            ExportTaskEntity(
                id = UUID.randomUUID().toString(),
                periodType = periodType,
                periodKey = periodKey,
                state = "completed",
                fileName = "麦时统计-$periodType-$periodKey.xlsx",
                completedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        "Excel 已生成：麦时明细、日汇总、月汇总、异常记录"
    }

    suspend fun correctSegment(
        id: String,
        durationSeconds: Long,
        reason: String,
    ): Result<String> = runCatching {
        require(durationSeconds >= 0) { "修正时长不能小于 0 秒" }
        require(reason.trim().length in 2..200) { "请填写 2–200 字修正原因" }
        check(dao.correctSegment(id, durationSeconds, reason.trim()) > 0) { "麦时记录不存在" }
        log("segment.corrected", "麦时已手工修正为 $durationSeconds 秒：${reason.trim()}", id)
        "麦时记录已修正"
    }

    suspend fun log(
        type: String,
        message: String,
        relatedId: String? = null,
        level: String = "info",
    ) {
        dao.log(AuditLogEntity(UUID.randomUUID().toString(), level, type, message, relatedId))
    }

    private suspend fun resolveShift(label: String?): ShiftEntity? {
        if (label.isNullOrBlank()) return dao.getOpenShift()
        return dao.getAllShifts().firstOrNull {
            it.state == ShiftState.OPEN.value && it.label.contains(label.trim())
        }
    }

    private fun requireAdmin(isAdmin: Boolean) {
        check(isAdmin) { "该指令仅管理员可执行" }
    }

    private fun formatTotal(wechatName: String): String {
        val rows = segments.value.filter { it.wechatName == wechatName }
        val ordinary = rows.filter { it.role == QueueRole.PARTICIPANT.value }.sumOf { it.durationSeconds }
        val host = rows.filter { it.role == QueueRole.HOST.value }.sumOf { it.durationSeconds }
        return "@$wechatName 累计：普通麦时 ${formatDuration(ordinary)}，主持麦时 ${formatDuration(host)}，共 ${rows.size} 段"
    }

    private fun formatDuration(seconds: Long): String =
        "%d小时%02d分%02d秒".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)

    private fun shiftLabel(startAt: Long, endAt: Long): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(startAt).atZone(zone).format(formatter)
        val end = Instant.ofEpochMilli(endAt).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        return "$start-$end"
    }

    private fun messageFingerprint(message: IncomingGroupMessage): String {
        val secondBucket = message.observedAtEpochMs / 1_000L
        return sha256(
            "${message.groupTitle}|${message.senderWechatName}|${message.text.trim()}|$secondBucket",
        )
    }

    private fun stableId(prefix: String, value: String): String =
        UUID.nameUUIDFromBytes("$prefix|$value".toByteArray(StandardCharsets.UTF_8)).toString()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val HELP_TEXT =
            "可用指令：补、补 时段、取消、麦序、绑定 映客昵称、累计、帮助。管理员另可使用：开排 日期 开始-结束 麦位数 截止时间、插主持 @昵称 序号、截止、累计 @昵称、导表 日/周/月 日期。"
    }
}
