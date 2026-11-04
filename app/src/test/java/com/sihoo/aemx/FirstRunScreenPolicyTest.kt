package com.sihoo.aemx

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstRunScreenPolicyTest {

    private fun choose(
        reviewing: Boolean = false,
        connected: Boolean = true,
        allFinished: Boolean = false,
        homeReached: Boolean = false,
        walkthrough: Boolean = false,
    ) = FirstRunScreenPolicy.choose(reviewing, connected, allFinished, homeReached, walkthrough)

    @Test
    fun `첫 실행 안내 중에는 모든 단계가 끝나도 단계 화면이다`() {
        assertEquals(FirstRunScreen.Steps, choose(connected = true, allFinished = true, walkthrough = true))
    }

    @Test
    fun `접근성이 연결되지 않으면 모든 단계가 끝나 있어도 단계 화면이다`() {
        assertEquals(FirstRunScreen.Steps, choose(connected = false, allFinished = true, homeReached = true))
    }

    @Test
    fun `연결되고 모든 단계가 끝나면 홈이다`() {
        assertEquals(FirstRunScreen.Home, choose(connected = true, allFinished = true))
    }

    @Test
    fun `아직 끝나지 않은 단계가 있으면 단계 화면이다`() {
        assertEquals(FirstRunScreen.Steps, choose(connected = true, allFinished = false))
    }

    @Test
    fun `한 번 홈에 들어갔으면 연결된 동안 단계로 돌아가지 않는다`() {
        assertEquals(FirstRunScreen.Home, choose(connected = true, allFinished = false, homeReached = true))
    }

    @Test
    fun `설정 다시 보기 중에는 연결과 상관없이 단계 화면이다`() {
        assertEquals(FirstRunScreen.Steps, choose(reviewing = true, connected = true, allFinished = true, homeReached = true))
    }
}
