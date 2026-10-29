package com.sihoo.aemx.pipeline.model

/**
 * 화면 글자와 찾는 글자를 비교하는 규칙. 서비스(실행)와 오케스트레이터(판정)가 같은 규칙을 쓴다.
 *
 * 공백과 대소문자는 무시한다. 화면은 "손흥민 하이라이트"를 "손흥민하이라이트"로 줄여 보여 주기도 해서다.
 */
object TextMatch {

    /** 검색어 확인에 쓰는 앞부분 길이(공백 제외). 긴 검색어는 화면에서 잘려 보일 수 있다. */
    const val QUERY_KEY_LENGTH = 8

    fun key(text: String): String = text.filterNot { it.isWhitespace() }.lowercase()

    /** [text] 안에 [needle] 이 (부분일치로) 있는가. [needle] 이 비면 false. */
    fun contains(text: String, needle: String): Boolean {
        val wanted = key(needle)
        return wanted.isNotEmpty() && key(text).contains(wanted)
    }

    /**
     * 검색어가 화면 글자 중 하나에라도 보이는가. 검색어의 앞 [QUERY_KEY_LENGTH] 글자만 본다.
     * 이전 검색 결과 화면에는 이번 검색어가 없으므로, 이것이 참이어야 "이번 검색 결과"다.
     */
    fun queryVisible(texts: Iterable<String>, query: String): Boolean {
        val probe = key(query).take(QUERY_KEY_LENGTH)
        return probe.isNotEmpty() && texts.any { key(it).contains(probe) }
    }
}
