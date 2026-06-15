package com.local.micqueueassistant.automation

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

internal fun AccessibilityNodeInfo.findEditable(): AccessibilityNodeInfo? =
    walk().firstOrNull { it.isVisibleToUser && it.isEditable && it.isEnabled }

internal fun AccessibilityNodeInfo.findClickableByText(vararg values: String): AccessibilityNodeInfo? =
    walk().firstOrNull { node ->
        val text = node.semanticText()
        node.isVisibleToUser &&
            values.any { text.equals(it, ignoreCase = true) } &&
            (node.isClickable || node.parent?.isClickable == true)
    }?.let { if (it.isClickable) it else it.parent }

internal fun AccessibilityNodeInfo.bounds(): Rect = Rect().also(::getBoundsInScreen)
