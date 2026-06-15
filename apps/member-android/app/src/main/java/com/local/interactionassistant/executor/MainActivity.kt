package com.local.interactionassistant.executor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.local.interactionassistant.executor.data.CloudConfigEntity
import com.local.interactionassistant.executor.data.CloudTaskEntity
import com.local.interactionassistant.executor.cloud.MemberStats

class MainActivity : ComponentActivity() {
    private val viewModel: MemberCloudViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MemberCloudApp(viewModel)
                }
            }
        }
    }
}

private data class MemberTab(val title: String, val short: String)

private val tabs = listOf(
    MemberTab("今日作业", "今日"),
    MemberTab("任务池", "任务"),
    MemberTab("客户卡片", "客户"),
    MemberTab("交流建议", "建议"),
    MemberTab("完成记录", "记录"),
    MemberTab("个人统计", "统计"),
)

@Composable
private fun MemberCloudApp(viewModel: MemberCloudViewModel) {
    val config by viewModel.config.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val conflictCount by viewModel.conflictCount.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val loggedIn = config?.accessToken?.isNotBlank() == true

    if (!loggedIn) {
        LoginScreen(busy, message, viewModel::login)
        return
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    val selectedTask = tasks.firstOrNull { it.id == selectedTaskId }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF4F6F2))) {
        MemberHeader(config, busy, pendingCount, conflictCount, viewModel::sync, viewModel::logout)
        config?.lastSyncError?.let { MessageBanner("最近同步：$it", true) }
        message?.let {
            MessageBanner(it, it.contains("失败") || it.contains("请先") || it.contains("返回"))
        }
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> TodayPage(tasks, viewModel, onSelect = {
                    selectedTaskId = it.id
                    selectedTab = 3
                })
                1 -> TaskPoolPage(tasks, viewModel, onSelect = {
                    selectedTaskId = it.id
                    selectedTab = 3
                })
                2 -> CustomerPage(tasks, onSelect = {
                    selectedTaskId = it.id
                    selectedTab = 3
                })
                3 -> AdvicePage(selectedTask, viewModel)
                4 -> CompletedPage(tasks)
                5 -> StatsPage(stats, pendingCount, conflictCount, viewModel::uploadLegacy)
            }
        }
        NavigationBar {
            tabs.forEachIndexed { index, tab ->
                NavigationBarItem(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    icon = { Text(tab.short.take(1), fontWeight = FontWeight.Bold) },
                    label = { Text(tab.short) },
                )
            }
        }
    }
}

@Composable
private fun LoginScreen(
    busy: Boolean,
    message: String?,
    login: (String, String, String, String) -> Unit,
) {
    var baseUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF123A2F)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAF6)),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("语音厅成员端", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "领取关系维护作业，人工完成交流并提交结果。成员端不使用无障碍权限。",
                    color = Color(0xFF65736D),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("中台 HTTPS 地址") },
                    placeholder = { Text("https://ops.example.com/api") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("账号") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = otp,
                    onValueChange = { otp = it.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TOTP（账号启用时填写）") },
                    placeholder = { Text("6 位动态码") },
                    singleLine = true,
                )
                message?.let { MessageBanner(it, true) }
                Button(
                    onClick = { login(baseUrl, username, password, otp) },
                    enabled = !busy && baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "登录中…" else "登录并同步")
                }
                Text(
                    "旧版关系助手数据仍保留在本机。登录后可在“个人统计”中预览并上传到迁移暂存区。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF78847E),
                )
            }
        }
    }
}

@Composable
private fun MemberHeader(
    config: CloudConfigEntity?,
    busy: Boolean,
    pendingCount: Int,
    conflictCount: Int,
    sync: () -> Unit,
    logout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF173F33)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("今日关系作业", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                buildString {
                    append("${config?.displayName.orEmpty()} · ${config?.role.orEmpty()}")
                    if (pendingCount > 0) append(" · 待同步 $pendingCount")
                    if (conflictCount > 0) append(" · 冲突 $conflictCount")
                },
                color = Color(0xFFB9D4C9),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = sync, enabled = !busy) { Text("同步", color = Color.White) }
        TextButton(onClick = logout, enabled = !busy) { Text("退出", color = Color.White) }
    }
}

@Composable
private fun TodayPage(
    tasks: List<CloudTaskEntity>,
    viewModel: MemberCloudViewModel,
    onSelect: (CloudTaskEntity) -> Unit,
) {
    val active = tasks.filter { it.state in setOf("assigned", "claimed", "in_progress", "rejected") }
    TaskList(
        title = "今日待办",
        subtitle = "一次只处理一个客户，交流由你本人完成。",
        tasks = active,
        empty = "今天没有待处理作业",
        action = { task ->
            when (task.state) {
                "assigned" -> "开始领取" to { viewModel.claim(task.id) }
                "claimed", "rejected" -> "开始作业" to { viewModel.start(task.id) }
                else -> "填写结果" to { onSelect(task) }
            }
        },
        onSelect = onSelect,
    )
}

@Composable
private fun TaskPoolPage(
    tasks: List<CloudTaskEntity>,
    viewModel: MemberCloudViewModel,
    onSelect: (CloudTaskEntity) -> Unit,
) {
    TaskList(
        title = "公开任务池",
        subtitle = "同一客户同时只会有一条有效作业。",
        tasks = tasks.filter { it.state == "published" },
        empty = "任务池暂时为空",
        action = { task -> "领取" to { viewModel.claim(task.id) } },
        onSelect = onSelect,
    )
}

@Composable
private fun TaskList(
    title: String,
    subtitle: String,
    tasks: List<CloudTaskEntity>,
    empty: String,
    action: (CloudTaskEntity) -> Pair<String, () -> Unit>,
    onSelect: (CloudTaskEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF6B7872))
            Spacer(Modifier.height(4.dp))
        }
        if (tasks.isEmpty()) {
            item { EmptyCard(empty) }
        }
        items(tasks, key = { it.id }) { task ->
            val taskAction = action(task)
            TaskCard(task, onClick = { onSelect(task) }) {
                Button(onClick = taskAction.second) { Text(taskAction.first) }
            }
        }
    }
}

@Composable
private fun CustomerPage(tasks: List<CloudTaskEntity>, onSelect: (CloudTaskEntity) -> Unit) {
    val latestByCustomer = tasks.groupBy { it.customerId }.values.mapNotNull {
        it.maxByOrNull(CloudTaskEntity::updatedAtEpochMs)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("客户卡片", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("只展示与你当前作业有关的关系信息。", color = Color(0xFF6B7872))
        }
        items(latestByCustomer, key = { it.customerId }) { task ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onSelect(task) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.customerName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        StatusPill(task.valueLevel)
                    }
                    Text("最近作业：${task.title}")
                    Text("状态：${task.state}", color = Color(0xFF6B7872))
                    Text("精确流水由管理端按角色控制，价值等级不代表真实财富能力。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF7E8984))
                }
            }
        }
    }
}

@Composable
private fun AdvicePage(task: CloudTaskEntity?, viewModel: MemberCloudViewModel) {
    val adviceByTask by viewModel.advice.collectAsState()
    if (task == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("请先从今日作业或任务池选择客户", color = Color(0xFF78847E))
        }
        return
    }
    var showSubmit by remember(task.id) { mutableStateOf(false) }
    val advice = adviceByTask[task.id]
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(task.customerName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            StatusPill(task.state)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E9))) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.title, fontWeight = FontWeight.Bold)
                    Text(task.brief)
                    Text("建议仅供参考。禁止索礼、消费施压、虚假亲密或频繁催促。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF805F2B))
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("脱敏交流建议", fontWeight = FontWeight.Bold)
                    if (advice == null) {
                        Text("只发送关系阶段、互动时间区间、价值等级、任务目的和语气。")
                        OutlinedButton(onClick = { viewModel.generateAdvice(task.id) }) {
                            Text("生成建议")
                        }
                    } else {
                        Text(advice.advice)
                        Text(
                            if (advice.fallbackUsed) "规则模板兜底" else "AI 建议",
                            color = Color(0xFF6B7872),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (advice.riskTags.isNotEmpty()) {
                            Text(
                                "已拦截风险：${advice.riskTags.joinToString("、")}",
                                color = Color(0xFF9B2C2C),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("必须由成员人工确认后使用。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (task.syncState == "conflict") {
                    OutlinedButton(onClick = { viewModel.discardConflict(task.id) }) {
                        Text("放弃本地冲突并刷新")
                    }
                }
                if (task.state == "claimed" || task.state == "rejected") {
                    Button(onClick = { viewModel.start(task.id) }) { Text("开始人工交流") }
                }
                if (task.state == "in_progress") {
                    Button(onClick = { showSubmit = true }) { Text("填写完成结果") }
                }
            }
        }
    }
    if (showSubmit) {
        SubmitDialog(
            onDismiss = { showSubmit = false },
            onSubmit = { channel, note, nextAt ->
                viewModel.submit(task.id, channel, note, nextAt)
                showSubmit = false
            },
        )
    }
}

@Composable
private fun SubmitDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String, Long?) -> Unit,
) {
    var channel by remember { mutableStateOf("映客私信") }
    var note by remember { mutableStateOf("") }
    var remind by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提交作业结果") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(channel, { channel = it }, label = { Text("互动渠道") })
                OutlinedTextField(
                    note,
                    { note = it },
                    label = { Text("结果与备注") },
                    minLines = 3,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("下次提醒：", modifier = Modifier.weight(1f))
                    TextButton(onClick = { remind = !remind }) {
                        Text(if (remind) "7 天后" else "不设置")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(
                        channel,
                        note,
                        if (remind) System.currentTimeMillis() + 7 * 86_400_000L else null,
                    )
                },
                enabled = note.isNotBlank(),
            ) { Text("提交审核") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CompletedPage(tasks: List<CloudTaskEntity>) {
    val completed = tasks.filter { it.state in setOf("submitted", "approved", "rejected", "cancelled") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("完成记录", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("提交后由管理端审核，驳回项可重新处理。", color = Color(0xFF6B7872))
        }
        if (completed.isEmpty()) item { EmptyCard("还没有完成记录") }
        items(completed, key = { it.id }) { task ->
            TaskCard(task) {
                task.resultNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun StatsPage(
    stats: MemberStats?,
    pendingCount: Int,
    conflictCount: Int,
    uploadLegacy: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("个人统计", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("已领取", (stats?.claimedCount ?: 0).toString(), Modifier.weight(1f))
                StatCard("已提交", (stats?.submittedCount ?: 0).toString(), Modifier.weight(1f))
                StatCard("已通过", (stats?.approvedCount ?: 0).toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("待同步", pendingCount.toString(), Modifier.weight(1f))
                StatCard("冲突", conflictCount.toString(), Modifier.weight(1f))
                StatCard(
                    "回访完成率",
                    "${(stats?.followUpCompletionRateBps ?: 0) / 100}%",
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("旧版数据迁移", fontWeight = FontWeight.Bold)
                    Text(
                        "上传前会读取旧关系、互动和作业数量，保存到管理端迁移暂存区；管理员确认后再合并。",
                        color = Color(0xFF6B7872),
                    )
                    OutlinedButton(onClick = uploadLegacy) { Text("预览并上传旧数据") }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: CloudTaskEntity,
    onClick: (() -> Unit)? = null,
    action: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.customerName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusPill(task.state)
            }
            Text(task.title, fontWeight = FontWeight.SemiBold)
            Text(task.brief, color = Color(0xFF65716C), maxLines = 3)
            if (task.syncState != "synced") {
                Text(
                    when (task.syncState) {
                        "pending" -> "待同步：服务端确认前不会显示为已完成"
                        "conflict" -> "同步冲突：${task.conflictMessage ?: "请刷新后联系管理员"}"
                        else -> task.syncState
                    },
                    color = if (task.syncState == "conflict") Color(0xFF9B2C2C) else Color(0xFF8A641A),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("优先级 ${task.priority}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Text("等级 ${task.valueLevel}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                action()
            }
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Text(
        text,
        modifier = Modifier.background(Color(0xFFE2ECE6), RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
        color = Color(0xFF315C4A),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = Color(0xFF6B7872), style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAF7))) {
        Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF87928D))
        }
    }
}

@Composable
private fun MessageBanner(message: String, error: Boolean) {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().background(
            if (error) Color(0xFFFFE7E4) else Color(0xFFE3F2E8),
        ).padding(11.dp),
        color = if (error) Color(0xFF8A302E) else Color(0xFF2E6944),
        style = MaterialTheme.typography.bodySmall,
    )
}
