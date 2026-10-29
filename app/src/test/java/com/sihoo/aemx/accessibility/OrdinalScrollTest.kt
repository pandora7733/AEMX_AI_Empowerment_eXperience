package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N번째 항목 세기: 스크롤하며 이어 센다. 같은 항목은 한 번만 센다. */
class OrdinalScrollTest {

    @Test
    fun `스크롤하며 이어 세서 다섯 번째를 찾는다`() {
        val tracker = OrdinalTracker(5)

        assertNull(tracker.pick(listOf("a", "b")))
        tracker.markScrolled(now = 0)
        assertNull(tracker.pick(listOf("c", "d")))
        tracker.markScrolled(now = 1_000)
        // 다섯째 항목 "e" 가 두 번째 스크롤 뒤 화면에 보인다. 그 자리는 화면 맨 위(0)다.
        assertEquals(0, tracker.pick(listOf("e", "f")))
        assertEquals(2, tracker.scrollCount)
    }

    @Test
    fun `다시 보이는 항목은 중복으로 빼고 센다`() {
        val tracker = OrdinalTracker(3)

        assertNull(tracker.pick(listOf("a", "b")))
        tracker.markScrolled(now = 0)
        // "b" 는 두 번 보이지만 한 항목이다. 세 번째는 "c".
        assertEquals(1, tracker.pick(listOf("b", "c")))
    }

    @Test
    fun `스크롤 뒤 처음 보는 항목이 없으면 끝까지 본 것이다`() {
        val tracker = OrdinalTracker(5)

        tracker.pick(listOf("a", "b"))
        tracker.markScrolled(now = 0)
        tracker.pick(listOf("a", "b"))

        assertTrue(tracker.noNewSinceLastScroll())
    }

    @Test
    fun `스크롤 뒤 새 항목이 보이면 끝이 아니다`() {
        val tracker = OrdinalTracker(5)

        tracker.pick(listOf("a", "b"))
        tracker.markScrolled(now = 0)
        tracker.pick(listOf("b", "c"))

        assertFalse(tracker.noNewSinceLastScroll())
    }

    @Test
    fun `스크롤하기 전에는 끝이라고 보지 않는다`() {
        val tracker = OrdinalTracker(5)

        tracker.pick(listOf("a"))

        assertFalse(tracker.noNewSinceLastScroll())
        assertEquals(0, tracker.scrollCount)
    }
}
