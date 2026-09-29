package com.sihoo.aemx.pipeline.model

/**
 * 어느 한 순간의 화면 요약.
 *
 * 접근성 노드 트리를 통째로 들고 있으면 온디바이스 LLM 컨텍스트에 들어가지 않는다.
 * 그래서 판정과 재분석에 필요한 최소한만 남긴 납작한 형태로 둔다.
 */
data class ScreenSnapshot(
    val packageName: String?,
    /** 현재 창의 클래스명(액티비티/다이얼로그 구분에 쓴다). */
    val className: String?,
    /** 화면에서 읽은 텍스트들. 순서는 노드 순회 순서를 따른다. */
    val texts: List<String>,
    /** 눌 수 있는 요소들의 라벨. 재분석 때 "무엇을 누를 수 있는지" 알려주는 용도. */
    val clickableLabels: List<String> = emptyList(),
    /** 이 화면이 팝업/다이얼로그로 보이는지. */
    val isDialog: Boolean = false,
    val capturedAt: Long = System.currentTimeMillis()
) {
    /**
     * 같은 화면인지 비교하기 위한 지문.
     * 재분석이 같은 화면을 맴돌고 있는지 감지하는 데 쓴다.
     */
    fun signature(): String = buildString {
        append(packageName ?: "?")
        append('|')
        append(className ?: "?")
        append('|')
        append(texts.take(SIGNATURE_TEXT_COUNT).joinToString("\u0001"))
    }

    /** 화면에 이 문자열이 (부분일치로) 보이는지. */
    fun containsText(needle: String): Boolean {
        val target = needle.trim()
        if (target.isEmpty()) return false
        return texts.any { it.contains(target, ignoreCase = true) } ||
            clickableLabels.any { it.contains(target, ignoreCase = true) }
    }

    /**
     * LLM 에 넘길 요약 문자열.
     * 토큰 예산 때문에 줄 수와 길이를 모두 자른다. 자른 경우 그 사실을 함께 적는다.
     */
    fun toPromptText(
        maxTexts: Int = MAX_PROMPT_TEXTS,
        maxCharsPerLine: Int = MAX_CHARS_PER_LINE
    ): String = buildString {
        append("앱: ").append(packageName ?: "알 수 없음").append('\n')
        append("화면: ").append(className ?: "알 수 없음")
        if (isDialog) append(" (팝업)")
        append('\n')
        append("보이는 내용:\n")
        val shown = texts.take(maxTexts)
        shown.forEach { line ->
            append("- ").append(line.take(maxCharsPerLine)).append('\n')
        }
        if (texts.size > shown.size) {
            append("- ...(").append(texts.size - shown.size).append("줄 더 있음, 생략)\n")
        }
        if (clickableLabels.isNotEmpty()) {
            append("누를 수 있는 것: ")
            append(clickableLabels.take(maxTexts).joinToString(", ") { it.take(maxCharsPerLine) })
            append('\n')
        }
    }

    companion object {
        const val SIGNATURE_TEXT_COUNT = 10
        const val MAX_PROMPT_TEXTS = 25
        const val MAX_CHARS_PER_LINE = 60

        val EMPTY = ScreenSnapshot(null, null, emptyList())
    }
}
