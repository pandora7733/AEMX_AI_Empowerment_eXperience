package com.sihoo.aemx.pipeline.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 호출어 판정: 허용하는 변형과, 오탐이 나면 안 되는 다른 문장. */
class WakeWordMatcherTest {

    @Test fun `자주 적히는 호출어 변형을 받아들인다`() {
        val variants = listOf(
            "하이 에이맥스", "하이에이맥스", "하이 아이맥스", "하이 에이 맥스", "하이 에이멕스",
            "하이 에이맥", "아이맥스", "에이맥스", "하이 애이맥스", "하이 예이맥스",
            "하이 에이믹스", "하이 에이맥쓰", "하이 ai max", "하이 i max", "하이 aimax",
        )
        for (text in variants) assertNotNull("호출어여야 한다: $text", WakeWordMatcher.match(text))
    }

    @Test fun `실기기 로그에서 본 짧은 변형을 받아들인다`() {
        // "에이" 가 빠지거나 "맥" 이 "넥", "미트" 로 적힌 것.
        val variants = listOf("엠엑스", "하이 엠엑스", "하이넥스", "하이미트", "할컴스트", "맥을라고")
        for (text in variants) assertNotNull("호출어여야 한다: $text", WakeWordMatcher.match(text))
    }

    @Test fun `실기기에서 실제로 수락됐던 중간 결과를 모두 받아들인다`() {
        // 2026-11-05 bg3_1105 로그에서 호출로 받아 캡슐이 떴던 글. 하나라도 거절되면 안 된다.
        val accepted = listOf("아이맥스", "IMAX", "하이 MX", "하이 MX.", "아 MX?", "하이맥스", "하긴 A맥스?")
        for (text in accepted) assertNotNull("호출어여야 한다: $text", WakeWordMatcher.match(text))
    }

    @Test fun `짧은 변형 뒤의 명령을 떼어 준다`() {
        assertEquals("유튜브 틀어줘", WakeWordMatcher.match("하이넥스 유튜브 틀어줘"))
        assertEquals("유튜브 틀어줘", WakeWordMatcher.match("엠엑스 유튜브 틀어줘"))
    }

    @Test fun `한 글자 차이인 긴 이름도 받아들인다`() {
        // 에이맥스와 한 글자만 다르다. 긴 이름에만 적용한다.
        assertNotNull(WakeWordMatcher.match("하이 에어맥스"))
    }

    @Test fun `호출어 뒤의 명령을 떼어 준다`() {
        assertEquals("유튜브 틀어줘", WakeWordMatcher.match("하이 에이맥스 유튜브 틀어줘"))
        assertEquals("", WakeWordMatcher.match("하이 에이맥스"))
    }

    @Test fun `호출어가 아닌 문장은 오탐하지 않는다`() {
        val others = listOf(
            "오늘 날씨 좋다",
            "어제 하이 에어맥스 얘기했잖아",
            "유튜브에서 에이맥스 검색해줘",
            "배재준한테 안녕 보내줘",
            "에어컨 켜줘",
            "에이전트 켜줘",
            "에이즈 뉴스",
            "맥스 커피 사줘",
            "아 맥스 커피",
            "저번에이름뭐", "다음에노트북", "완전웃겼", "그러니까", "주는데악착오세요", "됐어",
            "말하잖아", "올리는게아니고혹시모르니까", "이미떠나서", "아아", "카카오", "아그까",
            "아디미오", "기미노라같더라아이고", "되게기저귀를찾아왔다", "아니왜케더워이시기때문인가",
            "발암물어라", "씁쓸하",
            // 호출어 변형이 대화 중간에 끼어도 호출로 보지 않는다.
            "유튜브에서 엠엑스 검색해줘", "유튜브에서 하이넥스 검색해줘",
            "어제 하이넥스트 얘기했잖아", "어제 하이미트 먹었어", "어제 하이 엠엑스 봤어",
            "",
        )
        for (text in others) assertNull("호출어가 아니어야 한다: [$text]", WakeWordMatcher.match(text))
    }

    @Test fun `호출어 판정 실패 로그에는 앞의 여섯 글자 이하만 남는다`() {
        val head = WakeWordMatcher.debugHead("오늘 날씨가 아주 좋아서 산책을 나가고 싶다")
        assertTrue("짧은 구간만 남아야 한다: $head", head.length <= 6)
        assertTrue(head.startsWith("오늘"))
    }

    @Test fun `편집 거리는 글자 하나 차이를 1로 센다`() {
        assertEquals(0, WakeWordMatcher.editDistance("에이맥스", "에이맥스"))
        assertEquals(1, WakeWordMatcher.editDistance("에어맥스", "에이맥스"))
        assertEquals(2, WakeWordMatcher.editDistance("에이즈", "에이맥스"))
    }
}
