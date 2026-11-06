package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import com.sihoo.aemx.pipeline.model.Expectation
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.AppResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 목표 앱이 이미 앞에 떠 있는 상태에서 open_app 을 내면 화면이 안 바뀌어도 통과해야 한다(쿠팡 재분석 버그).
 * 다른 앱이 앞에 있거나 같은 앱 안의 TAP 이 아무 변화도 없으면 여전히 실패다.
 */
class OpenAlreadyFrontTest {

    private val coupang = "com.coupang.mobile"
    private val launcher = "com.sec.android.app.launcher"

    private fun screen(pkg: String, vararg texts: String) =
        ScreenSnapshot(packageName = pkg, className = "MainActivity", texts = texts.toList())

    /** open_app 단계가 만드는 기대치와 같은 모양(패키지 조건 + 화면 변화 + 이미 앞에 있으면 통과). */
    private val openCoupang = Expectation(
        packageName = coupang,
        requireScreenChange = true,
        alreadyOpenOk = true,
    )

    @Test
    fun `쿠팡이 이미 앞에 있고 화면이 같으면 통과하고 증거는 그 앱 화면이라고 적는다`() {
        val same = screen(coupang, "검색", "추천 상품")
        val verdict = ScreenVerifier.verify(openCoupang, before = same, after = same)
        assertTrue("이미 그 앱 화면이면 통과해야 한다: $verdict", verdict is ScreenVerifier.Verdict.Match)
        val evidence = (verdict as ScreenVerifier.Verdict.Match).evidence.orEmpty()
        assertTrue("증거에 '이미 그 앱 화면'이 있어야 한다: $evidence", evidence.contains("이미 그 앱 화면"))
        assertFalse("증거에 화면 글자가 새면 안 된다", evidence.contains("추천 상품"))
    }

    @Test
    fun `다른 앱이 앞에 있고 화면이 같으면 여전히 불일치다`() {
        val before = screen(launcher, "홈", "앱")
        val after = screen(launcher, "홈", "앱")
        val verdict = ScreenVerifier.verify(openCoupang, before = before, after = after)
        assertTrue("다른 앱이면 실패여야 한다: $verdict", verdict is ScreenVerifier.Verdict.Mismatch)
    }

    @Test
    fun `같은 앱 안에서 탭을 눌러도 아무 변화가 없으면 여전히 불일치다`() {
        val same = screen(coupang, "검색", "추천 상품")
        val tap = Expectation(requireScreenChange = true)
        val verdict = ScreenVerifier.verify(tap, before = same, after = same)
        assertTrue("탭은 이미 앞에 있어도 변화가 있어야 한다: $verdict", verdict is ScreenVerifier.Verdict.Mismatch)
    }

    @Test
    fun `이미 그 앱 화면이라도 열기 기대치가 아니면 바뀔 것을 요구한다`() {
        // alreadyOpenOk 가 꺼진 기대치는 같은 앱이어도 화면 변화를 요구한다(TAP, SEARCH 와 같다).
        val same = screen(coupang, "검색", "추천 상품")
        val withoutFlag = Expectation(packageName = coupang, requireScreenChange = true)
        val verdict = ScreenVerifier.verify(withoutFlag, before = same, after = same)
        assertTrue(verdict is ScreenVerifier.Verdict.Mismatch)
    }

    @Test
    fun `화면이 바뀌었으면 열기 기대치는 원래대로 통과한다`() {
        val before = screen(launcher, "홈")
        val after = screen(coupang, "검색", "추천 상품")
        val verdict = ScreenVerifier.verify(openCoupang, before = before, after = after)
        assertTrue("화면이 바뀌어 도착했으면 통과다: $verdict", verdict is ScreenVerifier.Verdict.Match)
        val evidence = (verdict as ScreenVerifier.Verdict.Match).evidence.orEmpty()
        assertTrue("이미 그 앱 화면이 아니라 화면이 바뀜으로 적혀야 한다: $evidence", evidence.contains("화면이 바뀜"))
    }

    @Test
    fun `열기 단계의 기대치는 패키지를 알 때 이미 앞에 있어도 되는 것으로 만든다`() {
        val resolver = object : AppResolver {
            override fun resolvePackage(appName: String): String? = if (appName == "쿠팡") coupang else null
        }
        val step = ActionStep(0, ActionType.OPEN_APP, "쿠팡")
        val expect = ExpectationInferencer(resolver).enrich(step, "쿠팡").expect
        assertEquals(coupang, expect.packageName)
        assertTrue(expect.alreadyOpenOk)
    }

    @Test
    fun `패키지를 모르는 열기 기대치는 이미 앞에 있어도 되는 것으로 만들지 않는다`() {
        val resolver = object : AppResolver {
            override fun resolvePackage(appName: String): String? = null
        }
        val step = ActionStep(0, ActionType.OPEN_APP, "모르는앱")
        val expect = ExpectationInferencer(resolver).enrich(step, "모르는앱").expect
        assertFalse(expect.alreadyOpenOk)
    }
}
