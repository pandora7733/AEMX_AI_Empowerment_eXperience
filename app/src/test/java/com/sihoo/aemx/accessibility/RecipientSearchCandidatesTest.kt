package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 카톡 검색 결과 이름에서 받는 사람 후보 고르기: 같은 이름, 뒷부분, 포함 순. 최대 4명. */
class RecipientSearchCandidatesTest {

    @Test fun `검색 결과에 정우가 없으면 후보가 없다`() {
        assertTrue(RecipientFallback.fromSearch("정우", listOf("김철수", "박영희")).isEmpty())
    }

    @Test fun `성 뺀 이름은 뒷부분 일치로 후보가 된다`() {
        assertEquals(listOf("김정우"), RecipientFallback.fromSearch("정우", listOf("김정우", "김철수")))
    }

    @Test fun `같은 이름이 먼저, 뒷부분 일치가 다음, 포함이 마지막이다`() {
        val names = listOf("정우성", "이정우", "정우", "김정우")
        assertEquals(listOf("정우", "김정우", "이정우", "정우성"), RecipientFallback.fromSearch("정우", names))
    }

    @Test fun `한 글자 말은 포함 일치를 쓰지 않는다`() {
        assertEquals(listOf("우"), RecipientFallback.fromSearch("우", listOf("우", "김우진")))
    }

    @Test fun `후보는 최대 네 명이다`() {
        val names = listOf("가정우", "나정우", "다정우", "라정우", "마정우")
        assertEquals(RecipientFallback.MAX_CANDIDATES, RecipientFallback.fromSearch("정우", names).size)
    }

    @Test fun `띄어 쓴 이름과 중복은 한 명으로 본다`() {
        assertEquals(listOf("김정우"), RecipientFallback.fromSearch("정 우", listOf("김 정우", "김정우")))
    }

    @Test fun `빈 말이면 후보가 없다`() {
        assertTrue(RecipientFallback.fromSearch("  ", listOf("김정우")).isEmpty())
    }
}
