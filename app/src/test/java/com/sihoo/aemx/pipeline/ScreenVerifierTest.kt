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
    fun `로그인 실패 팝업은 화면이 바뀌었어도 실패로 본다`() {
        // 로그인 버튼을 눌러 화면은 분명히 바뀌었다(=requireScreenChange 는 만족).
        // 그래도 실패 팝업이 떠 있으면 성공이 아니다.
        val verdict = ScreenVerifier.verify(
            Expectation(requireScreenChange = true),
            before = screen("로그인", "아이디"),
            after = screen("비밀번호가 올바르지 않습니다", "확인")
        )
        assertTrue(verdict is ScreenVerifier.Verdict.Mismatch)
        assertEquals(
            KnownSituation.LOGIN_FAILED,
            (verdict as ScreenVerifier.Verdict.Mismatch).situation
        )
    }

    @Test
    fun `이미 로그인된 화면을 알아챈다`() {
        val verdict = ScreenVerifier.verify(
            Expectation.NONE,
            before = screen("홈"),
            after = screen("마이페이지", "로그아웃")
        )
        assertTrue(verdict is ScreenVerifier.Verdict.Mismatch)
        assertEquals(
            KnownSituation.ALREADY_LOGGED_IN,
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
    fun `캡차와 로그인 실패가 같이 보이면 캡차가 이긴다`() {
        // 둘 다 뜨는 화면에서 자동 재시도로 캡차를 뚫으려 들면 안 된다.
        val situation = KnownSituation.detect(
            screen("로그인에 실패했습니다", "보안문자를 입력해 주세요")
        )
        assertEquals(KnownSituation.HUMAN_VERIFICATION, situation)
    }

    @Test
    fun `평범한 화면에서는 아무 상황도 탐지되지 않는다`() {
        assertNull(KnownSituation.detect(screen("베스트셀러", "신간", "장바구니")))
    }
}
