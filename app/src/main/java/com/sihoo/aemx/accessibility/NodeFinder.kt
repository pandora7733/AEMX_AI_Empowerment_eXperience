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
                if (!isShorts(node)) return node
                if (shortsFallback == null) shortsFallback = node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return shortsFallback
    }

    /**
     * 유튜브 뮤직 검색 결과에서 맨 처음 나오는 노래를 고른다.
     *
     * 주의: 유튜브 뮤직의 접근성 트리는 아직 실기기에서 확인하지 못했다. 아래 규칙은 추정이고,
     * 실기기 uiautomator dump 로 보정해야 한다. 보정할 곳은 [SONG_KIND], [OTHER_KIND],
     * [MAX_ITEM_LABELS] 와 [itemLabels] 의 깊이다.
     *
     * 추정한 모양:
     * - 검색 결과는 세로 목록이고, 항목 하나가 눌 수 있는 컨테이너 하나다.
     * - 항목 안에 글자가 둘 이상 있다: 제목, 그리고 "노래 • 가수 • 앨범" 같은 부제(영문 기기는
     *   "Song • Artist"). 부제의 첫 단어가 종류(노래/앨범/아티스트/재생목록…)를 말해 준다.
     * - 상단의 필터 칩(노래, 동영상, 앨범…)은 글자가 하나뿐이라 둘 이상이라는 조건으로 걸러진다.
     *
     * 그래서 눌 수 있고 글자가 2~[MAX_ITEM_LABELS]개인 항목 중 부제가 "노래"로 시작하는 첫 항목을 고른다.
     * 그런 항목이 없으면(부제 문구가 다르면) 구분점(•)이 있는 부제를 가졌고 아티스트·앨범·
     * 재생목록 등으로 보이지 않는 첫 항목을 대신 고른다. 맨 위 "상위 결과" 카드가 아티스트면 건너뛴다.
     */
    fun findFirstSong(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        var fallback: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            if (++visited > 2_000) break
            val node = queue.removeFirst()
            if (isClickableNode(node)) {
                val labels = itemLabels(node)
                if (labels.size in 2..MAX_ITEM_LABELS) {
                    if (labels.any { SONG_KIND.containsMatchIn(it) }) return node
                    if (fallback == null &&
                        labels.any { it.contains('\u2022') || it.contains('\u00B7') } &&
                        labels.none { OTHER_KIND.containsMatchIn(it) }
                    ) {
                        fallback = node
                    }
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return fallback
    }

    private fun isClickableNode(node: AccessibilityNodeInfo): Boolean =
        node.isVisibleToUser && node.isEnabled &&
            (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })

    /** 항목 안의 글자(자신 포함, 몇 단계 아래까지). 중복은 뺀다. */
    private fun itemLabels(item: AccessibilityNodeInfo): List<String> {
        val labels = LinkedHashSet<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (labels.size > MAX_ITEM_LABELS) return
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= MAX_LABEL_LENGTH }
                .forEach { labels += it }
            if (depth >= ITEM_LABEL_DEPTH) return
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
        }
        visit(item, 0)
        return labels.toList()
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
     * Shorts 항목인지 본다.
     *
     * 제목에 "shorts" 가 들어간 일반 영상까지 Shorts 로 밀리지 않도록, 제목이 아니라
     * 꼬리말을 본다. 실기기에서 Shorts 는 `… - Shorts 동영상 재생` 으로 끝난다.
     */
    private fun isShorts(node: AccessibilityNodeInfo): Boolean {
        val description = node.contentDescription?.toString()?.trimEnd() ?: return false
        return SHORTS_MARKERS.any { description.endsWith(it, ignoreCase = true) }
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
     * 거르지 않으면 "첫 번째 영상"이 사용자가 찾던 영상이 아니라 광고가 된다.
     *
     * 실기기에서 "노트북 추천"을 검색해 광고 항목의 트리를 떠 보면 이렇게 생겼다.
     *
     *     ViewGroup  cd="스폰서 - 지금 놓치면 후회 | … | Temu"   ← 항목을 감싸는 컨테이너
     *       ViewGroup  cd="사이트 방문"  (누를 수 있음)
     *
     * 즉 광고 표시는 **항목을 감싸는 조상**에 붙고, 항목 자신의 설명에는 제목만 들어 있다.
     * 그래서 조상과, 항목 안에 따로 붙은 배지(라벨 전체가 정확히 일치할 때만)를 본다. 예전에는 항목 자신과 그 아래를 전부 훑었는데, 제목에 "광고"가
     * 들어간 멀쩡한 영상("추억의 TV 광고 모음")까지 광고로 걸러졌다(이슈 #13).
     *
     * 조상을 끝까지 올라가지 않고 몇 단계로 끊는 이유는, 더 올라가면 결과 목록이나 창
     * 전체가 나오고 그쪽 설명에 광고 문구가 한 번이라도 섞이면 결과를 통째로 버리기
     * 때문이다.
     */
    private fun isSponsored(item: AccessibilityNodeInfo): Boolean {
        var ancestor = item.parent
        var hops = 0
        while (ancestor != null && hops++ < AD_ANCESTOR_HOPS) {
            if (hasAdLabel(ancestor)) return true
            ancestor = ancestor.parent
        }
        return hasAdBadgeInside(item)
    }

    /**
     * 항목 안에 "광고" 같은 배지가 따로 있는지 본다.
     *
     * 항목 자신의 content-desc(제목)는 보지 않는다. 자식 노드 중 라벨 전체가 표시 문구와 정확히
     * 같은 것만 인정해서, 제목에 "광고"가 들어간 일반 영상은 걸리지 않는다. 배지는 항목 바로 아래
     * 몇 단계에 있고, 더 깊이 내려가면 비용만 늘어서 [AD_BADGE_DEPTH] 로 끊는다.
     */
    private fun hasAdBadgeInside(item: AccessibilityNodeInfo): Boolean {
        var level = listOf(item)
        repeat(AD_BADGE_DEPTH) {
            val next = ArrayList<AccessibilityNodeInfo>()
            for (parent in level) {
                for (index in 0 until parent.childCount) {
                    val child = parent.getChild(index) ?: continue
                    val labels = listOfNotNull(child.text?.toString(), child.contentDescription?.toString())
                    if (labels.any { raw -> AD_BADGES.any { raw.trim().equals(it, ignoreCase = true) } }) return true
                    next += child
                }
            }
            level = next
        }
        return false
    }

    /**
     * 광고 표시 라벨인지 본다.
     *
     * 라벨 전체가 표시 문구이거나(배지), 표시 문구로 시작하는 경우만 인정한다. 문자열
     * 어디에든 들어 있으면 광고로 보던 방식은 제목을 광고로 만든다.
     */
    private fun hasAdLabel(node: AccessibilityNodeInfo): Boolean {
        val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        return labels.any { raw ->
            val label = raw.trim()
            AD_MARKERS.any { marker ->
                label.equals(marker, ignoreCase = true) ||
                    label.startsWith("$marker ", ignoreCase = true) ||
                    label.startsWith("$marker·", ignoreCase = true)
            }
        }
    }

    private fun normalize(value: String): String = value
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("\\s+"), "")

    private const val MAX_LABELS = 40

    /** 유튜브 뮤직 검색 결과 한 항목에서 읽을 글자 수 상한. 더 많으면 항목이 아니라 목록 전체로 본다. */
    private const val MAX_ITEM_LABELS = 8
    private const val ITEM_LABEL_DEPTH = 4

    /** 부제가 "노래"(영문 기기는 Song)로 시작하거나 그 종류 표시가 따로 있는 글자. 실기기 dump 로 보정한다. */
    private val SONG_KIND = Regex("(^|[\\u2022\\u00B7,]\\s*)(노래|song)\\s*([\\u2022\\u00B7,]|$)", RegexOption.IGNORE_CASE)

    /** 노래가 아닌 종류 표시. 폴백에서 이런 항목은 건너뛴다. 실기기 dump 로 보정한다. */
    private val OTHER_KIND = Regex(
        "(^|[\\u2022\\u00B7,]\\s*)(아티스트|앨범|싱글|EP|재생목록|프로필|팟캐스트|에피소드|artist|album|single|playlist|profile|podcast|episode)\\s*([\\u2022\\u00B7,]|$)",
        RegexOption.IGNORE_CASE,
    )
    private const val MAX_LABEL_LENGTH = 60

    /** 광고 표시를 찾아 조상을 몇 단계까지 올라갈지. 실기기에서는 한 단계면 닿는다. */
    private const val AD_ANCESTOR_HOPS = 4

    /** 항목 안에 따로 붙는 배지의 라벨. 정확히 같을 때만 광고로 본다("Ad" 는 접두어로는 너무 흔해 여기서만 쓴다). */
    private val AD_BADGES = listOf("광고", "스폰서", "프로모션", "Sponsored", "Ad")

    /** 항목 아래로 배지를 찾아 내려갈 단계 수. */
    private const val AD_BADGE_DEPTH = 3

    /** 유튜브가 광고 항목에 붙이는 표시. 앱 업데이트로 문구가 바뀌면 여기를 고친다. */
    private val AD_MARKERS = listOf("스폰서", "sponsored", "광고", "프로모션")

    /** 검색 결과 목록의 resource-id. 유튜브는 `com.google.android.youtube:id/results` 를 쓴다. */
    private const val RESULT_LIST_ID_SUFFIX = "/results"

    /** 누르면 영상이 열리는 항목의 content-desc 꼬리말. 기기 언어에 따라 달라진다. */
    private val PLAY_MARKERS = listOf("동영상 재생", "play video")

    /**
     * 짧은 클립이라 "영상 틀어줘"의 답으로는 뒤로 미루는 항목의 꼬리말.
     * 제목에 "shorts" 가 들어간 일반 영상과 섞이지 않게 꼬리말 전체로 본다.
     */
    private val SHORTS_MARKERS = listOf("Shorts 동영상 재생", "Shorts video")
}
