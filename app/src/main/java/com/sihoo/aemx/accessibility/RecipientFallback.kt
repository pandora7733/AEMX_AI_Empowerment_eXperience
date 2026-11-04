package com.sihoo.aemx.accessibility

/**
 * 말한 받는 사람 이름이 연락처에 정확히 없을 때 가까운 연락처 이름을 후보로 모은다. 안드로이드 없이 테스트한다.
 *
 * 후보는 이 순서로 모아 최대 [MAX_CANDIDATES] 명까지 돌려준다.
 * 1. 뒷부분 일치: 말한 이름이 연락처 이름의 뒤쪽과 같다("정우" -> "김정우"). 말한 이름이 두 글자 이상일 때만 본다.
 * 2. 자모 편집거리: 음절 수가 같은 이름 중 자모 차이가 허용치 이하인 것("배제준" -> "배재준").
 *    허용치는 3음절 이하 1, 4음절 이상 2. 짧은 이름은 한 자모만 달라도 다른 사람일 수 있어서 보수적으로 잡는다.
 *
 * 말한 이름이 연락처에 정확히 있으면 후보는 쓰지 않는다([Pick.Exact]). 후보가 하나면 확인 화면에서 그 이름이 미리 골라진다.
 * 여럿이면 아무것도 고르지 않고 사용자가 확인 화면에서 고른다. 연락처 이름은 폰 밖으로 보내지 않는다.
 */
internal object RecipientFallback {

    /** 확인 화면에 보여 줄 후보 수의 위쪽 한계. */
    const val MAX_CANDIDATES = 4

    sealed interface Pick {
        /** 말한 이름이 연락처에 정확히 있다. 후보가 필요 없다. */
        data object Exact : Pick

        /** 가까운 연락처 이름들. 앞쪽일수록 먼저 보여 준다. 1개 이상 [MAX_CANDIDATES] 개 이하. */
        data class Candidates(val names: List<String>) : Pick

        /** 후보가 없다. [reason] 은 로그용 짧은 사유(이름은 넣지 않는다). */
        data class None(val reason: String) : Pick
    }

    fun pick(spoken: String, contactNames: Collection<String>): Pick {
        val wanted = spoken.filterNot(Char::isWhitespace)
        if (!isHangulName(wanted)) return Pick.None("한글 이름이 아님")
        val names = contactNames.map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() }.distinct()
        if (names.any { MessageRules.sameText(it, wanted) }) return Pick.Exact
        if (wanted.length < MIN_SYLLABLES) return Pick.None("한 글자 이름")

        val suffix = names
            .filter { isHangulName(it) && it.length > wanted.length && it.endsWith(wanted) }
            .sorted()
        val allowed = if (wanted.length <= SHORT_NAME_SYLLABLES) SHORT_NAME_DISTANCE else LONG_NAME_DISTANCE
        val similar = names
            .filter { isHangulName(it) && it.length == wanted.length }
            .map { it to jamoDistance(wanted, it) }
            .filter { (_, distance) -> distance <= allowed }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .map { it.first }

        val candidates = (suffix + similar).distinct().take(MAX_CANDIDATES)
        return if (candidates.isEmpty()) Pick.None("가까운 연락처 이름 없음") else Pick.Candidates(candidates)
    }

    /**
     * 사용자가 고른 이름을 검증한다. 후보([allowed]) 중 하나일 때만 그 이름을 돌려주고, 아니면 null 이다.
     * 확인 화면이 보낸 선택이라도 후보 목록에 없는 이름으로는 보내지 않는다.
     */
    fun accepted(allowed: List<String>, chosen: String?): String? = chosen?.takeIf { it in allowed }

    /** 두 이름을 음절마다 초성·중성·종성으로 풀어서 편집거리(삽입, 삭제, 바꿈)를 잰다. */
    internal fun jamoDistance(a: String, b: String): Int {
        val left = jamo(a)
        val right = jamo(b)
        var previous = IntArray(right.size + 1) { it }
        for (i in 1..left.size) {
            val current = IntArray(right.size + 1)
            current[0] = i
            for (j in 1..right.size) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[right.size]
    }

    private fun jamo(name: String): IntArray {
        val out = ArrayList<Int>(name.length * 3)
        for (c in name) {
            val code = c.code - HANGUL_BASE
            val cho = code / (JUNG_COUNT * JONG_COUNT)
            val jung = (code / JONG_COUNT) % JUNG_COUNT
            val jong = code % JONG_COUNT
            out += cho
            out += JUNG_OFFSET + jung
            if (jong != 0) out += JONG_OFFSET + jong
        }
        return out.toIntArray()
    }

    private fun isHangulName(name: String): Boolean =
        name.isNotEmpty() && name.all { it.code in HANGUL_BASE..HANGUL_LAST }

    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_LAST = 0xD7A3
    private const val JUNG_COUNT = 21
    private const val JONG_COUNT = 28
    private const val JUNG_OFFSET = 100
    private const val JONG_OFFSET = 200

    private const val MIN_SYLLABLES = 2
    private const val SHORT_NAME_SYLLABLES = 3
    private const val SHORT_NAME_DISTANCE = 1
    private const val LONG_NAME_DISTANCE = 2
}
