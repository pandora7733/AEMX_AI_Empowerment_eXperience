package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.Expectation
import com.sihoo.aemx.pipeline.model.ScreenSnapshot

/**
 * step 을 실행한 뒤 화면이 기대대로 바뀌었는지 판정한다.
 *
 * 판정은 두 갈래다.
 *  1. [KnownSituation] 탐지 — 알려진 실패 상황(로그인 실패 팝업 등)이 떠 있는가.
 *  2. [Expectation] 대조 — 그 step 이 기대한 조건을 만족하는가.
 *
 * 1번이 먼저다. 기대 조건을 만족했더라도 실패 팝업이 떠 있으면 실패로 본다.
 * "로그인" 버튼을 눌러 화면은 분명히 바뀌었는데(=requireScreenChange 만족)
 * 사실은 "비밀번호가 올바르지 않습니다" 팝업이 뜬 경우가 딱 그 형태다.
 *
 * 로그인 관련 상황(실패/이미 로그인)은 로그인 step 에서만 본다. 로그인한 앱 화면에는
 * "로그아웃", "마이페이지" 가 늘 있어서, 아무 step 에서나 보면 멀쩡한 화면을 실패로 친다.
 * 캡차·인증은 사람에게 넘기는 안전장치라 step 과 상관없이 늘 본다.
 */
object ScreenVerifier {

    /**
     * @param before step 을 실행하기 직전의 화면. 화면 변화 여부를 보는 데 쓴다.
     * @param after  화면이 안정된 뒤의 화면.
     * @param loginRelated 로그인 관련 step 인지. 아니면 로그인 상황 탐지를 건너뛴다.
     */
    fun verify(
        expect: Expectation,
        before: ScreenSnapshot,
        after: ScreenSnapshot,
        loginRelated: Boolean = true
    ): Verdict {
        KnownSituation.detect(after, loginRelated)?.let { situation ->
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

    /** step 의 대상·값·기대 설명에 로그인을 가리키는 말이 있으면 로그인 step 으로 본다. */
    fun isLoginStep(step: ActionStep): Boolean {
        val text = listOfNotNull(step.target, step.value, step.expect.note).joinToString(" ").lowercase()
        return LOGIN_HINTS.any { it in text }
    }

    private val LOGIN_HINTS = listOf("로그인", "login", "sign in", "아이디", "비밀번호", "password")

    sealed interface Verdict {
        data object Match : Verdict
        data class Mismatch(val reason: String, val situation: KnownSituation? = null) : Verdict
    }
}

/**
 * 자주 만나는 화면 상황들.
 *
 * 처음부터 모든 경우를 덮으려 하지 않고, 기획에서 지목한 흔한 두 가지
 * (로그인 실패 / 이미 로그인된 상태)와, 자동화를 멈춰야 하는 경우만 둔다.
 * 키워드는 사이트마다 다르니 실기기에서 부딪히며 늘려 간다.
 */
enum class KnownSituation(
    private val keywords: List<String>,
    /** 이 상황이면 step 검증을 실패로 보고 재분석할지. */
    val treatAsFailure: Boolean,
    /** 자동 진행을 멈추고 사람에게 넘겨야 하는 상황인지. */
    val requiresHuman: Boolean
) {

    /** 로그인 실패. 재분석해서 다시 시도할지 포기할지 판단하게 한다. */
    LOGIN_FAILED(
        keywords = listOf(
            "비밀번호가 올바르지", "비밀번호를 확인", "아이디 또는 비밀번호",
            "로그인에 실패", "로그인 실패", "일치하지 않습니다", "등록되지 않은",
            "incorrect password", "login failed"
        ),
        treatAsFailure = true,
        requiresHuman = false
    ),

    /** 이미 로그인돼 있어 로그인 화면이 아예 안 나오는 경우. 남은 step 을 건너뛰어야 한다. */
    ALREADY_LOGGED_IN(
        keywords = listOf(
            "로그아웃", "마이페이지", "내 정보", "님 환영", "님 안녕",
            "sign out", "log out"
        ),
        treatAsFailure = true,
        requiresHuman = false
    ),

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

    /** 로그인과 관련된 상황인가. 로그인 step 에서만 탐지한다. */
    val loginRelated: Boolean
        get() = this == LOGIN_FAILED || this == ALREADY_LOGGED_IN

    /** 이 상황으로 판단한 근거를 사람이 읽을 수 있게. */
    fun reason(screen: ScreenSnapshot): String {
        val hit = keywords.firstOrNull { screen.containsText(it) }
        val label = when (this) {
            LOGIN_FAILED -> "로그인이 실패한 것으로 보인다"
            ALREADY_LOGGED_IN -> "이미 로그인된 상태로 보인다"
            HUMAN_VERIFICATION -> "사람이 직접 해야 하는 인증 화면이다"
        }
        return if (hit != null) "$label (\"$hit\" 발견)" else label
    }

    companion object {
        /**
         * 화면에서 알려진 상황을 찾는다.
         * 사람 개입이 필요한 쪽을 먼저 보므로, 로그인 실패와 캡차가 함께 보이면 캡차가 이긴다.
         * [includeLogin] 이 false 면 로그인 관련 상황은 건너뛴다.
         */
        fun detect(screen: ScreenSnapshot, includeLogin: Boolean = true): KnownSituation? =
            entries.filter { includeLogin || !it.loginRelated }
                .sortedByDescending { it.requiresHuman }
                .firstOrNull { situation -> situation.keywords.any { screen.containsText(it) } }
    }
}
