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

    /**
     * 유튜브 검색 결과에서 맨 처음 나오는 진짜 영상을 고른다.
     *
     * 예전에는 트리 전체에서 id 에 video/thumbnail 이 들어간 노드를 찾았는데, 실기기에서
     * 보니 유튜브 검색 결과 항목에는 resource-id 가 아예 없다. 그래서 늘 "라벨이 4자
     * 이상인 첫 노드"로 떨어졌고, 그게 검색창이나 미리보기 플레이어 컨트롤이라 영상이
     * 열리지 않았다.
     *
     * 믿을 만한 단서는 항목의 content-desc 꼬리말이다. 실기기에서 확인한 형태:
     * - 영상      `제목 - 19분 44초 - 채널 채널로 이동 - … - 동영상 재생`
     * - Shorts    `제목, 조회수 2.2억회, 채널, 1년 전 - Shorts 동영상 재생`
     * - 채널 버튼  `채널 채널로 이동`
     *
     * 그래서 결과 목록 안에서 "재생" 꼬리말이 붙은 것만 후보로 본다. Shorts 는 몇 초짜리
     * 클립이라 "영상 틀어줘"가 기대하는 결과가 아니므로 다른 영상이 없을 때만 쓴다.
     *
     * 유튜브 앱이 업데이트돼 문구가 바뀌면 [PLAY_MARKERS] 를 고쳐야 한다.
     */
    fun findFirstPlayable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        // 결과 목록 밖(검색창·재생 중인 미리보기·하단 탭)을 후보에서 아예 빼기 위해
        // 목록 컨테이너부터 찾는다.
        val container = resultList(root) ?: root
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(container) }
        var visited = 0
        var shortsFallback: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            if (++visited > 2_000) break
            val node = queue.removeFirst()
            if (isPlayableItem(node) && !isSponsored(node)) {
                val description = node.contentDescription?.toString().orEmpty()
                if (!SHORTS_MARKERS.any { description.contains(it, ignoreCase = true) }) return node
                if (shortsFallback == null) shortsFallback = node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return shortsFallback
    }

    /** 검색 결과가 담기는 목록. 못 찾으면 호출한 쪽이 화면 전체를 쓰게 null 을 준다. */
    private fun resultList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty()) {
            if (++visited > 2_000) return null
            val node = queue.removeFirst()
            if (node.viewIdResourceName?.endsWith(RESULT_LIST_ID_SUFFIX) == true) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return null
    }

    private fun isPlayableItem(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        if (!node.isClickable && node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return false
        val description = node.contentDescription?.toString() ?: return false
        return PLAY_MARKERS.any { description.contains(it, ignoreCase = true) }
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

    /** 검색 결과 목록의 resource-id. 유튜브는 `com.google.android.youtube:id/results` 를 쓴다. */
    private const val RESULT_LIST_ID_SUFFIX = "/results"

    /** 누르면 영상이 열리는 항목의 content-desc 꼬리말. 기기 언어에 따라 달라진다. */
    private val PLAY_MARKERS = listOf("동영상 재생", "play video")

    /** 짧은 클립이라 "영상 틀어줘"의 답으로는 뒤로 미루는 항목. */
    private val SHORTS_MARKERS = listOf("shorts")
}
