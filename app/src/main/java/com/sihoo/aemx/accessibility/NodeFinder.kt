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
            val container = clickableAncestor(node)
            if (node.isVisibleToUser && node.isEnabled && !excluded && container != null &&
                !isSponsored(container)
            ) {
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

    /**
     * 검색 결과의 광고 항목인지 본다.
     *
     * 유튜브 검색 결과는 맨 위가 광고인 경우가 많아서, 거르지 않으면 "첫 번째 영상"이
     * 사용자가 찾던 영상이 아니라 광고가 된다(실기기에서 실제로 그렇게 재생됐다).
     *
     * 광고 표시는 항목을 감싸는 컨테이너 안에 "스폰서" 같은 라벨로 들어 있으므로,
     * 누를 수 있는 조상까지 올라간 뒤 그 아래만 훑는다. 전체 목록이 하나의 컨테이너로
     * 잡히는 경우를 대비해 훑는 개수를 제한한다 — 그러면 광고 하나 때문에 결과 전체를
     * 광고로 판정하는 일이 생긴다.
     */
    private fun isSponsored(container: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(container) }
        var visited = 0
        while (queue.isNotEmpty()) {
            if (++visited > AD_SCAN_LIMIT) return false
            val node = queue.removeFirst()
            val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            if (labels.any { label -> AD_MARKERS.any { label.contains(it, ignoreCase = true) } }) {
                return true
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return false
    }

    private fun normalize(value: String): String = value
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("\\s+"), "")

    private const val MAX_LABELS = 40
    private const val MAX_LABEL_LENGTH = 60

    private const val AD_SCAN_LIMIT = 60

    /** 유튜브가 광고 항목에 붙이는 표시. 앱 업데이트로 문구가 바뀌면 여기를 고친다. */
    private val AD_MARKERS = listOf("스폰서", "sponsored", "광고", "프로모션")

    private val NAVIGATION_LABELS = setOf(
        "홈", "Shorts", "구독", "보관함", "내 페이지", "검색", "만들기", "알림", "더보기",
    )
}
