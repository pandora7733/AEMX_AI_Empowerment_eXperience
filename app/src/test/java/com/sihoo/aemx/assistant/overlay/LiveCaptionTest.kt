package com.sihoo.aemx.assistant.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 듣는 중 실시간 글자의 순수 규칙: 줄이기, 갱신 간격, 큰 변동 유예, 빈 값 유지. */
class LiveCaptionTest {

    @Test fun `줄바꿈과 여러 칸 공백은 한 칸으로 줄인다`() {
        assertEquals("날씨 알려줘", LiveCaption.normalize("  날씨\n  알려줘 "))
    }

    @Test fun `짧은 말은 그대로 보이고 긴 말은 앞을 줄이고 끝 글자를 보인다`() {
        assertEquals("날씨 알려줘", LiveCaption.display("날씨 알려줘"))

        val long = "가".repeat(10) + "나".repeat(LiveCaption.WIDTH)
        val shown = LiveCaption.display(long)
        assertTrue(shown.startsWith("…"))
        assertEquals(LiveCaption.WIDTH + 1, shown.length)
        assertTrue(shown.endsWith("나".repeat(LiveCaption.WIDTH)))
    }

    @Test fun `처음 들어온 말은 바로 반영한다`() {
        val throttle = LiveCaption.Throttle()

        assertEquals("날씨", throttle.offer("날씨", now = 1_000))
        assertEquals("날씨", throttle.shown)
    }

    @Test fun `갱신 간격 안의 값은 보류했다가 마지막 것만 반영한다`() {
        val throttle = LiveCaption.Throttle()
        throttle.offer("날씨", now = 1_000)

        assertNull(throttle.offer("날씨 알", now = 1_050))
        assertNull(throttle.offer("날씨 알려", now = 1_100))
        assertTrue(throttle.waiting)

        // 간격이 지난 뒤 다시 불러 주면 그 시점의 마지막 값이 반영된다.
        assertEquals("날씨 알려줘", throttle.offer("날씨 알려줘", now = 1_250))
        assertFalse(throttle.waiting)
    }

    @Test fun `앞부분이 크게 바뀌면 300ms 동안 그대로인 뒤에 반영한다`() {
        val throttle = LiveCaption.Throttle()
        throttle.offer("유튜브에서 고양이", now = 1_000)

        // 앞부분이 완전히 다른 말로 바뀌었다. 바로는 반영하지 않는다.
        assertNull(throttle.offer("오늘 날씨 어때", now = 1_500))
        assertNull(throttle.offer("오늘 날씨 어때", now = 1_600))
        assertEquals("유튜브에서 고양이", throttle.shown)

        assertEquals("오늘 날씨 어때", throttle.offer("오늘 날씨 어때", now = 1_850))
    }

    @Test fun `큰 변동 중에 값이 또 바뀌면 유예 시간을 다시 센다`() {
        val throttle = LiveCaption.Throttle()
        throttle.offer("유튜브에서 고양이", now = 1_000)

        assertNull(throttle.offer("오늘 날씨", now = 1_500))
        assertNull(throttle.offer("오늘 날씨 어때", now = 1_700))
        // 첫 값이 300ms 머물렀어도 값이 바뀌었으니, 새 값이 300ms 머물 때까지 기다린다.
        assertNull(throttle.offer("오늘 날씨 어때", now = 1_900))
        assertEquals("오늘 날씨 어때", throttle.offer("오늘 날씨 어때", now = 2_000))
    }

    @Test fun `빈 중간 결과는 이전 글자를 유지한다`() {
        val throttle = LiveCaption.Throttle()
        throttle.offer("날씨 알려줘", now = 1_000)

        assertNull(throttle.offer("", now = 1_500))
        assertNull(throttle.offer("   ", now = 1_600))
        assertEquals("날씨 알려줘", throttle.shown)
    }

    @Test fun `작은 변화는 간격만 지나면 바로 반영한다`() {
        val throttle = LiveCaption.Throttle()
        throttle.offer("유튜브에서", now = 1_000)

        assertEquals("유튜브에서 고양이", throttle.offer("유튜브에서 고양이", now = 1_300))
    }

    @Test fun `초기화하면 이전 글자를 잊는다`() {
        val throttle = LiveCaption.Throttle()
        throttle.offer("날씨", now = 1_000)
        throttle.reset()

        assertEquals("", throttle.shown)
        assertEquals("새 말", throttle.offer("새 말", now = 1_010))
    }

    @Test fun `짧은 글자의 변화는 큰 변동으로 보지 않는다`() {
        assertFalse(LiveCaption.isLargeChange("날씨", "날씨 알려"))
        assertTrue(LiveCaption.isLargeChange("유튜브에서 고양이", "오늘 날씨 어때"))
    }
}
