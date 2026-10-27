package com.sihoo.aemx.pipeline

import com.sihoo.aemx.accessibility.PlanOutcome
import com.sihoo.aemx.pipeline.device.AccessibilityStepExecutor
import com.sihoo.aemx.pipeline.model.Expectation
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.ExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sihoo.aemx.accessibility.ActionType as A11yType

/** 성공을 뒷받침하는 증거가 검증기와 실행모듈에서 오케스트레이터까지 전해지는지 본다. */
class EvidenceTest {

    private fun screen(vararg texts: String, pkg: String = "com.example.fake") =
        ScreenSnapshot(packageName = pkg, className = "Screen", texts = texts.toList())

    private fun match(expect: Expectation, before: ScreenSnapshot, after: ScreenSnapshot) =
        ScreenVerifier.verify(expect, before, after) as ScreenVerifier.Verdict.Match

    @Test
    fun `기대 조건이 없으면 증거도 없다`() {
        val s = screen("홈")
        assertNull(match(Expectation.NONE, s, s).evidence)
        assertNull(match(Expectation(note = "대기만 한다"), s, s).evidence)
    }

    @Test
    fun `확인한 조건마다 증거에 적힌다`() {
        val evidence = match(
            Expectation(packageName = "com.example.fake", requireScreenChange = true, anyText = listOf("토지")),
            before = screen("검색창"),
            after = screen("검색창", "토지")
        ).evidence!!

        assertTrue(evidence.contains("기대한 앱"))
        assertTrue(evidence.contains("화면이 바뀜"))
        assertTrue(evidence.contains("기대한 글자"))
    }

    @Test
    fun `증거에는 화면 글자나 검색어가 들어가지 않는다`() {
        val evidence = match(
            Expectation(anyText = listOf("비밀스러운검색어")),
            before = screen("검색창"),
            after = screen("비밀스러운검색어")
        ).evidence!!

        assertFalse(evidence.contains("비밀스러운검색어"))
    }

    @Test
    fun `서비스가 알린 증거는 Performed 까지 전해진다`() {
        val result = AccessibilityStepExecutor.toResult(
            PlanOutcome.Completed("보내기를 누르고 공유 화면이 닫힌 것을 확인함"),
            A11yType.SEND_MESSAGE
        )
        assertEquals(ExecutionResult.Performed("보내기를 누르고 공유 화면이 닫힌 것을 확인함"), result)
        assertEquals(
            ExecutionResult.Performed(),
            AccessibilityStepExecutor.toResult(PlanOutcome.Completed(), A11yType.TAP)
        )
    }
}
