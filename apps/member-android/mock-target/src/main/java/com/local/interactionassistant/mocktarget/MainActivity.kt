package com.local.interactionassistant.mocktarget

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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

@Composable
private fun MockTarget() {
    var page by remember { mutableStateOf("live") }
    var query by remember { mutableStateOf("") }
    val sent = remember { mutableStateListOf<String>() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("模拟映客 9.8.60", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { page = "live" }) { Text("直播间") }
            OutlinedButton(onClick = { page = "chat" }) { Text("消息") }
            OutlinedButton(onClick = { page = "search" }) { Text("搜索") }
        }
        when (page) {
            "live" -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("模拟映客直播间")
                    Text("在线观众与当前可见互动")
                    Column {
                        Text("测试用户", modifier = Modifier.testTag("candidate_name"))
                        Text("映客号: demo-1001", modifier = Modifier.testTag("candidate_id"))
                        Text("评论说：今天直播很有意思")
                    }
                    Column {
                        Text("熟悉观众")
                        Text("映客号: demo-2002")
                        Text("欢迎回来，再次进入直播间")
                    }
                    Column {
                        Text("关注用户")
                        Text("映客号: demo-3003")
                        Text("关注了主播")
                    }
                    Column {
                        Text("礼物用户")
                        Text("映客号: demo-4004")
                        Text("送出礼物：小星星")
                    }
                    Button(onClick = { page = "chat" }) { Text("发消息") }
                }
            }
            "search" -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("搜索用户")
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("输入用户 ID") },
                        modifier = Modifier.fillMaxWidth().testTag("search_input"),
                    )
                    Button(
                        onClick = { page = "search_results" },
                        modifier = Modifier.testTag("search_button"),
                    ) {
                        Text("搜索")
                    }
                }
            }
            "search_results" -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("搜索结果")
                    if (query.equals("demo-1001", ignoreCase = true)) {
                        Text("测试用户")
                        Text("映客号: demo-1001")
                        Button(
                            onClick = { page = "chat" },
                            modifier = Modifier.testTag("open_chat"),
                        ) {
                            Text("打开聊天")
                        }
                    } else {
                        Text("未找到用户")
                    }
                }
            }
            else -> ChatPage(sent)
        }
    }
}

@Composable
private fun ChatPage(sent: MutableList<String>) {
    var text by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("测试用户", modifier = Modifier.testTag("recipient_name"))
        Text("映客号: demo-1001")
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sent) { Text(it, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("输入消息") },
            modifier = Modifier.fillMaxWidth().testTag("message_editor"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {}) { Text("图片") }
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        sent += text
                        text = ""
                    }
                },
                modifier = Modifier.testTag("send_button"),
            ) { Text("发送") }
        }
    }
}
