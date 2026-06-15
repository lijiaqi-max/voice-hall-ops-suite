package com.local.interactionassistant.executor.automation

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.local.interactionassistant.executor.StandalonePolicy
import com.local.interactionassistant.executor.data.InteractionType
import com.local.interactionassistant.executor.data.PublishedAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

class Ingkee9860Adapter : TargetAdapter {
    override val id: String = "ingkee-9.8.60"

    override fun inspect(service: AccessibilityService): AdapterMatch {
        val root = service.rootInActiveWindow
            ?: return AdapterMatch(false, null, null, "当前窗口不可读取")
        val packageName = root.packageName?.toString()
        val versionName = packageName?.let { packageVersion(service, it) }
        if (packageName != OFFICIAL_PACKAGE && packageName != MOCK_PACKAGE) {
            return AdapterMatch(false, packageName, versionName, "当前不是映客或模拟测试应用")
        }
        if (versionName != SUPPORTED_VERSION) {
            return AdapterMatch(
                false,
                packageName,
                versionName,
                "仅支持 $SUPPORTED_VERSION，当前版本为 ${versionName ?: "未知"}",
            )
        }
        val page = pageType(service)
        if (page == "unknown") {
            return AdapterMatch(false, packageName, versionName, "页面特征未知，已停止操作")
        }
        return AdapterMatch(true, packageName, versionName, "包名、版本和页面特征已匹配")
    }

    override fun capabilities(service: AccessibilityService): AdapterCapabilities {
        val packageName = service.rootInActiveWindow?.packageName?.toString()
        return if (packageName == MOCK_PACKAGE) {
            AdapterCapabilities(
                visibleScan = true,
                autoNavigation = true,
                autoNavigationVerified = true,
                imageSend = true,
                reason = "模拟映客已启用互动采集和自动搜索",
            )
        } else {
            AdapterCapabilities(
                visibleScan = true,
                autoNavigation = false,
                autoNavigationVerified = false,
                imageSend = true,
                reason = "映客 9.8.60 真机校准待完成，自动搜索保持关闭",
            )
        }
    }

    override fun getCalibrationStatus(service: AccessibilityService): String =
        if (service.rootInActiveWindow?.packageName?.toString() == MOCK_PACKAGE) {
            "模拟应用已校准"
        } else {
            "映客 9.8.60 真机校准待完成"
        }

    override fun pageType(service: AccessibilityService): String {
        val root = service.rootInActiveWindow ?: return "unreadable"
        val packageName = root.packageName?.toString()
        if (packageName != OFFICIAL_PACKAGE && packageName != MOCK_PACKAGE) return "unsupported"
        if (isKnownChatPage(root)) return "chat"
        val texts = root.walk().map(AccessibilityNodeInfo::semanticText).filter(String::isNotBlank).toList()
        return when {
            texts.any { it.contains("搜索结果") || it.contains("Search results", true) } -> "search_results"
            texts.any { it == "搜索" || it.equals("Search", true) } && root.findEditable() != null -> "search"
            texts.any { marker -> LIVE_ROOM_MARKERS.any { marker.contains(it, ignoreCase = true) } } -> "live_room"
            texts.any { it.contains("个人主页") || it.contains("映客号") } -> "profile"
            texts.any { it.contains("相册") || it.contains("照片") } -> "image_picker"
            else -> "unknown"
        }
    }

    override suspend fun navigateToRecipient(
        service: AccessibilityService,
        externalUserId: String,
    ): NavigationResult {
        val match = inspect(service)
        if (!match.supported) {
            return NavigationResult(false, match.reason, true, pageType(service))
        }
        if (match.packageName != MOCK_PACKAGE) {
            return NavigationResult(
                ready = false,
                reason = "映客 9.8.60 自动搜索尚未真机校准，请手动打开正确聊天页",
                manualFallbackRequired = true,
                pageType = pageType(service),
            )
        }
        val cleanId = externalUserId.trim()
        require(cleanId.length in 3..40) { "用户 ID 无效" }
        var root = service.rootInActiveWindow
            ?: return NavigationResult(false, "当前窗口不可读取", true, "unreadable")
        if (pageType(service) == "chat" &&
            root.walk().any { it.semanticText().contains(cleanId, ignoreCase = true) }
        ) {
            return NavigationResult(true, "已位于目标聊天页", false, "chat")
        }
        if (pageType(service) !in setOf("search", "search_results")) {
            val searchEntry = root.findClickableByText("搜索", "Search")
                ?: return NavigationResult(false, "未找到语义明确的搜索入口", true, pageType(service))
            if (!withContext(Dispatchers.Main.immediate) {
                    searchEntry.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            ) {
                return NavigationResult(false, "搜索入口点击失败", true, pageType(service))
            }
            delay(400)
            root = service.rootInActiveWindow
                ?: return NavigationResult(false, "搜索页不可读取", true, "unreadable")
        }
        val input = root.findEditable()
            ?: return NavigationResult(false, "未找到可验证的搜索输入框", true, pageType(service))
        val textSet = withContext(Dispatchers.Main.immediate) {
            input.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanId)
                },
            )
        }
        if (!textSet) {
            return NavigationResult(false, "无法写入搜索用户 ID", true, pageType(service))
        }
        root.findClickableByText("搜索", "Search")?.let { button ->
            withContext(Dispatchers.Main.immediate) {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        delay(500)
        root = service.rootInActiveWindow
            ?: return NavigationResult(false, "搜索结果页不可读取", true, "unreadable")
        val idNode = root.walk().firstOrNull { it.semanticText().contains(cleanId, ignoreCase = true) }
            ?: return NavigationResult(false, "搜索结果中未找到目标用户 ID", true, pageType(service))
        val card = candidateContainer(idNode, service.resources.displayMetrics.heightPixels)
        val openChat = card.findClickableByText("发消息", "打开聊天", "聊天", "Message")
            ?: root.findClickableByText("发消息", "打开聊天", "Message")
            ?: return NavigationResult(false, "搜索结果缺少语义明确的聊天入口", true, pageType(service))
        if (!withContext(Dispatchers.Main.immediate) {
                openChat.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        ) {
            return NavigationResult(false, "聊天入口点击失败", true, pageType(service))
        }
        delay(500)
        val finalPage = pageType(service)
        return if (finalPage == "chat") {
            NavigationResult(true, "模拟映客已打开聊天页", false, finalPage)
        } else {
            NavigationResult(false, "导航后页面特征不匹配，请手动打开聊天页", true, finalPage)
        }
    }

    override suspend fun captureVisibleInteractions(
        service: AccessibilityService,
    ): InteractionCaptureResult {
        val match = inspect(service)
        if (!match.supported) return InteractionCaptureResult(emptyList(), match.reason)
        if (pageType(service) != "live_room") {
            return InteractionCaptureResult(emptyList(), "仅允许扫描当前可见直播间，当前页面不匹配")
        }
        val root = service.rootInActiveWindow
            ?: return InteractionCaptureResult(emptyList(), "当前窗口不可读取")
        val rows = root.walk().mapNotNull { node ->
            val id = ID_PATTERN.find(node.semanticText())?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null
            val card = candidateContainer(node, service.resources.displayMetrics.heightPixels)
            val texts = card.walk()
                .map(AccessibilityNodeInfo::semanticText)
                .filter(String::isNotBlank)
                .distinct()
                .toList()
            val type = parseIngkeeInteractionType(texts) ?: return@mapNotNull null
            val name = texts.firstOrNull { text ->
                text.length in 1..40 &&
                    ID_PATTERN.find(text) == null &&
                    parseIngkeeInteractionType(listOf(text)) == null &&
                    text !in GENERIC_TEXT
            } ?: return@mapNotNull null
            CapturedInteraction(
                externalUserId = id,
                displayName = name,
                type = type,
                summary = texts.joinToString(" · ").take(160),
            )
        }.distinctBy { "${it.externalUserId}:${it.type}:${it.summary}" }.toList()
        if (rows.isNotEmpty()) {
            return InteractionCaptureResult(rows, "识别到 ${rows.size} 条当前可见互动")
        }
        return captureWithOcr(service)
    }

    override suspend fun scanVisible(service: AccessibilityService): ScanResult {
        val captured = captureVisibleInteractions(service)
        val candidates = captured.interactions
            .groupBy(CapturedInteraction::externalUserId)
            .mapNotNull { (_, events) ->
                val latest = events.firstOrNull() ?: return@mapNotNull null
                AutomationCandidate(
                    externalUserId = latest.externalUserId,
                    displayName = latest.displayName,
                    note = "映客当前可见直播间互动",
                    interactionType = latest.type,
                    interactionSummary = latest.summary,
                )
            }
        return ScanResult(candidates, captured.reason)
    }

    override suspend fun verifyConversation(
        service: AccessibilityService,
        payload: ExecutionPayload,
    ): VerificationResult {
        val match = inspect(service)
        if (!match.supported) return VerificationResult(false, match.reason)
        if (StandalonePolicy.approvalExpired(payload.task.approvalExpiresAtEpochMs, System.currentTimeMillis())) {
            return VerificationResult(false, "批准已过期")
        }
        val root = service.rootInActiveWindow
            ?: return VerificationResult(false, "当前窗口不可读取")
        if (!isKnownChatPage(root)) return VerificationResult(false, "当前页面不是已知聊天页")
        val recipient = findVisibleRecipient(service, payload.task.displayName)
            ?: return VerificationResult(false, "无法确认当前收件人")
        if (recipient.normalizedIdentity() != payload.task.displayName.normalizedIdentity()) {
            return VerificationResult(false, "当前收件人与任务不一致", recipient)
        }
        if (root.findEditable() == null) return VerificationResult(false, "未找到可编辑消息框", recipient)
        if (payload.assets.size != payload.task.imageCount) {
            return VerificationResult(false, "任务图片数量不一致", recipient)
        }
        val summary = "收件人=$recipient；文字=${payload.task.messageText.length} 字；图片=${payload.task.imageCount} 张"
        return VerificationResult(true, "页面已核验，等待第二次人工确认", recipient, summary)
    }

    override suspend fun execute(
        service: AccessibilityService,
        payload: ExecutionPayload,
        publishedAssets: List<PublishedAsset>,
    ): AutomationResult {
        val initial = verifyConversation(service, payload)
        if (!initial.ready) {
            return AutomationResult(false, initial.reason, initial.visibleRecipient, initial.summary)
        }
        if (publishedAssets.size != payload.task.imageCount) {
            return AutomationResult(false, "发布图片数量与任务不一致", initial.visibleRecipient)
        }
        val root = service.rootInActiveWindow
            ?: return AutomationResult(false, "当前窗口不可读取", initial.visibleRecipient)
        val editable = root.findEditable()
            ?: return AutomationResult(false, "未找到可编辑消息框", initial.visibleRecipient)
        val textSet = withContext(Dispatchers.Main.immediate) {
            editable.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        payload.task.messageText,
                    )
                },
            )
        }
        if (!textSet) return AutomationResult(false, "无法写入已批准话术", initial.visibleRecipient)

        var attachedCount = 0
        for (asset in publishedAssets) {
            if (!attachAsset(service, asset.publishedName)) {
                return AutomationResult(
                    false,
                    "无法通过语义节点选择图片 ${asset.publishedName}",
                    initial.visibleRecipient,
                    "已附加 $attachedCount/${publishedAssets.size} 张图片",
                )
            }
            attachedCount += 1
        }
        val delaySeconds = StandalonePolicy.randomDelaySeconds(
            payload.settings.minDelaySeconds,
            payload.settings.maxDelaySeconds,
        )
        if (delaySeconds > 0) delay(delaySeconds * 1_000L)
        if (StandalonePolicy.approvalExpired(payload.task.approvalExpiresAtEpochMs, System.currentTimeMillis())) {
            return AutomationResult(false, "点击发送前批准已过期", initial.visibleRecipient)
        }
        val finalCheck = verifyConversation(service, payload)
        if (!finalCheck.ready) {
            return AutomationResult(false, "发送前复核失败：${finalCheck.reason}", finalCheck.visibleRecipient)
        }
        val finalRoot = service.rootInActiveWindow
            ?: return AutomationResult(false, "点击发送前窗口不可读取", finalCheck.visibleRecipient)
        if (finalRoot.findEditable()?.text?.toString().orEmpty() != payload.task.messageText) {
            return AutomationResult(false, "点击发送前话术内容发生变化", finalCheck.visibleRecipient)
        }
        if (attachedCount != payload.task.imageCount) {
            return AutomationResult(false, "点击发送前图片数量不一致", finalCheck.visibleRecipient)
        }
        val send = finalRoot.findClickableByText("发送", "Send")
            ?: return AutomationResult(false, "未找到语义明确的发送按钮", finalCheck.visibleRecipient)
        val clicked = withContext(Dispatchers.Main.immediate) {
            send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val summary = "收件人=${finalCheck.visibleRecipient}；文字已复核；图片=$attachedCount 张；延迟=${delaySeconds}秒"
        return if (clicked) {
            AutomationResult(true, "已执行一次发送", finalCheck.visibleRecipient, summary)
        } else {
            AutomationResult(false, "发送按钮点击失败", finalCheck.visibleRecipient, summary)
        }
    }

    private suspend fun captureWithOcr(service: AccessibilityService): InteractionCaptureResult {
        val lines = OcrFallback.recognize(service)
        val rows = lines.mapNotNull { line ->
            val id = ID_PATTERN.find(line.text)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val nearby = lines.filter { abs(it.verticalCenterFraction - line.verticalCenterFraction) < 0.12f }
            val type = parseIngkeeInteractionType(nearby.map(OcrLine::text)) ?: return@mapNotNull null
            val name = nearby.map(OcrLine::text).firstOrNull { text ->
                text.length in 1..40 &&
                    ID_PATTERN.find(text) == null &&
                    parseIngkeeInteractionType(listOf(text)) == null &&
                    text !in GENERIC_TEXT
            } ?: return@mapNotNull null
            CapturedInteraction(id, name, type, nearby.joinToString(" · ") { it.text }.take(160))
        }.distinctBy { "${it.externalUserId}:${it.type}:${it.summary}" }
        return if (rows.isEmpty()) {
            InteractionCaptureResult(emptyList(), "节点和 OCR 均未识别到带用户 ID 的可验证互动")
        } else {
            InteractionCaptureResult(rows, "OCR 后备识别到 ${rows.size} 条当前可见互动")
        }
    }

    private fun candidateContainer(node: AccessibilityNodeInfo, screenHeight: Int): AccessibilityNodeInfo {
        var current = node
        repeat(5) {
            val parent = current.parent ?: return current
            val height = parent.bounds().height()
            if (height in 80..(screenHeight * 0.45f).toInt()) current = parent else return current
        }
        return current
    }

    private suspend fun findVisibleRecipient(
        service: AccessibilityService,
        expected: String,
    ): String? {
        val root = service.rootInActiveWindow ?: return null
        val screenHeight = service.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        root.walk().firstOrNull { node ->
            node.isVisibleToUser &&
                node.semanticText().normalizedIdentity() == expected.normalizedIdentity() &&
                node.bounds().centerY() < screenHeight * 0.28f
        }?.semanticText()?.let { return it }
        return OcrFallback.recognize(service)
            .firstOrNull {
                it.verticalCenterFraction < 0.28f &&
                    it.text.normalizedIdentity() == expected.normalizedIdentity()
            }
            ?.text
    }

    private fun isKnownChatPage(root: AccessibilityNodeInfo): Boolean =
        root.findEditable() != null && root.findClickableByText("发送", "Send") != null

    private suspend fun attachAsset(service: AccessibilityService, publishedName: String): Boolean {
        val chatRoot = service.rootInActiveWindow ?: return false
        val attach = chatRoot.findClickableByText("图片", "相册", "照片", "添加图片")
            ?: return false
        if (!withContext(Dispatchers.Main.immediate) {
                attach.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        ) return false
        delay(700)
        val pickerRoot = service.rootInActiveWindow ?: return false
        val stem = publishedName.substringBeforeLast('.')
        val fileNode = pickerRoot.walk().firstOrNull { node ->
            val text = node.semanticText()
            node.isVisibleToUser &&
                (text.equals(publishedName, true) || text.contains(stem, true)) &&
                (node.isClickable || node.parent?.isClickable == true)
        } ?: return false
        val clickable = if (fileNode.isClickable) fileNode else fileNode.parent ?: return false
        if (!withContext(Dispatchers.Main.immediate) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        ) return false
        delay(500)
        service.rootInActiveWindow?.findClickableByText("完成", "确定", "添加")?.let { done ->
            withContext(Dispatchers.Main.immediate) {
                done.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            delay(500)
        }
        return service.rootInActiveWindow?.let(::isKnownChatPage) == true
    }

    private fun packageVersion(service: AccessibilityService, packageName: String): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                service.packageManager.getPackageInfo(packageName, 0).versionName
            }
        }.getOrNull()

    companion object {
        const val OFFICIAL_PACKAGE = "com.meelive.ingkee"
        const val MOCK_PACKAGE = "com.local.interactionassistant.mocktarget"
        const val SUPPORTED_VERSION = "9.8.60"

        private val ID_PATTERN =
            Regex("(?:映客号|用户\\s*ID|ID|账号)\\s*[:：]?\\s*([A-Za-z0-9_-]{3,40})", RegexOption.IGNORE_CASE)
        private val LIVE_ROOM_MARKERS = setOf("直播间", "在线观众", "关注主播", "送出礼物", "模拟映客直播间")
        private val GENERIC_TEXT = setOf(
            "直播间",
            "消息",
            "搜索",
            "关注",
            "发送",
            "图片",
            "发消息",
            "在线观众",
        )
    }
}

internal fun parseIngkeeInteractionType(texts: List<String>): String? {
    val combined = texts.joinToString(" ")
    return when {
        listOf("送出", "礼物", "赠送").any(combined::contains) -> InteractionType.GIFT.value
        listOf("关注了主播", "关注你", "新增关注").any(combined::contains) ->
            InteractionType.FOLLOW.value
        listOf("再次进入", "欢迎回来", "回来了").any(combined::contains) ->
            InteractionType.RETURN_VISIT.value
        listOf("评论", "说：", "说:", "发言").any(combined::contains) ->
            InteractionType.COMMENT.value
        else -> null
    }
}
