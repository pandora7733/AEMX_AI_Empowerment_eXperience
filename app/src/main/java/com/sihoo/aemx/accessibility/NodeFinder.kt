package com.sihoo.aemx.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.os.Build
import java.util.ArrayDeque

internal object NodeFinder {
    fun find(root: AccessibilityNodeInfo?, target: String, editableOnly: Boolean = false): AccessibilityNodeInfo? {
        if (root == null || target.isBlank()) return null
        val normalizedTarget = normalize(target)
        var match: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0

        while (queue.isNotEmpty()) {
            if (++visited > 2_000) return null
            val node = queue.removeFirst()
            if (node.isVisibleToUser && node.isEnabled && (!editableOnly || node.isEditable || node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
                }
            )) {
                val candidates = listOfNotNull(
                    node.text?.toString(),
                    node.contentDescription?.toString(),
                    if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null,
                    node.viewIdResourceName,
                ).map(::normalize).filter(String::isNotEmpty)
                if (candidates.any { it == normalizedTarget }) {
                    if (match != null) return null // Ambiguous: require a resource ID.
                    match = node
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        // Only exact labels/IDs are actionable. Substrings can select an unrelated control.
        return match
    }

    fun clickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isEnabled && current.isVisibleToUser &&
                (current.isClickable || current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
                return current
            }
            current = current.parent
        }
        return null
    }

    /** Best-effort YouTube result selection. Real-device resource IDs must be revalidated after app updates. */
    fun findFirstPlayable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        var firstLabelCandidate: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            if (++visited > 2_000) return null
            val node = queue.removeFirst()
            val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .joinToString(" ").trim()
            val id = node.viewIdResourceName.orEmpty()
            val excluded = NAVIGATION_LABELS.any { normalize(label) == normalize(it) }
            val hasVideoId = id.contains("video", true) || id.contains("thumbnail", true)
            if (node.isVisibleToUser && node.isEnabled && !excluded && clickableAncestor(node) != null) {
                if (hasVideoId) return node
                // The window root often has a long aggregate description; never treat it as a result.
                if (node !== root && label.length >= 4 && firstLabelCandidate == null) {
                    firstLabelCandidate = node
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return firstLabelCandidate
    }

    private fun normalize(value: String): String = value
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("\\s+"), "")

    private val NAVIGATION_LABELS = setOf(
        "홈", "Shorts", "구독", "보관함", "내 페이지", "검색", "만들기", "알림", "더보기",
    )
}
