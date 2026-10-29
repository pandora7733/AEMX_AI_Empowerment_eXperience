package com.sihoo.aemx.accessibility

/**
 * "두 번째", "2번째", "셋째", "3" 같은 순번 말을 읽는다. play_first 가 몇 번째 항목을 누를지 정하는 데 쓴다.
 *
 * 말에서 찾을 때는 "번째/째" 가 붙은 꼴만 본다. 숫자만 있는 말("2024 검색")을 순번으로 읽으면 안 되기 때문이다.
 * 숫자만 오는 것은 step 의 value 처럼 순번이라고 정해진 자리에서만 받는다([find]).
 */
internal object Ordinal {
    const val MAX = 20

    private val WORDS = mapOf(
        "첫" to 1, "두" to 2, "둘" to 2, "세" to 3, "셋" to 3, "네" to 4, "넷" to 4,
        "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9, "열" to 10,
    )

    /** "두 번째", "2번째", "첫번째". */
    private val BEON_JJAE = Regex("(\\d{1,2}|첫|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*번\\s*째")

    /** "둘째", "셋째", "2째". */
    private val JJAE = Regex("(\\d{1,2}|첫|둘|셋|넷|다섯|여섯|일곱|여덟|아홉|열)\\s*째")

    /** "2번 영상", "3번 노래". */
    private val BEON_NOUN = Regex("(\\d{1,2})\\s*번\\s*(?:영상|동영상|노래|곡|항목)")

    /** 발화에 순번 말이 있으면 그 값(1~[MAX]). 없으면 null. */
    fun inUtterance(text: String): Int? {
        for (pattern in listOf(BEON_JJAE, JJAE, BEON_NOUN)) {
            val match = pattern.find(text) ?: continue
            val n = toNumber(match.groupValues[1]) ?: continue
            if (n in 1..MAX) return n
        }
        return null
    }

    /** step 의 value 같이 순번이 오기로 한 자리. 숫자만 있어도 받고, 아니면 [inUtterance] 로 읽는다. */
    fun find(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() } && text.length <= 2) return text.toInt().takeIf { it in 1..MAX }
        return inUtterance(text)
    }

    private fun toNumber(word: String): Int? = word.toIntOrNull() ?: WORDS[word]
}

/**
 * N번째 항목을 화면 스크롤을 건너 세는 기록. 화면 하나에 N개가 안 보이면 한 번만 스크롤해서 이어 센다.
 *
 * 화면에 보이는 항목의 키를 위에서부터 [pick] 에 주면, 처음 보는 키는 순서대로 쌓고 N번째 키가 지금 화면에
 * 있으면 그 자리를 준다. 스크롤하면 이미 센 항목이 다시 보이므로 키로 겹침을 걸러 센다.
 */
internal class OrdinalTracker(private val n: Int) {
    private val seen = ArrayList<String>()
    private var scrolledAt = 0L
    private var scrolls = 0
    private var seenAtLastScroll = 0

    val scrolled: Boolean get() = scrolls > 0

    /** 지금까지 한 스크롤 횟수. 한 단계 안에서 이어 센다. */
    val scrollCount: Int get() = scrolls

    /** @return [visible] 안에서 N번째 항목의 자리. 아직 N개를 못 세었거나 N번째가 화면 밖이면 null. */
    fun pick(visible: List<String>): Int? {
        for (key in visible) if (key !in seen) seen += key
        val target = seen.getOrNull(n - 1) ?: return null
        return visible.indexOf(target).takeIf { it >= 0 }
    }

    fun markScrolled(now: Long) {
        scrolledAt = now
        scrolls++
        seenAtLastScroll = seen.size
    }

    /** 마지막 스크롤 뒤에 처음 보는 항목이 하나도 없었는가. 그러면 목록 끝까지 본 것이다. [pick] 뒤에 부른다. */
    fun noNewSinceLastScroll(): Boolean = scrolls > 0 && seen.size == seenAtLastScroll

    /** 스크롤하고 화면이 자리 잡기를 기다리는 중인가. */
    fun settling(now: Long): Boolean = scrolled && now - scrolledAt < SETTLE_MS

    companion object {
        const val SETTLE_MS = 900L
    }
}
