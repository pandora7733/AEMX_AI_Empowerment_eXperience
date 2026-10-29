package com.sihoo.aemx.pipeline

/**
 * 누를 대상이 "몇 번째 항목", "가장 위 항목" 같은 위치 설명인지 가린다.
 *
 * 이런 대상은 화면 글자로 찾을 수 없어서 헤맨다(사용자가 5초 넘게 기다렸다). 재분석해도 같은 실패가 되풀이되므로
 * 누르지 않고 바로 끝내며, 못 한다는 것을 알린다. 유튜브 재생(play_first)의 N번째는 따로 다룬다.
 */
object TapTargetRules {

    const val POSITIONAL_MESSAGE = "화면에서 몇 번째 항목을 누르는 건 아직 못 해요"

    private val ORDINAL = Regex("(첫|두|세|네|다섯|여섯|일곱|여덟|아홉|열|\\d+)번째")

    /** 공백을 뺀 대상에 위치 말이 들어 있는가. */
    private val POSITION_WORDS = listOf("가장위", "가장상단", "가장아래", "맨위", "맨아래", "맨앞", "맨뒤")

    fun isPositional(target: String): Boolean {
        val key = target.filterNot { it.isWhitespace() }
        return ORDINAL.containsMatchIn(key) || POSITION_WORDS.any { key.contains(it) }
    }
}
