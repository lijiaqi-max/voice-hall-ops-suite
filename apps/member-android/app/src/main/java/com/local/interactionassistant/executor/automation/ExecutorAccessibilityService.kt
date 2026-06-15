package com.local.interactionassistant.executor.automation

import com.local.interactionassistant.executor.R
import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.local.interactionassistant.executor.ExecutorApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExecutorAccessibilityService : AccessibilityService() {
    private var scope: CoroutineScope? = null
    private var targetWindowJob: Job? = null
    private var overlayCountdownJob: Job? = null
    private var overlay: View? = null
    private var overlayTaskId: String? = null
    private val adapter: TargetAdapter = Ingkee9860Adapter()
    private val repository get() = ExecutorApp.instance.repository

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutomationCoordinator.setConnected(true)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { serviceScope ->
            serviceScope.launch {
                for (command in AutomationCoordinator.scans) {
                    runCatching { adapter.scanVisible(this@ExecutorAccessibilityService) }
                        .fold(
                            onSuccess = {
                                AutomationCoordinator.updateLatestScan(it)
                                command.result.complete(it)
                            },
                            onFailure = {
                                command.result.complete(
                                    ScanResult(emptyList(), it.message ?: "页面扫描失败"),
                                )
                            },
                        )
                }
            }
            serviceScope.launch {
                for (command in AutomationCoordinator.navigations) {
                    runCatching {
                        adapter.navigateToRecipient(
                            this@ExecutorAccessibilityService,
                            command.externalUserId,
                        )
                    }.fold(
                        onSuccess = { result ->
                            AutomationCoordinator.updateAdapterStatus(result.reason)
                            AutomationCoordinator.updatePageType(result.pageType)
                            AutomationCoordinator.updateFailureCode(
                                if (result.ready) null else "navigation_manual_fallback",
                            )
                            command.result.complete(result)
                        },
                        onFailure = { error ->
                            AutomationCoordinator.updateFailureCode("navigation_failed")
                            command.result.complete(
                                NavigationResult(
                                    ready = false,
                                    reason = error.message ?: "导航失败，请手动打开聊天页",
                                    manualFallbackRequired = true,
                                    pageType = adapter.pageType(this@ExecutorAccessibilityService),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName != Ingkee9860Adapter.OFFICIAL_PACKAGE &&
            packageName != Ingkee9860Adapter.MOCK_PACKAGE
        ) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        targetWindowJob?.cancel()
        targetWindowJob = scope?.launch {
            delay(350)
            val match = adapter.inspect(this@ExecutorAccessibilityService)
            AutomationCoordinator.updateAdapterStatus(match.reason)
            AutomationCoordinator.updatePageType(adapter.pageType(this@ExecutorAccessibilityService))
            AutomationCoordinator.updateCapabilities(adapter.capabilities(this@ExecutorAccessibilityService))
            runCatching { adapter.scanVisible(this@ExecutorAccessibilityService) }
                .getOrNull()
                ?.let(AutomationCoordinator::updateLatestScan)
            prepareConfirmation()
        }
    }

    private suspend fun prepareConfirmation() {
        if (overlay != null) return
        val payload = repository.executionCandidate() ?: return
        val verification = adapter.verify(this, payload)
        if (!verification.ready) {
            AutomationCoordinator.updateAdapterStatus(verification.reason)
            return
        }
        repository.recordVerified(payload.task.id, verification.summary.orEmpty())
        withContext(Dispatchers.Main.immediate) {
            showConfirmationOverlay(payload, verification.summary.orEmpty())
        }
    }

    private fun showConfirmationOverlay(payload: ExecutionPayload, summary: String) {
        if (overlay != null) return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 22, 28, 22)
            setBackgroundColor(Color.argb(245, 24, 32, 44))
        }
        container.addView(TextView(this).apply {
            text = "最终发送确认"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        val countdown = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 8, 0, 4)
        }
        container.addView(countdown)
        val imageNames = payload.assets.joinToString("\n") { "• ${it.displayName}" }
            .ifBlank { "不发送图片" }
        val details = TextView(this).apply {
            text = buildString {
                append("收件人：${payload.task.displayName}\n")
                append("用户 ID：${payload.task.externalUserId}\n\n")
                append("最终话术：\n${payload.task.messageText}\n\n")
                append("图片 ${payload.task.imageCount} 张：\n$imageNames")
                if (summary.isNotBlank()) append("\n\n页面核验：\n$summary")
            }
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        container.addView(
            ScrollView(this).apply { addView(details) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 8, 0, 0)
        }
        actions.addView(Button(this).apply {
            text = "取消"
            setOnClickListener {
                hideOverlay()
                scope?.launch { repository.cancelFinalConfirmation(payload.task.id) }
            }
        })
        actions.addView(Button(this).apply {
            text = "确认发送"
            setOnClickListener {
                hideOverlay()
                scope?.launch { executeConfirmed(payload.task.id) }
            }
        })
        container.addView(actions)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.72f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
        }
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(container, params)
        overlay = container
        overlayTaskId = payload.task.id
        overlayCountdownJob = scope?.launch {
            while (true) {
                val expiresAt = payload.task.approvalExpiresAtEpochMs ?: 0L
                val remainingSeconds = ((expiresAt - System.currentTimeMillis() + 999L) / 1_000L)
                    .coerceAtLeast(0L)
                countdown.text = getString(
                    R.string.approval_countdown,
                    remainingSeconds,
                )
                if (remainingSeconds == 0L) {
                    repository.expireFinalConfirmation(payload.task.id)
                    AutomationCoordinator.updateAdapterStatus("批准已过期，任务已停止")
                    hideOverlay()
                    break
                }
                delay(250)
            }
        }
    }

    private suspend fun executeConfirmed(taskId: String) {
        val payload = repository.finalConfirm(taskId).getOrElse {
            AutomationCoordinator.updateAdapterStatus(it.message ?: "第二次确认失败")
            return
        }
        val library = repository.assetLibrary()
        val published = try {
            withContext(Dispatchers.IO) { library.publish(payload.task.id, payload.assets) }
        } catch (error: Throwable) {
            repository.finishExecution(
                payload,
                AutomationResult(false, error.message ?: "图片发布失败"),
            )
            return
        }
        val result = try {
            adapter.execute(this, payload, published)
        } catch (error: Throwable) {
            AutomationResult(false, error.message ?: "执行器内部错误")
        } finally {
            if (payload.settings.clearPublishedImagesAfterSend) {
                withContext(Dispatchers.IO) { library.clearPublished(published) }
            }
        }
        repository.finishExecution(payload, result)
        AutomationCoordinator.updateAdapterStatus(result.reason)
    }

    private fun hideOverlay() {
        overlayCountdownJob?.cancel()
        overlayCountdownJob = null
        val current = overlay ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(current)
        }
        overlay = null
        overlayTaskId = null
    }

    override fun onInterrupt() {
        hideOverlay()
    }

    override fun onDestroy() {
        AutomationCoordinator.setConnected(false)
        hideOverlay()
        targetWindowJob?.cancel()
        scope?.cancel()
        scope = null
        super.onDestroy()
    }
}
