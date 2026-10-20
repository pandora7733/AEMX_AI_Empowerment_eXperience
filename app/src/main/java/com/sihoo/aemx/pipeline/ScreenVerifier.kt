package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.model.Expectation
import com.sihoo.aemx.pipeline.model.ScreenSnapshot

/**
 * step 을 실행한 뒤 화면이 기대대로 바뀌었는지 판정한다.
 *
 * 판정은 두 갈래다.
 *  1. [KnownSituation] 탐지 — 사람에게 넘겨야 하는 상황(캡차·본인인증)이 떠 있는가.
 *  2. [Expectation] 대조 — 그 step 이 기대한 조건을 만족하는가.
 *
 * 1번이 먼저다. 기대 조건을 만족했더라도 캡차가 떠 있으면 사람에게 넘긴다.
 * 로그인·자격증명은 범위에서 빠져서 로그인 화면을 따로 판정하지 않는다. 로그인한 앱 화면에는
 * "로그아웃", "마이페이지" 가 늘 있어서, 그런 글자로 상황을 판정하면 멀쩡한 화면을 실패로 친다.
 */
object ScreenVerifier {

    /**
     * @param before step 을 실행하기 직전의 화면. 화면 변화 여부를 보는 데 쓴다.
     * @param after  화면이 안정된 뒤의 화면.
     */
    fun verify(
        expect: Expectation,
        before: ScreenSnapshot,
        after: ScreenSnapshot
    ): Verdict {
        KnownSituation.detect(after)?.let { situation ->
            if (situation.treatAsFailure) {
                return Verdict.Mismatch(situation.reason(after), situation)
            }
        }

        if (expect.isEmpty) return Verdict.Match

        expect.packageName?.let { wanted ->
            if (!wanted.equals(after.packageName, ignoreCase = true)) {
                return Verdict.Mismatch(
                    "$wanted 화면을 기대했는데 지금은 ${after.packageName ?: "알 수 없는 앱"} 이다"
                )
            }
        }

        if (expect.requireScreenChange && before.signature() == after.signature()) {
            return Verdict.Mismatch("화면이 전혀 바뀌지 않았다")
        }

        if (expect.anyText.isNotEmpty() && expect.anyText.none { after.containsText(it) }) {
            return Verdict.Mismatch(
                "${expect.anyText.joinToString(", ")} 중 아무것도 화면에 없다"
            )
        }

        expect.absentText.firstOrNull { after.containsText(it) }?.let { forbidden ->
            return Verdict.Mismatch("화면에 \"$forbidden\" 이 떠 있다")
        }

        return Verdict.Match
    }

    sealed interface Verdict {
        data object Match : Verdict
        data class Mismatch(val reason: String, val situation: KnownSituation? = null) : Verdict
    }
}

/**
 * 자동화를 멈추고 사람에게 넘겨야 하는 화면 상황.
 *
 * 로그인 실패·이미 로그인됨 판정은 로그인이 범위에서 빠지면서 없앴다(#10). 키워드는 사이트마다
 * 다르니 실기기에서 부딪히며 늘려 간다.
 */
enum class KnownSituation(
    private val keywords: List<String>,
    /** 이 상황이면 step 검증을 실패로 보고 재분석할지. */
    val treatAsFailure: Boolean,
    /** 자동 진행을 멈추고 사람에게 넘겨야 하는 상황인지. */
    val requiresHuman: Boolean
) {

    /**
     * 캡차·2단계 인증. **자동으로 뚫지 않는다.**
     * 사람이 직접 처리하도록 넘기고 파이프라인은 멈춘다.
     */
    HUMAN_VERIFICATION(
        keywords = listOf(
            "자동입력 방지", "보안문자", "캡차", "captcha", "로봇이 아닙니다",
            "인증번호", "2단계 인증", "본인확인", "otp"
        ),
        treatAsFailure = true,
        requiresHuman = true
    );

    /** 이 상황으로 판단한 근거를 사람이 읽을 수 있게. */
    fun reason(screen: ScreenSnapshot): String {
        val hit = keywords.firstOrNull { matches(screen, it) }
        val label = when (this) {
            HUMAN_VERIFICATION -> "사람이 직접 해야 하는 인증 화면이다"
        }
        return if (hit != null) "$label (\"$hit\" 발견)" else label
    }

    companion object {
        /** 화면에서 알려진 상황을 찾는다. 사람 개입이 필요한 쪽을 먼저 본다. */
        fun detect(screen: ScreenSnapshot): KnownSituation? =
            entries.sortedByDescending { it.requiresHuman }
                .firstOrNull { situation -> situation.keywords.any { matches(screen, it) } }

        /**
         * 키워드가 화면에 있는지.
         *
         * 영문 키워드는 앞뒤가 영문자가 아닐 때만 맞는 단어 경계로 찾는다. 부분 문자열로 찾으면
         * "footprint", "hotpot" 에 "otp" 가 걸려 멀쩡한 화면에서 사람에게 넘어간다.
         * 한글 키워드는 붙여 쓰는 일이 많아("보안문자를입력") 경계를 두면 오히려 놓치므로
         * [ScreenSnapshot.containsText] 의 부분 문자열 일치를 그대로 쓴다.
         * 대소문자는 둘 다 무시한다.
         */
        internal fun matches(screen: ScreenSnapshot, keyword: String): Boolean {
            val word = keyword.trim()
            if (word.isEmpty()) return false
            if (!word.all { it.code < 128 }) return screen.containsText(word)
            return screen.texts.any { containsWord(it, word) } ||
                screen.clickableLabels.any { containsWord(it, word) }
        }

        private fun containsWord(text: String, word: String): Boolean {
            var from = 0
            while (true) {
                val at = text.indexOf(word, from, ignoreCase = true)
                if (at < 0) return false
                val before = text.getOrNull(at - 1)
                val after = text.getOrNull(at + word.length)
                if (!isAsciiLetter(before) && !isAsciiLetter(after)) return true
                from = at + 1
            }
        }

        private fun isAsciiLetter(c: Char?): Boolean = c != null && (c in 'a'..'z' || c in 'A'..'Z')
    }
}
