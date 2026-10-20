package com.sihoo.aemx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechFocusTrackerTest {

    @Test
    fun `처음 말할 때 포커스를 요청하고 끝나면 돌려준다`() {
        val tracker = SpeechFocusTracker()
        assertTrue(tracker.begin("a"))
        assertTrue(tracker.finish("a"))
    }

    @Test
    fun `앞 문장을 끊고 이어 말해도 포커스를 다시 요청하지 않는다`() {
        val tracker = SpeechFocusTracker()
        assertTrue(tracker.begin("a"))
        assertFalse(tracker.begin("b"))
    }

    @Test
    fun `끊긴 앞 문장의 늦은 콜백은 새 문장의 포커스를 돌려주지 않는다`() {
        val tracker = SpeechFocusTracker()
        tracker.begin("a")
        tracker.begin("b")
        assertFalse(tracker.finish("a"))
        assertTrue(tracker.finish("b"))
    }

    @Test
    fun `같은 발화의 끝 콜백이 두 번 와도 돌려주는 건 한 번이다`() {
        val tracker = SpeechFocusTracker()
        tracker.begin("a")
        assertTrue(tracker.finish("a"))
        assertFalse(tracker.finish("a"))
    }

    @Test
    fun `끝난 뒤 다시 말하면 포커스를 다시 요청한다`() {
        val tracker = SpeechFocusTracker()
        tracker.begin("a")
        tracker.finish("a")
        assertTrue(tracker.begin("b"))
    }
}
