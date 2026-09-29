package com.sihoo.aemx.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.os.Build
import java.util.ArrayDeque

/**
 * 실패 시점의 화면 요약. 재분석을 요청할 때 LLM 에게 함께 넘긴다.
 *
 * 접근성 노드를 그대로 넘기지 않는 이유는 이것이 프로세스 경계를 넘어가고, 노드는
 * 화면이 바뀌는 순간 무효가 되기 때문이다.
 */
data class ScreenDescription(
    val packageName: String? = null,
    val texts: List<String> = emptyList(),
    val clickableLabels: List<String> = emptyList(),
)

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

    /**
     * 실패한 시점의 화면을 요약한다.
     *
     * LLM 에게 "지금 화면에 무엇이 보이고 무엇을 누를 수 있는지" 알려 주기 위한 것이다.
     * 이 정보가 빈약하면 재분석이 근거 없이 아무거나 고르게 되므로, 보이는 글자와
     * 누를 수 있는 것을 나눠서 모은다.
     *
     * 노드 수에 상한을 두는 이유는 [find] 와 같다 — 화면에 따라 트리가 수천 개까지
     * 커질 수 있고, 여기는 실패 직후에 도는 경로라 오래 붙잡고 있으면 안 된다.
     */
    fun describeScreen(root: AccessibilityNodeInfo?): ScreenDescription {
        if (root == null) return ScreenDescription()

        val texts = LinkedHashSet<String>()
        val clickables = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0

        while (queue.isNotEmpty() && texts.size < MAX_LABELS) {
            if (++visited > 2_000) break
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val label = listOfNotNull(
                    node.text?.toString(),
                    node.contentDescription?.toString(),
                ).firstOrNull { it.isNotBlank() }?.trim()

                // 화면 전체를 설명하는 긴 문자열(창 root 등)은 목록을 오염시키므로 버린다.
                if (label != null && label.length <= MAX_LABEL_LENGTH) {
                    texts += label
                    if (node.isEnabled && clickableAncestor(node) != null) clickables += label
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }

        return ScreenDescription(
            packageName = root.packageName?.toString(),
            texts = texts.toList(),
            clickableLabels = clickables.toList(),
        )
    }

    private fun normalize(value: String): String = value
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("\\s+"), "")

    private const val MAX_LABELS = 40
    private const val MAX_LABEL_LENGTH = 60

    private val NAVIGATION_LABELS = setOf(
        "홈", "Shorts", "구독", "보관함", "내 페이지", "검색", "만들기", "알림", "더보기",
    )
}
