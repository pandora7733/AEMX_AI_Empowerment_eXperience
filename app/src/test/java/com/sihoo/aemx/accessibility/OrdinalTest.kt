package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrdinalTest {
    @Test
    fun `value 자리에서는 숫자만 와도 순번이다`() {
        assertEquals(2, Ordinal.find("2"))
        assertEquals(3, Ordinal.find(" 3 "))
        assertEquals(10, Ordinal.find("10"))
    }

    @Test
    fun `번째와 째 꼴을 읽는다`() {
        assertEquals(2, Ordinal.find("두 번째"))
        assertEquals(2, Ordinal.find("두번째"))
        assertEquals(2, Ordinal.find("2번째"))
        assertEquals(2, Ordinal.find("둘째"))
        assertEquals(3, Ordinal.find("셋째"))
        assertEquals(3, Ordinal.find("세 번째 동영상"))
        assertEquals(1, Ordinal.find("첫 번째"))
        assertEquals(5, Ordinal.find("다섯번째"))
        assertEquals(10, Ordinal.find("열 번째"))
    }

    @Test
    fun `발화에서는 번째가 붙은 말만 순번으로 본다`() {
        assertEquals(2, Ordinal.inUtterance("유튜브에서 페이커 검색하고 두번째 영상 틀어줘"))
        assertEquals(3, Ordinal.inUtterance("아이유 노래 3번째 곡 틀어줘"))
        assertEquals(4, Ordinal.inUtterance("넷째 영상 재생해줘"))
        assertEquals(2, Ordinal.inUtterance("2번 영상 틀어줘"))
        // 숫자만 있는 말은 순번이 아니다.
        assertNull(Ordinal.inUtterance("유튜브에서 2024 검색해줘"))
        assertNull(Ordinal.inUtterance("유튜브에서 고양이 영상 틀어줘"))
    }

    @Test
    fun `범위 밖이거나 비었으면 null 이다`() {
        assertNull(Ordinal.find(null))
        assertNull(Ordinal.find(""))
        assertNull(Ordinal.find("0"))
        assertNull(Ordinal.find("99"))
        assertNull(Ordinal.find("두 개"))
    }

    @Test
    fun `N번째는 처음 본 순서대로 세고 스크롤해도 겹친 항목을 다시 세지 않는다`() {
        val tracker = OrdinalTracker(5)
        // 화면 하나에 네 개: 다섯 번째가 아직 없다.
        assertNull(tracker.pick(listOf("a", "b", "c", "d")))
        assertFalse(tracker.scrolled)
        tracker.markScrolled(1_000)
        assertTrue(tracker.settling(1_500))
        assertFalse(tracker.settling(2_000))
        // 스크롤하면 c, d 가 다시 보이고 e 가 새로 나온다. 다섯 번째는 e 다.
        assertEquals(2, tracker.pick(listOf("c", "d", "e", "f")))
    }

    @Test
    fun `N번째가 이미 보이면 바로 그 자리를 준다`() {
        val tracker = OrdinalTracker(2)
        assertEquals(1, tracker.pick(listOf("a", "b", "c")))
        assertFalse(tracker.scrolled)
    }

    @Test
    fun `스크롤하고도 N개를 못 채우면 null 이다`() {
        val tracker = OrdinalTracker(9)
        assertNull(tracker.pick(listOf("a", "b", "c")))
        tracker.markScrolled(1)
        assertNull(tracker.pick(listOf("c", "d")))
        assertTrue(tracker.scrolled)
    }
}
