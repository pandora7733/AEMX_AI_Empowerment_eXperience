package com.sihoo.aemx

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstRunCursorTest {

    /** 완료된 단계들 = 전체에서 [off] 를 뺀 것. */
    private fun allExcept(vararg off: FirstRunStep): Set<FirstRunStep> =
        FirstRunStep.entries.filter { it !in off }.toSet()

    @Test
    fun `마이크만 꺼져 있으면 마이크 단계부터 보인다`() {
        assertEquals(FirstRunStep.MIC.ordinal, FirstRunCursor.firstPending(allExcept(FirstRunStep.MIC)))
    }

    @Test
    fun `알림만 꺼져 있으면 알림 단계부터 보인다`() {
        assertEquals(FirstRunStep.NOTIFY.ordinal, FirstRunCursor.firstPending(allExcept(FirstRunStep.NOTIFY)))
    }

    @Test
    fun `전부 꺼져 있으면 마이크 단계부터 보인다`() {
        assertEquals(FirstRunStep.MIC.ordinal, FirstRunCursor.firstPending(emptySet()))
    }

    @Test
    fun `전부 켜져 있으면 쓰지 않는 값 0 이다`() {
        assertEquals(0, FirstRunCursor.firstPending(FirstRunStep.entries.toSet()))
    }

    @Test
    fun `접근성만 꺼져 있으면 접근성 단계가 보인다`() {
        assertEquals(FirstRunStep.ACCESS.ordinal, FirstRunCursor.firstPending(allExcept(FirstRunStep.ACCESS)))
    }

    @Test
    fun `접근성과 마이크가 모두 꺼져 있으면 앞선 마이크가 먼저다`() {
        assertEquals(FirstRunStep.MIC.ordinal, FirstRunCursor.firstPending(allExcept(FirstRunStep.ACCESS, FirstRunStep.MIC)))
    }

    @Test
    fun `순서는 마이크 알림 접근성 캡슐 연락처 모델이다`() {
        assertEquals(
            listOf("MIC", "NOTIFY", "ACCESS", "BACKGROUND", "OVERLAY", "CONTACTS", "MODEL"),
            FirstRunStep.entries.map { it.name },
        )
    }
}
