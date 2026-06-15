package com.local.micqueueassistant.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AutomationCoordinator {
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _status = MutableStateFlow("等待目标应用")
    val status = _status.asStateFlow()
    private val _pageType = MutableStateFlow("unknown")
    val pageType = _pageType.asStateFlow()
    private val _visibleSeats = MutableStateFlow<List<String>>(emptyList())
    val visibleSeats = _visibleSeats.asStateFlow()
    private val _lastReply = MutableStateFlow("")
    val lastReply = _lastReply.asStateFlow()

    internal fun setConnected(value: Boolean) {
        _connected.value = value
    }

    internal fun updateStatus(value: String, pageType: String) {
        _status.value = value
        _pageType.value = pageType
    }

    internal fun updateSeats(value: List<String>) {
        _visibleSeats.value = value
    }

    internal fun updateLastReply(value: String) {
        _lastReply.value = value
    }
}
