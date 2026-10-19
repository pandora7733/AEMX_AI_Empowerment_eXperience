package com.sihoo.aemx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TtsSilenceTest {

    @Before
    fun reset() {
        // 앞 테스트의 상태가 남지 않도록 아주 오래전에 끝난 것으로 돌려 놓는다.
        TtsSilence.end(now = 0L)
    }

    @Test
    fun `말하는 동안과 끝난 직후 잠깐은 막는다`() {
        TtsSilence.begin(now = 10_000L)
        assertTrue(TtsSilence.isMuted(now = 12_000L))

        TtsSilence.end(now = 13_000L)
        assertTrue(TtsSilence.isMuted(now = 13_300L))
        assertFalse(TtsSilence.isMuted(now = 13_700L))
    }

    @Test
    fun `끝났다는 신호가 안 와도 영원히 막지는 않는다`() {
        TtsSilence.begin(now = 20_000L)
        assertTrue(TtsSilence.isMuted(now = 30_000L))
        assertFalse(TtsSilence.isMuted(now = 36_000L))
    }
}
