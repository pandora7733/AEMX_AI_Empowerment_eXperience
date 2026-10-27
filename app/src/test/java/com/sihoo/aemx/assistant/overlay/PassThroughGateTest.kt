package com.sihoo.aemx.assistant.overlay

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** 터치 통과 문과 안전 타이머. 로봇일렉트릭의 멈춘 루퍼가 가짜 시계 역할을 한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PassThroughGateTest {

    private val applied = mutableListOf<Boolean>()
    private val gate = PassThroughGate(Handler(Looper.getMainLooper()), { applied += it })

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `제스처를 보내기 전에 통과로 바꾸고 끝나면 되돌린다`() {
        gate.begin()
        assertTrue(gate.passingThrough)
        assertEquals(listOf(true), applied)

        gate.end()
        assertFalse(gate.passingThrough)
        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `끝 콜백이 안 와도 안전 타이머가 1초 뒤 되돌린다`() {
        gate.begin()
        advance(999)
        assertTrue("1초가 되기 전에는 통과를 유지한다", gate.passingThrough)

        advance(2)
        assertFalse(gate.passingThrough)
        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `제때 끝나면 안전 타이머가 다시 되돌리지 않는다`() {
        gate.begin()
        gate.end()
        advance(5_000)

        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `겹친 제스처는 모두 끝나야 되돌린다`() {
        gate.begin()
        gate.begin()
        assertEquals("두 번째 begin 은 다시 적용하지 않는다", listOf(true), applied)

        gate.end()
        assertTrue(gate.passingThrough)
        gate.end()
        assertFalse(gate.passingThrough)
        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `새 제스처가 오면 안전 타이머를 다시 잰다`() {
        gate.begin()
        advance(800)
        gate.begin()
        advance(800)
        assertTrue("두 번째 begin 으로 타이머가 다시 시작됐다", gate.passingThrough)

        advance(300)
        assertFalse(gate.passingThrough)
    }

    @Test
    fun `reset 은 대기 중인 타이머를 없애고 통과를 푼다`() {
        gate.begin()
        gate.reset()
        assertFalse(gate.passingThrough)
        advance(5_000)
        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `쓰지 않은 end 와 reset 은 아무것도 하지 않는다`() {
        gate.end()
        gate.reset()
        assertEquals(emptyList<Boolean>(), applied)
    }
}
