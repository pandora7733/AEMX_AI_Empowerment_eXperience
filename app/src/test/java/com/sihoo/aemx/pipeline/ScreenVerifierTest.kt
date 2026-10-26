package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.model.Expectation
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenVerifierTest {

    private fun screen(vararg texts: String, pkg: String = "com.example.fake.aladin") =
        ScreenSnapshot(packageName = pkg, className = "LoginActivity", texts = texts.toList())

    @Test
    fun `기대가 비어 있으면 통과한다`() {
        val s = screen("아무 말")
        assertEquals(ScreenVerifier.Verdict.Match, ScreenVerifier.verify(Expectation.NONE, s, s))
    }

    @Test
    fun `패키지가 다르면 불일치다`() {
        val verdict = ScreenVerifier.verify(
            Expectation(packageName = "com.example.fake.aladin"),
            before = screen(pkg = "com.android.launcher"),
            after = screen("홈", pkg = "com.android.launcher")
        )
        assertTrue(verdict is ScreenVerifier.Verdict.Mismatch)
    }

    @Test
    fun `화면이 그대로면 화면 변화 기대가 깨진다`() {
        val same = screen("로그인", "아이디", "비밀번호")
        val verdict = ScreenVerifier.verify(
            Expectation(requireScreenChange = true),
            before = same,
            after = same
        )
        assertTrue(verdict is ScreenVerifier.Verdict.Mismatch)
        assertTrue((verdict as ScreenVerifier.Verdict.Mismatch).reason.contains("바뀌지 않았다"))
    }

    @Test
    fun `화면이 바뀌면 화면 변화 기대를 만족한다`() {
        val verdict = ScreenVerifier.verify(
            Expectation(requireScreenChange = true),
            before = screen("로그인"),
            after = screen("아이디", "비밀번호")
        )
        assertEquals(ScreenVerifier.Verdict.Match, verdict)
    }

    @Test
    fun `입력한 텍스트가 보이면 통과한다`() {
        val verdict = ScreenVerifier.verify(
            Expectation(anyText = listOf("토지")),
            before = screen("검색창"),
            after = screen("검색창", "토지")
        )
        assertEquals(ScreenVerifier.Verdict.Match, verdict)
    }

    // ------------------------------------------------ 흔한 실패 케이스

    @Test
    fun `로그아웃 글자가 있는 로그인된 화면은 정상으로 통과한다`() {
        // 로그인한 네이버 앱 화면에는 "로그아웃" 이 늘 있다. 로그인은 범위 밖이라 이걸로 실패하면 안 된다.
        val naver = screen("검색", "로그아웃", "마이페이지", pkg = "com.nhn.android.search")
        val verdict = ScreenVerifier.verify(
            Expectation(packageName = "com.nhn.android.search"),
            before = screen("홈", pkg = "com.android.launcher"),
            after = naver
        )
        assertEquals(ScreenVerifier.Verdict.Match, verdict)
    }

    @Test
    fun `캡차는 어떤 step 에서도 사람에게 넘긴다`() {
        val verdict = ScreenVerifier.verify(
            Expectation.NONE,
            before = screen("홈"),
            after = screen("보안문자를 입력해 주세요", "로그아웃")
        )
        assertEquals(
            KnownSituation.HUMAN_VERIFICATION,
            (verdict as ScreenVerifier.Verdict.Mismatch).situation
        )
    }

    @Test
    fun `캡차는 사람 개입이 필요한 상황으로 분류한다`() {
        val situation = KnownSituation.detect(screen("자동입력 방지문자를 입력하세요"))
        assertEquals(KnownSituation.HUMAN_VERIFICATION, situation)
        assertTrue(situation!!.requiresHuman)
    }

    @Test
    fun `평범한 화면에서는 아무 상황도 탐지되지 않는다`() {
        assertNull(KnownSituation.detect(screen("베스트셀러", "신간", "장바구니")))
    }

    @Test
    fun `영문 단어 안에 키워드가 끼어 있으면 캡차로 보지 않는다`() {
        assertNull(KnownSituation.detect(screen("Footprint", "hotpot", "Otpusk")))
    }

    @Test
    fun `OTP 인증번호 입력 화면은 캡차로 본다`() {
        val situation = KnownSituation.detect(screen("OTP 인증번호 입력"))
        assertEquals(KnownSituation.HUMAN_VERIFICATION, situation)
        // 한글 없이 OTP 만 따로 있어도 단어로 보인다.
        assertEquals(
            KnownSituation.HUMAN_VERIFICATION,
            KnownSituation.detect(screen("Enter OTP:"))
        )
    }

    @Test
    fun `한글 키워드는 붙여 써도 찾는다`() {
        assertEquals(
            KnownSituation.HUMAN_VERIFICATION,
            KnownSituation.detect(screen("보안문자를입력해주세요"))
        )
    }

    @Test
    fun `메시지 앱 대화 목록에 인증번호가 보여도 통과한다`() {
        for (pkg in listOf("com.samsung.android.messaging", "com.google.android.apps.messaging", "com.kakao.talk")) {
            val list = screen("엄마", "인증번호 123456", "[Web발신]", pkg = pkg)
            assertNull(KnownSituation.detect(list))
            assertEquals(ScreenVerifier.Verdict.Match, ScreenVerifier.verify(Expectation.NONE, list, list))
        }
    }

    @Test
    fun `일반 앱의 인증 화면은 그대로 걸린다`() {
        val situation = KnownSituation.detect(screen("인증번호 입력", pkg = "com.nhn.android.search"))
        assertEquals(KnownSituation.HUMAN_VERIFICATION, situation)
    }
}
