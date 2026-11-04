package com.sihoo.aemx.pipeline.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 마이크 무음 판정: 0 이 연속으로 제한 프레임 이상 이어질 때만 무음이다. */
class MicHealthTest {

    private val zeros = ShortArray(1_600)
    private val voice = ShortArray(1_600) { if (it % 2 == 0) 900 else -900 }

    @Test fun `0만 세 시간(30프레임) 이어지면 무음으로 보고 그때 한 번만 알린다`() {
        val health = MicHealth(silentFramesLimit = 30)
        var triggers = 0
        repeat(40) { if (health.onFrame(zeros, zeros.size)) triggers++ }

        assertTrue(health.silenced)
        assertEquals(1, triggers)
    }

    @Test fun `제한보다 짧으면 무음이 아니다`() {
        val health = MicHealth(silentFramesLimit = 30)
        repeat(29) { health.onFrame(zeros, zeros.size) }

        assertFalse(health.silenced)
    }

    @Test fun `소리가 한 번이라도 들어오면 무음을 푼다`() {
        val health = MicHealth(silentFramesLimit = 5)
        repeat(6) { health.onFrame(zeros, zeros.size) }
        assertTrue(health.silenced)

        health.onFrame(voice, voice.size)
        assertFalse(health.silenced)
    }

    @Test fun `중간에 소리가 섞이면 연속 셈이 끊긴다`() {
        val health = MicHealth(silentFramesLimit = 5)
        repeat(4) { health.onFrame(zeros, zeros.size) }
        health.onFrame(voice, voice.size)
        repeat(4) { health.onFrame(zeros, zeros.size) }

        assertFalse(health.silenced)
    }

    @Test fun `읽은 것이 없으면 아무것도 세지 않는다`() {
        val health = MicHealth(silentFramesLimit = 1)
        assertFalse(health.onFrame(zeros, 0))
        assertFalse(health.silenced)
    }

    @Test fun `기본 제한은 100ms 프레임 30개, 3초다`() {
        assertEquals(30, MicHealth.SILENT_FRAMES_LIMIT)
    }
}
