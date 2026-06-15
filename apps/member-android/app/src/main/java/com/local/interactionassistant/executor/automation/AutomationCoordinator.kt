package com.local.interactionassistant.executor.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AutomationCoordinator {
    internal data class ScanCommand(
        val result: CompletableDeferred<ScanResult>,
    )

    internal data class NavigationCommand(
        val externalUserId: String,
        val result: CompletableDeferred<NavigationResult>,
    )

    internal val scans = Channel<ScanCommand>(Channel.UNLIMITED)
    internal val navigations = Channel<NavigationCommand>(Channel.UNLIMITED)
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _latestScan = MutableStateFlow<ScanResult?>(null)
    val latestScan = _latestScan.asStateFlow()
    private val _adapterStatus = MutableStateFlow("等待目标 App")
    val adapterStatus = _adapterStatus.asStateFlow()
    private val _pageType = MutableStateFlow("unknown")
    val pageType = _pageType.asStateFlow()
    private val _capabilities = MutableStateFlow(
        AdapterCapabilities(
            visibleScan = true,
            autoNavigation = false,
            autoNavigationVerified = false,
            imageSend = true,
            reason = "等待目标 App",
        ),
    )
    val capabilities = _capabilities.asStateFlow()
    private val _lastFailureCode = MutableStateFlow<String?>(null)
    val lastFailureCode = _lastFailureCode.asStateFlow()

    internal fun setConnected(value: Boolean) {
        _connected.value = value
    }

    internal fun updateLatestScan(result: ScanResult) {
        _adapterStatus.value = result.reason
        if (result.candidates.isNotEmpty()) _latestScan.value = result
    }

    internal fun updateAdapterStatus(value: String) {
        _adapterStatus.value = value
    }

    internal fun updatePageType(value: String) {
        _pageType.value = value
    }

    internal fun updateCapabilities(value: AdapterCapabilities) {
        _capabilities.value = value
    }

    internal fun updateFailureCode(value: String?) {
        _lastFailureCode.value = value
    }

    suspend fun scan(): ScanResult {
        if (!_connected.value) return ScanResult(emptyList(), "无障碍服务未连接")
        val result = CompletableDeferred<ScanResult>()
        scans.send(ScanCommand(result))
        return result.await()
    }

    suspend fun navigate(externalUserId: String): NavigationResult {
        if (!_connected.value) {
            return NavigationResult(
                ready = false,
                reason = "无障碍服务未连接，请手动打开聊天页",
                manualFallbackRequired = true,
                pageType = _pageType.value,
            )
        }
        val result = CompletableDeferred<NavigationResult>()
        navigations.send(NavigationCommand(externalUserId, result))
        return result.await()
    }
}
