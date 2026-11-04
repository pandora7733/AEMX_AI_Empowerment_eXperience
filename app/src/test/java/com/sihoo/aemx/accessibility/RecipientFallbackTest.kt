package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 받는 사람 후보: 정확 일치, 뒷부분 일치, 자모 편집거리, 여럿, 없음, 두 글자와 세 글자 이름, 선택 검증. */
class RecipientFallbackTest {

    private fun candidates(spoken: String, contacts: List<String>): List<String> {
        val pick = RecipientFallback.pick(spoken, contacts)
        assertTrue("후보가 있어야 한다: $pick", pick is RecipientFallback.Pick.Candidates)
        return (pick as RecipientFallback.Pick.Candidates).names
    }

    private fun assertNoCandidate(spoken: String, contacts: List<String>) {
        val pick = RecipientFallback.pick(spoken, contacts)
        assertTrue("후보가 없어야 한다: $pick", pick is RecipientFallback.Pick.None)
    }

    @Test fun `말한 이름이 연락처에 정확히 있으면 후보를 쓰지 않는다`() {
        assertEquals(RecipientFallback.Pick.Exact, RecipientFallback.pick("배재준", listOf("배재준", "김철수")))
    }

    @Test fun `띄어 말해도 정확 일치로 본다`() {
        assertEquals(RecipientFallback.Pick.Exact, RecipientFallback.pick("배 재준", listOf("배재준")))
    }

    @Test fun `성 뺀 이름이 연락처 뒷부분과 같으면 후보로 준다`() {
        // "정우" 는 연락처 "김정우" 의 뒷부분이다. 두 글자 이상이라 뒷부분 일치를 본다.
        assertEquals(listOf("김정우"), candidates("정우", listOf("김정우", "김철수")))
    }

    @Test fun `뒷부분 일치가 여럿이면 모두 후보로 준다`() {
        assertEquals(listOf("김정우", "이정우"), candidates("정우", listOf("이정우", "김정우", "김철수")))
    }

    @Test fun `뒷부분 일치는 두 글자 이상 말한 이름에만 쓴다`() {
        assertNoCandidate("우", listOf("김정우"))
    }

    @Test fun `뒷부분 일치 후보가 자모 후보보다 앞에 온다`() {
        // 김배정우 는 뒷부분 일치(배정우), 배정위 는 자모 한 개 차이(같은 음절 수). 뒷부분 일치가 먼저다.
        val names = candidates("배정우", listOf("김배정우", "배정위", "배정"))
        assertEquals("김배정우", names.first())
    }

    @Test fun `자모가 한 자모 다른 이름이 한 명이면 그 이름을 후보로 준다`() {
        // 배제준 -> 배재준 은 ㅔ/ㅐ 한 자모. 대재준 은 ㅂ/ㄷ 한 자모.
        assertEquals(listOf("배재준"), candidates("배제준", listOf("배재준", "김철수")))
        assertEquals(listOf("배재준"), candidates("대재준", listOf("배재준", "김철수")))
    }

    @Test fun `가까운 이름이 둘이면 둘 다 후보로 준다(거절하지 않고 사용자가 고른다)`() {
        val names = candidates("배재준", listOf("배제준", "대재준"))
        assertEquals(setOf("배제준", "대재준"), names.toSet())
    }

    @Test fun `후보는 최대 네 명까지만 준다`() {
        val names = candidates("정우", listOf("가정우", "나정우", "다정우", "라정우", "마정우"))
        assertEquals(RecipientFallback.MAX_CANDIDATES, names.size)
    }

    @Test fun `너무 먼 이름은 후보로 쓰지 않는다`() {
        assertNoCandidate("배제준", listOf("김철수"))
        assertNoCandidate("배제준", emptyList())
    }

    @Test fun `세 글자 이름은 두 자모 차이를 후보로 주지 않는다`() {
        // 김철수 와 김철호 는 두 자모 차이다. 세 글자라 허용치 1 을 넘는다.
        assertNoCandidate("김철수", listOf("김철호"))
    }

    @Test fun `두 글자 이름은 한 자모 차이만 후보로 준다`() {
        assertEquals(listOf("은치"), candidates("은지", listOf("은치")))
        assertNoCandidate("나정", listOf("나경"))
    }

    @Test fun `네 글자 이상 이름은 두 자모까지 후보로 준다`() {
        assertEquals(listOf("남궁민주"), candidates("남궁민수", listOf("남궁민주")))
    }

    @Test fun `글자 수가 다른 이름은 자모 후보가 되지 않는다`() {
        assertNoCandidate("배재준", listOf("배재준우"))
    }

    @Test fun `한글이 아닌 연락처 이름은 후보가 되지 않는다`() {
        assertNoCandidate("배제준", listOf("21105배재준"))
    }

    @Test fun `한글이 아닌 말이나 한 글자 이름은 후보가 없다`() {
        assertNoCandidate("MAX", listOf("MAXX"))
        assertNoCandidate("철", listOf("찰"))
    }

    @Test fun `선택은 후보 목록에 있는 이름일 때만 받는다`() {
        val allowed = listOf("김정우", "정우")
        assertEquals("김정우", RecipientFallback.accepted(allowed, "김정우"))
        assertEquals("정우", RecipientFallback.accepted(allowed, "정우"))
        assertNull("목록에 없는 이름은 거절한다", RecipientFallback.accepted(allowed, "이정우"))
        assertNull("선택이 없으면 보내지 않는다", RecipientFallback.accepted(allowed, null))
    }

    @Test fun `자모 편집거리는 음절 안의 자모를 따로 센다`() {
        assertEquals(0, RecipientFallback.jamoDistance("배재준", "배재준"))
        assertEquals(1, RecipientFallback.jamoDistance("배제준", "배재준"))
        assertEquals(2, RecipientFallback.jamoDistance("김철수", "김철호"))
    }
}
