package com.local.interactionassistant.executor.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

internal fun AccessibilityNodeInfo.walk(): Sequence<AccessibilityNodeInfo> = sequence {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(this@walk)
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        yield(node)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let(queue::addLast)
        }
    }
}

internal fun AccessibilityNodeInfo.semanticText(): String =
    listOfNotNull(text?.toString(), contentDescription?.toString())
        .joinToString(" ")
        .trim()

internal fun AccessibilityNodeInfo.matchesAny(vararg values: String): Boolean {
    val semantic = semanticText()
    return values.any { semantic.equals(it, ignoreCase = true) }
}

internal fun AccessibilityNodeInfo.findClickableByText(vararg values: String): AccessibilityNodeInfo? =
    walk().firstOrNull { node ->
        node.isVisibleToUser && node.matchesAny(*values) && (node.isClickable || node.parent?.isClickable == true)
    }?.let { if (it.isClickable) it else it.parent }

internal fun AccessibilityNodeInfo.findEditable(): AccessibilityNodeInfo? =
    walk().firstOrNull { it.isVisibleToUser && it.isEditable && it.isEnabled }

internal fun AccessibilityNodeInfo.bounds(): Rect = Rect().also(::getBoundsInScreen)

internal fun String.normalizedIdentity(): String =
    trim().replace(Regex("\\s+"), "").lowercase()
