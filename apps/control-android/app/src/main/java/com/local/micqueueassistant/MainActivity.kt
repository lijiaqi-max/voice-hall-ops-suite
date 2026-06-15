package com.local.micqueueassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.local.micqueueassistant.data.MemberBindingEntity
import com.local.micqueueassistant.data.MicSegmentEntity
import com.local.micqueueassistant.data.QueueEntryEntity
import com.local.micqueueassistant.data.ShiftEntity
import com.local.micqueueassistant.domain.DeviceRole
import com.local.micqueueassistant.transport.PairingForegroundService
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MicQueueAppScreen(
                        viewModel = viewModel,
                        openAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        openTarget = ::openTargetApp,
                        startTransport = ::startTransport,
                        stopTransport = ::stopTransport,
                    )
                }
            }
        }
    }

    private fun startTransport() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ContextCompat.startForegroundService(this, Intent(this, PairingForegroundService::class.java))
    }

    private fun stopTransport() {
        stopService(Intent(this, PairingForegroundService::class.java))
    }

    private fun openTargetApp() {
        val role = viewModel.config.value.role
        val targets = when (role) {
            DeviceRole.COLLECTOR.value -> listOf(
                "com.meelive.ingkee",
                "com.local.micqueueassistant.mocktarget",
            )
            else -> listOf(
                "com.tencent.mm",
                "com.local.micqueueassistant.mocktarget",
            )
        }.let { if (BuildConfig.DEBUG) it.reversed() else it }
        targets.firstNotNullOfOrNull(packageManager::getLaunchIntentForPackage)?.let(::startActivity)
    }
}

private enum class RobotPage(val title: String) {
    DASHBOARD("运行看板"),
    SHIFTS("班次队列"),
    BINDINGS("成员绑定"),
    SEGMENTS("麦时记录"),
    REPORTS("报表"),
    SETTINGS("设置"),
}

private enum class CollectorPage(val title: String) {
    STATUS("连接状态"),
    SEATS("当前麦位"),
    SEGMENTS("活动计时"),
    ANOMALIES("异常"),
    SETTINGS("设置"),
}

@Composable
private fun MicQueueAppScreen(
    viewModel: MainViewModel,
    openAccessibility: () -> Unit,
    openTarget: () -> Unit,
    startTransport: () -> Unit,
    stopTransport: () -> Unit,
) {
    val config by viewModel.config.collectAsState()
    val message by viewModel.message.collectAsState()
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startTransport() }
    var pendingRole by remember { mutableStateOf<String?>(null) }
    pendingRole?.let { role ->
        AlertDialog(
            onDismissRequest = { pendingRole = null },
            title = { Text("切换设备角色？") },
            text = { Text("切换角色会停止当前连接服务。确认后需要重新启动对应服务。") },
            confirmButton = {
                Button(onClick = {
                    stopTransport()
                    viewModel.chooseRole(role)
                    pendingRole = null
                }) { Text("确认切换") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRole = null }) { Text("取消") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("麦序统计机器人", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "1.0.0 · 私有云同步 · 本地断线队列",
                color = Color(0xFF667085),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when (config.role) {
                DeviceRole.ROBOT.value -> RobotApp(
                    viewModel,
                    openAccessibility,
                    openTarget,
                    startTransport = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else startTransport()
                    },
                    stopTransport,
                    switchRole = { pendingRole = DeviceRole.COLLECTOR.value },
                )
                DeviceRole.COLLECTOR.value -> CollectorApp(
                    viewModel,
                    openAccessibility,
                    openTarget,
                    startTransport = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else startTransport()
                    },
                    stopTransport,
                    switchRole = { pendingRole = DeviceRole.ROBOT.value },
                )
                else -> RoleSelection(
                    chooseRobot = { viewModel.chooseRole(DeviceRole.ROBOT.value) },
                    chooseCollector = { viewModel.chooseRole(DeviceRole.COLLECTOR.value) },
                )
            }
        }
        if (message.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(message, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearMessage) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun RoleSelection(chooseRobot: () -> Unit, chooseCollector: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("选择这台手机的角色", style = MaterialTheme.typography.titleLarge)
        Text("同一个 APK 安装到两台手机。机器人端常驻微信，采集端常驻映客语音房。")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("微信机器人端", fontWeight = FontWeight.Bold)
                Text("管理单群排麦、主持插入、绑定审批、麦时账本和 Excel。")
                Button(onClick = chooseRobot) { Text("设为机器人端") }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("映客采集端", fontWeight = FontWeight.Bold)
                Text("保持映客 9.8.60 语音房在前台，持续识别麦位并同步。")
                Button(onClick = chooseCollector) { Text("设为采集端") }
            }
        }
    }
}

@Composable
private fun RobotApp(
    viewModel: MainViewModel,
    openAccessibility: () -> Unit,
    openTarget: () -> Unit,
    startTransport: () -> Unit,
    stopTransport: () -> Unit,
    switchRole: () -> Unit,
) {
    var page by remember { mutableStateOf(RobotPage.DASHBOARD) }
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = page.ordinal, edgePadding = 8.dp) {
            RobotPage.entries.forEach { item ->
                Tab(page == item, { page = item }, text = { Text(item.title) })
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (page) {
                RobotPage.DASHBOARD -> RobotDashboard(viewModel, openTarget, startTransport, stopTransport)
                RobotPage.SHIFTS -> ShiftsScreen(viewModel)
                RobotPage.BINDINGS -> BindingsScreen(viewModel)
                RobotPage.SEGMENTS -> SegmentsScreen(viewModel, allowCorrection = true)
                RobotPage.REPORTS -> ReportsScreen(viewModel)
                RobotPage.SETTINGS -> RobotSettings(
                    viewModel,
                    openAccessibility,
                    openTarget,
                    switchRole,
                )
            }
        }
    }
}

@Composable
private fun CollectorApp(
    viewModel: MainViewModel,
    openAccessibility: () -> Unit,
    openTarget: () -> Unit,
    startTransport: () -> Unit,
    stopTransport: () -> Unit,
    switchRole: () -> Unit,
) {
    var page by remember { mutableStateOf(CollectorPage.STATUS) }
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = page.ordinal, edgePadding = 8.dp) {
            CollectorPage.entries.forEach { item ->
                Tab(page == item, { page = item }, text = { Text(item.title) })
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (page) {
                CollectorPage.STATUS -> CollectorStatus(viewModel, openTarget, startTransport, stopTransport)
                CollectorPage.SEATS -> SeatsScreen(viewModel)
                CollectorPage.SEGMENTS -> SegmentsScreen(viewModel, activeOnly = true)
                CollectorPage.ANOMALIES -> SegmentsScreen(viewModel, anomaliesOnly = true)
                CollectorPage.SETTINGS -> CollectorSettings(
                    viewModel,
                    openAccessibility,
                    openTarget,
                    switchRole,
                )
            }
        }
    }
}

@Composable
private fun RobotDashboard(
    viewModel: MainViewModel,
    openTarget: () -> Unit,
    startTransport: () -> Unit,
    stopTransport: () -> Unit,
) {
    val shifts by viewModel.shifts.collectAsState()
    val bindings by viewModel.bindings.collectAsState()
    val segments by viewModel.segments.collectAsState()
    val transport by viewModel.transportState.collectAsState()
    val detail by viewModel.transportDetail.collectAsState()
    val automation by viewModel.automationStatus.collectAsState()
    val lastReply by viewModel.lastReply.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusCard(
                "机器人运行状态",
                "WSS: $transport\n$detail\n微信: $automation",
                listOf(
                    "启动连接" to startTransport,
                    "停止连接" to stopTransport,
                    "打开微信/模拟群" to openTarget,
                ),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("开放班次", shifts.count { it.state == "open" }.toString(), Modifier.weight(1f))
                MetricCard("待审绑定", bindings.count { it.state == "pending" }.toString(), Modifier.weight(1f))
                MetricCard("麦时段", segments.size.toString(), Modifier.weight(1f))
            }
        }
        if (lastReply.isNotBlank()) item { StatusCard("最近机器人回复", lastReply) }
        item {
            Text(
                "正式微信自动回复待校准；模拟应用可完整验证白名单指令。未知消息不会回复。",
                color = Color(0xFFB54708),
            )
        }
    }
}

@Composable
private fun CollectorStatus(
    viewModel: MainViewModel,
    openTarget: () -> Unit,
    startTransport: () -> Unit,
    stopTransport: () -> Unit,
) {
    val transport by viewModel.transportState.collectAsState()
    val detail by viewModel.transportDetail.collectAsState()
    val automation by viewModel.automationStatus.collectAsState()
    val fingerprint by viewModel.observedFingerprint.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusCard(
                "采集端状态",
                "WSS: $transport\n$detail\n映客: $automation",
                listOf(
                    "启动连接" to startTransport,
                    "停止连接" to stopTransport,
                    "打开映客/模拟房" to openTarget,
                ),
            )
        }
        if (transport == "fingerprint_pending") {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("首次证书确认", fontWeight = FontWeight.Bold)
                        Text(fingerprint)
                        Text("请与机器人端设置页显示的 SHA-256 指纹逐字核对。")
                        Button(onClick = viewModel::confirmFingerprint) { Text("指纹一致，固定并重连") }
                    }
                }
            }
        }
        item {
            Text(
                "映客 9.8.60 真实麦位识别待真机校准；模拟应用中已启用。页面不可读时不会估算麦时。",
                color = Color(0xFFB54708),
            )
        }
    }
}

@Composable
private fun ShiftsScreen(viewModel: MainViewModel) {
    val shifts by viewModel.shifts.collectAsState()
    val queue by viewModel.queue.collectAsState()
    var sender by remember { mutableStateOf("") }
    var command by remember {
        mutableStateOf("开排 ${LocalDate.now()} 16:00-17:00 8 截止16:10")
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("核心指令测试", fontWeight = FontWeight.Bold)
                    OutlinedTextField(sender, { sender = it }, label = { Text("发送者微信昵称") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(command, { command = it }, label = { Text("指令") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = { viewModel.simulateCommand(sender, command) },
                        enabled = sender.isNotBlank() && command.isNotBlank(),
                    ) { Text("按群消息规则执行") }
                }
            }
        }
        items(shifts, key = ShiftEntity::id) { shift ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.selectShift(shift.id) },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(shift.label, fontWeight = FontWeight.Bold)
                    Text("状态 ${shift.state} · 普通麦位 ${shift.capacity} · 截止 ${Instant.ofEpochMilli(shift.cutoffAtEpochMs)}")
                }
            }
        }
        if (queue.isNotEmpty()) {
            item { Text("已选班次麦序", fontWeight = FontWeight.Bold) }
            items(queue, key = QueueEntryEntity::id) { row ->
                Text("${row.position}. @${row.wechatName} ${if (row.role == "host") "主持" else "补"} · ${row.state}")
            }
        }
    }
}

@Composable
private fun BindingsScreen(viewModel: MainViewModel) {
    val bindings by viewModel.bindings.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("首次绑定必须由管理员确认；确认后会回填未匹配麦时。", color = Color(0xFF667085)) }
        items(bindings, key = MemberBindingEntity::id) { row ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("@${row.wechatName} → ${row.ingkeeName}", fontWeight = FontWeight.Bold)
                    Text("状态：${row.state}")
                    if (row.state == "pending") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.approveBinding(row.id) }) { Text("确认绑定") }
                            OutlinedButton(onClick = { viewModel.rejectBinding(row.id) }) { Text("拒绝") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentsScreen(
    viewModel: MainViewModel,
    activeOnly: Boolean = false,
    anomaliesOnly: Boolean = false,
    allowCorrection: Boolean = false,
) {
    val segments by viewModel.segments.collectAsState()
    var correcting by remember { mutableStateOf<MicSegmentEntity?>(null) }
    correcting?.let { target ->
        var seconds by remember(target.id) { mutableStateOf(target.durationSeconds.toString()) }
        var reason by remember(target.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { correcting = null },
            title = { Text("手工修正麦时") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${target.wechatName ?: target.ingkeeName} · 原记录 ${target.durationSeconds} 秒")
                    OutlinedTextField(
                        seconds,
                        { seconds = it.filter(Char::isDigit) },
                        label = { Text("修正后时长（秒）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        reason,
                        { reason = it },
                        label = { Text("修正原因") },
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.correctSegment(target.id, seconds.toLongOrNull() ?: 0L, reason)
                        correcting = null
                    },
                    enabled = seconds.isNotBlank() && reason.trim().length >= 2,
                ) { Text("保存修正") }
            },
            dismissButton = {
                TextButton(onClick = { correcting = null }) { Text("取消") }
            },
        )
    }
    val rows = segments.filter {
        (!activeOnly || it.state == "active") &&
            (!anomaliesOnly || it.state in setOf("uncertain", "corrected") || it.bindingId == null)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (rows.isEmpty()) item { Text("暂无记录", color = Color(0xFF667085)) }
        items(rows, key = MicSegmentEntity::id) { row ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(row.wechatName ?: "未绑定：${row.ingkeeName}", fontWeight = FontWeight.Bold)
                    Text("${if (row.role == "host") "主持" else "普通"} · ${row.durationSeconds} 秒 · ${row.state}")
                    Text("开始 ${Instant.ofEpochMilli(row.startedAtEpochMs)}")
                    row.correctionReason?.let { Text(it, color = Color(0xFFB54708)) }
                    if (allowCorrection) {
                        TextButton(onClick = { correcting = row }) { Text("手工修正") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeatsScreen(viewModel: MainViewModel) {
    val seats by viewModel.visibleSeats.collectAsState()
    val page by viewModel.pageType.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("页面：$page · 可见麦位 ${seats.size}", fontWeight = FontWeight.Bold) }
        items(seats) { Text("正在计时：$it") }
        if (seats.isEmpty()) item { Text("未识别到可验证麦位。", color = Color(0xFF667085)) }
    }
}

@Composable
private fun ReportsScreen(viewModel: MainViewModel) {
    var type by remember { mutableStateOf("day") }
    var key by remember { mutableStateOf(LocalDate.now().toString()) }
    var pendingType by remember { mutableStateOf("day") }
    var pendingKey by remember { mutableStateOf(key) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ),
    ) { uri -> uri?.let { viewModel.exportXlsx(it, pendingType, pendingKey) } }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Excel 包含麦时明细、日汇总、月汇总、异常记录四张表。", color = Color(0xFF667085))
        }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("day" to "日", "week" to "周", "month" to "月").forEach { (value, label) ->
                    if (type == value) Button(onClick = {}) { Text(label) }
                    else OutlinedButton(onClick = {
                        type = value
                        key = if (value == "month") YearMonth.now().toString() else LocalDate.now().toString()
                    }) { Text(label) }
                }
            }
        }
        item {
            OutlinedTextField(
                key,
                { key = it },
                label = { Text(if (type == "month") "YYYY-MM" else "YYYY-MM-DD") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(onClick = {
                pendingType = type
                pendingKey = key
                exporter.launch("麦时统计-$type-$key.xlsx")
            }) { Text("生成并保存 Excel") }
        }
    }
}

@Composable
private fun RobotSettings(
    viewModel: MainViewModel,
    openAccessibility: () -> Unit,
    openTarget: () -> Unit,
    switchRole: () -> Unit,
) {
    val config by viewModel.config.collectAsState()
    val admins by viewModel.admins.collectAsState()
    var group by remember(config.groupTitle) { mutableStateOf(config.groupTitle) }
    var owner by remember(config.ownerWechatName) { mutableStateOf(config.ownerWechatName) }
    var newAdmin by remember { mutableStateOf("") }
    var cloudUrl by remember(config.cloudBaseUrl) { mutableStateOf(config.cloudBaseUrl) }
    var roomId by remember(config.cloudRoomId) { mutableStateOf(config.cloudRoomId) }
    var enrollmentToken by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("微信厅控端") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("微信自动回复待校准", color = Color(0xFFB54708), fontWeight = FontWeight.Bold)
            Text("正式微信不会自动发送；模拟群完整启用。")
        }
        item { OutlinedTextField(group, { group = it }, label = { Text("指定微信群名称") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(owner, { owner = it }, label = { Text("主人微信昵称") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(onClick = { viewModel.saveRobotConfig(group, owner, config.serverPort) }) {
                Text("保存机器人配置")
            }
        }
        item { Text("私有云设备", fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(cloudUrl, { cloudUrl = it }, label = { Text("中台 HTTPS 地址") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(roomId, { roomId = it }, label = { Text("厅房 ID") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(deviceName, { deviceName = it }, label = { Text("设备名称") }, modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedTextField(
                enrollmentToken,
                { enrollmentToken = it },
                label = { Text("一次性管理员注册令牌") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    viewModel.enrollCloud(cloudUrl, roomId, enrollmentToken, deviceName)
                    enrollmentToken = ""
                }) { Text("注册厅控设备") }
                OutlinedButton(onClick = viewModel::disconnectCloud) { Text("解除注册") }
            }
        }
        item {
            Text(
                if (config.cloudDeviceId.isBlank()) "云端设备尚未注册"
                else "设备 ID：${config.cloudDeviceId}\n最近同步：${formatTime(config.cloudLastSyncAtEpochMs)}",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = openAccessibility) { Text("无障碍设置") }
                OutlinedButton(onClick = openTarget) { Text("打开微信/模拟群") }
            }
        }
        item { Text("管理员白名单", fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newAdmin, { newAdmin = it }, label = { Text("微信昵称") }, modifier = Modifier.weight(1f))
                Button(onClick = {
                    viewModel.addAdmin(newAdmin)
                    newAdmin = ""
                }) { Text("添加") }
            }
        }
        items(admins) { admin ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("@${admin.wechatName}")
                TextButton(onClick = { viewModel.removeAdmin(admin.id) }) { Text("移除") }
            }
        }
        item { OutlinedButton(onClick = switchRole) { Text("切换为映客采集端") } }
    }
}

@Composable
private fun CollectorSettings(
    viewModel: MainViewModel,
    openAccessibility: () -> Unit,
    openTarget: () -> Unit,
    switchRole: () -> Unit,
) {
    val config by viewModel.config.collectAsState()
    var cloudUrl by remember(config.cloudBaseUrl) { mutableStateOf(config.cloudBaseUrl) }
    var roomId by remember(config.cloudRoomId) { mutableStateOf(config.cloudRoomId) }
    var enrollmentToken by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("映客麦位采集端") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("映客麦位识别待校准", color = Color(0xFFB54708), fontWeight = FontWeight.Bold)
            Text("正式映客不会开始计时；模拟语音房完整启用。")
        }
        item { OutlinedTextField(cloudUrl, { cloudUrl = it }, label = { Text("中台 HTTPS 地址") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(roomId, { roomId = it }, label = { Text("厅房 ID") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(deviceName, { deviceName = it }, label = { Text("设备名称") }, modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedTextField(
                enrollmentToken,
                { enrollmentToken = it },
                label = { Text("一次性管理员注册令牌") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    viewModel.enrollCloud(cloudUrl, roomId, enrollmentToken, deviceName)
                    enrollmentToken = ""
                }) { Text("注册厅控设备") }
                OutlinedButton(onClick = viewModel::disconnectCloud) { Text("解除注册") }
            }
        }
        item {
            Text(
                if (config.cloudDeviceId.isBlank()) "云端设备尚未注册"
                else "设备 ID：${config.cloudDeviceId}\n最近同步：${formatTime(config.cloudLastSyncAtEpochMs)}",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = openAccessibility) { Text("无障碍设置") }
                OutlinedButton(onClick = openTarget) { Text("打开映客/模拟房") }
            }
        }
        item { OutlinedButton(onClick = switchRole) { Text("切换为微信机器人端") } }
    }
}

@Composable
private fun StatusCard(
    title: String,
    content: String,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(content)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions.forEach { (label, action) ->
                    OutlinedButton(onClick = action) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(title, color = Color(0xFF667085))
        }
    }
}

private fun formatTime(epochMs: Long?): String =
    epochMs?.let {
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(it))
    } ?: "未同步"
