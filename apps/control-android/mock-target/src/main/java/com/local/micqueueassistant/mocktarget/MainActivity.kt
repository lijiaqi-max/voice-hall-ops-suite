package com.local.micqueueassistant.mocktarget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MockTarget()
                }
            }
        }
    }
}

private data class GroupMessage(
    val sender: String,
    val text: String,
    val timestamp: Long,
)

@Composable
private fun MockTarget() {
    var page by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = page) {
            Tab(page == 0, { page = 0 }, text = { Text("模拟微信群") })
            Tab(page == 1, { page = 1 }, text = { Text("模拟映客语音房") })
        }
        if (page == 0) {
            WechatMock()
        } else {
            IngkeeMock()
        }
    }
}

@Composable
private fun WechatMock() {
    val messages = remember {
        mutableStateListOf(
            GroupMessage("琳惠", "帮助", 1L),
        )
    }
    var sender by remember { mutableStateOf("琳惠") }
    var incoming by remember { mutableStateOf("补") }
    var reply by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "群名称:模拟排麦群",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { contentDescription = "群名称:模拟排麦群" },
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages) { message ->
                val semantic = "群消息|发送者:${message.sender}|内容:${message.text}|时间:${message.timestamp}"
                Card(modifier = Modifier.fillMaxWidth().semantics { contentDescription = semantic }) {
                    Column(Modifier.padding(10.dp)) {
                        Text(message.sender)
                        Text(message.text)
                        Text(semantic)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                sender,
                { sender = it },
                label = { Text("发送者") },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                incoming,
                { incoming = it },
                label = { Text("群消息") },
                modifier = Modifier.weight(2f),
            )
        }
        Button(
            onClick = {
                if (sender.isNotBlank() && incoming.isNotBlank()) {
                    messages += GroupMessage(sender.trim(), incoming.trim(), System.currentTimeMillis())
                    incoming = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("加入成员消息")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                reply,
                { reply = it },
                label = { Text("机器人回复") },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    if (reply.isNotBlank()) {
                        messages += GroupMessage("麦序统计机器人", reply.trim(), System.currentTimeMillis())
                        reply = ""
                    }
                },
            ) {
                Text("发送")
            }
        }
    }
}

@Composable
private fun IngkeeMock() {
    val seats = remember {
        mutableStateListOf<String?>().also { list ->
            repeat(8) { list += null }
        }
    }
    var customName by remember { mutableStateOf("夏天") }
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("模拟映客语音房", style = MaterialTheme.typography.titleLarge)
        Text("版本 9.8.60，麦位节点可被测试 APK 读取")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                seats[0] = "夏天"
                seats[1] = "芊芊"
                seats[2] = "念安"
            }) { Text("载入三人") }
            OutlinedButton(onClick = { repeat(seats.size) { seats[it] = null } }) {
                Text("全部下麦")
            }
            OutlinedButton(onClick = {
                val first = seats[0]
                seats[0] = seats[3]
                seats[3] = first
            }) { Text("1/4 换位") }
        }
        OutlinedTextField(
            customName,
            { customName = it },
            label = { Text("自定义昵称") },
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items((1..8).toList()) { seatNumber ->
                val name = seats[seatNumber - 1]
                val semantic = if (name.isNullOrBlank()) {
                    "麦位 $seatNumber 空"
                } else {
                    "麦位 $seatNumber 昵称:$name"
                }
                Card(modifier = Modifier.fillMaxWidth().semantics { contentDescription = semantic }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(semantic)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                seats[seatNumber - 1] = customName.trim().ifBlank { "未命名" }
                            }) { Text("上麦") }
                            OutlinedButton(onClick = { seats[seatNumber - 1] = null }) {
                                Text("下麦")
                            }
                        }
                    }
                }
            }
        }
    }
}
