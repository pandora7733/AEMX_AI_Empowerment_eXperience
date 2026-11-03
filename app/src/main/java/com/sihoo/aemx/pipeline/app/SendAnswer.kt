package com.sihoo.aemx.pipeline.app

/**
 * 보내기 확인에 대한 음성 답을 읽는다. 순수 함수라 안드로이드 없이 테스트한다.
 *
 * 취소 말은 길이와 상관없이 먼저 본다(잘못 보내는 쪽이 더 위험하다). 보내기 말은 짧은 답일 때만 인정한다.
 * 그래서 길게 떠드는 말 속에 "네"가 섞여도 보내지 않는다.
 */
object SendAnswer {

    private val NO = listOf("아니", "취소", "그만", "보내지", "안보내", "안해")
    private val YES = listOf("응", "네", "예", "보내", "맞아", "좋아", "그래", "해줘")

    /** 짧은 답이 보내기면 true, 취소면 false. 답으로 읽을 수 없으면 null. */
    fun parse(text: String): Boolean? {
        val squeezed = text.filterNot { it.isWhitespace() || it in PUNCTUATION }
        if (squeezed.isEmpty()) return null
        if (NO.any { squeezed.contains(it) }) return false
        if (squeezed.length <= MAX_YES_LENGTH && YES.any { squeezed.contains(it) }) return true
        return null
    }

    private const val PUNCTUATION = ".,!?~…"
    private const val MAX_YES_LENGTH = 12
}
