package com.sihoo.aemx.assistant

import com.sihoo.aemx.assistant.overlay.MicState
import com.sihoo.aemx.assistant.overlay.QuickMenuRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickMenuRulesTest {

    @Test
    fun `듣고 있고 소리가 들어오면 켜짐이다`() {
        assertEquals(MicState.LISTENING, QuickMenuRules.micState(listening = true, micIdle = false, silenced = false))
    }

    @Test
    fun `듣고 있지만 소리가 0 만 들어오면 막힘이다`() {
        assertEquals(MicState.SILENCED, QuickMenuRules.micState(listening = true, micIdle = false, silenced = true))
    }

    @Test
    fun `마이크를 여는 중이면 켜는 중이다`() {
        assertEquals(MicState.STARTING, QuickMenuRules.micState(listening = false, micIdle = false, silenced = false))
    }

    @Test
    fun `듣지도 열지도 않으면 꺼짐이다`() {
        assertEquals(MicState.OFF, QuickMenuRules.micState(listening = false, micIdle = true, silenced = false))
        // 꺼진 뒤에 남은 무음 표시는 상태를 바꾸지 않는다.
        assertEquals(MicState.OFF, QuickMenuRules.micState(listening = false, micIdle = true, silenced = true))
    }

    @Test
    fun `켜져 있으면 누를 때 끄고 꺼져 있으면 켠다`() {
        assertEquals(true, QuickMenuRules.tapTurnsOff(MicState.LISTENING))
        assertEquals(true, QuickMenuRules.tapTurnsOff(MicState.SILENCED))
        assertEquals(false, QuickMenuRules.tapTurnsOff(MicState.OFF))
    }

    @Test
    fun `켜는 중에는 눌러도 아무것도 하지 않는다`() {
        assertNull(QuickMenuRules.tapTurnsOff(MicState.STARTING))
    }

    @Test
    fun `상태마다 이름과 안내가 다르다`() {
        assertEquals("마이크 켜짐", QuickMenuRules.micLabel(MicState.LISTENING))
        assertEquals("마이크 꺼짐", QuickMenuRules.micLabel(MicState.OFF))
        assertEquals("누르면 잠시 끕니다", QuickMenuRules.micHint(MicState.LISTENING))
        assertEquals("누르면 다시 켭니다", QuickMenuRules.micHint(MicState.OFF))
    }

    @Test
    fun `소리 크기는 0에서 100 사이의 막대 값이 된다`() {
        assertEquals(0, QuickMenuRules.levelPercent(0.0))
        assertEquals(50, QuickMenuRules.levelPercent(0.15))
        assertEquals(100, QuickMenuRules.levelPercent(0.9))
    }
}
