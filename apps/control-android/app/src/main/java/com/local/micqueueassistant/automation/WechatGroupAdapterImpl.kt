package com.local.micqueueassistant.automation

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.local.micqueueassistant.domain.IncomingGroupMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class WechatGroupAdapterImpl : WechatGroupAdapter {
    override fun inspect(service: AccessibilityService): AdapterStatus {
        val root = service.rootInActiveWindow
            ?: return AdapterStatus(false, null, null, "unreadable", false, "当前窗口不可读取")
        val packageName = root.packageName?.toString()
        if (packageName !in setOf(OFFICIAL_PACKAGE, MOCK_PACKAGE)) {
            return AdapterStatus(false, packageName, null, "unsupported", false, "当前不是微信或模拟应用")
        }
        val texts = root.walk().map { it.semanticText() }.filter(String::isNotBlank).toList()
        val page = if (
            root.findEditable() != null &&
            texts.any { it == "发送" || it == "模拟微信群" || it.contains("群消息") }
        ) {
            "group_chat"
        } else {
            "unknown"
        }
        val mock = packageName == MOCK_PACKAGE
        return AdapterStatus(
            supported = page == "group_chat",
            packageName = packageName,
            versionName = if (mock) MOCK_VERSION else null,
            pageType = page,
            calibrated = mock,
            reason = when {
                page != "group_chat" -> "未知微信页面，已停止"
                mock -> "模拟微信群已校准"
                else -> "微信自动回复待校准"
            },
        )
    }

    override suspend fun captureIncomingMessages(
        service: AccessibilityService,
    ): List<IncomingGroupMessage> {
        val status = inspect(service)
        if (!status.supported) return emptyList()
        val root = service.rootInActiveWindow ?: return emptyList()
        val allTexts = root.walk().map { it.semanticText() }.filter(String::isNotBlank).toList()
        val groupTitle = allTexts.firstOrNull { it.startsWith("群名称:") }
            ?.substringAfter(':')?.trim()
            ?: allTexts.firstOrNull { it.contains("群聊") || it.contains("排麦") }
            ?: "模拟排麦群"
        val rows = mutableListOf<IncomingGroupMessage>()
        root.walk().forEach { node ->
            val text = node.semanticText()
            val match = MOCK_MESSAGE_PATTERN.matchEntire(text) ?: return@forEach
            val sender = match.groupValues[1].trim()
            val content = match.groupValues[2].trim()
            val observed = match.groupValues[3].toLongOrNull()
                ?: stableObservedTime(sender, content)
            if (sender != BOT_NAME && content.isNotBlank()) {
                rows += IncomingGroupMessage(groupTitle, sender, content, observed)
            }
        }
        if (rows.isNotEmpty()) return rows.distinctBy {
            "${it.senderWechatName}|${it.text}|${it.observedAtEpochMs}"
        }

        if (status.packageName == OFFICIAL_PACKAGE) return emptyList()
        val ocr = OcrFallback.recognize(service)
        return ocr.mapNotNull { line ->
            val match = MOCK_MESSAGE_PATTERN.matchEntire(line.text) ?: return@mapNotNull null
            IncomingGroupMessage(
                groupTitle = groupTitle,
                senderWechatName = match.groupValues[1].trim(),
                text = match.groupValues[2].trim(),
                observedAtEpochMs = match.groupValues[3].toLongOrNull()
                    ?: stableObservedTime(match.groupValues[1], match.groupValues[2]),
            )
        }
    }

    override suspend fun sendReply(
        service: AccessibilityService,
        message: String,
    ): Result<Unit> = runCatching {
        val status = inspect(service)
        check(status.supported) { status.reason }
        check(status.calibrated) { "微信自动回复待校准，正式微信不执行发送" }
        val root = service.rootInActiveWindow ?: error("群聊窗口不可读取")
        val editable = root.findEditable() ?: error("未找到群聊输入框")
        val textSet = withContext(Dispatchers.Main.immediate) {
            editable.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        message,
                    )
                },
            )
        }
        check(textSet) { "无法写入群回复" }
        val send = service.rootInActiveWindow?.findClickableByText("发送", "Send")
            ?: error("未找到语义明确的发送按钮")
        check(withContext(Dispatchers.Main.immediate) {
            send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }) { "发送按钮点击失败" }
    }

    private fun stableObservedTime(sender: String, content: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$sender|$content".toByteArray())
        val positive = bytes.take(6).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
        return positive
    }

    companion object {
        const val OFFICIAL_PACKAGE = "com.tencent.mm"
        const val MOCK_PACKAGE = "com.local.micqueueassistant.mocktarget"
        const val MOCK_VERSION = "9.8.60"
        const val BOT_NAME = "麦序统计机器人"
        private val MOCK_MESSAGE_PATTERN =
            Regex("""群消息\|发送者:(.+?)\|内容:(.+?)\|时间:(\d+)""")
    }
}
