package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StepLabelFormatterTest {

    @Test
    fun `자주 쓰는 단계를 사람이 읽는 말로 바꾼다`() {
        assertEquals("유튜브 열기", StepLabelFormatter.label(ActionType.OPEN_APP, "유튜브", "유튜브"))
        assertEquals("'고양이' 검색", StepLabelFormatter.label(ActionType.SEARCH, "고양이", "유튜브"))
        assertEquals("'로그인' 누르기", StepLabelFormatter.label(ActionType.TAP, "로그인", "알라딘"))
        assertEquals("배재준에게 카톡 보내기", StepLabelFormatter.label(ActionType.SEND_MESSAGE, "배재준", "카카오톡"))
        assertEquals("배재준에게 문자 보내기", StepLabelFormatter.label(ActionType.SEND_MESSAGE, "배재준", "메시지"))
    }

    @Test
    fun `끝난 뒤 문장은 받침에 맞게 조사를 붙인다`() {
        assertEquals("유튜브를 열었어요", StepLabelFormatter.doneText(ActionType.OPEN_APP, "유튜브", "유튜브"))
        assertEquals("카카오톡을 열었어요", StepLabelFormatter.doneText(ActionType.OPEN_APP, "카카오톡", "카카오톡"))
        assertEquals("배재준에게 카톡을 보냈어요", StepLabelFormatter.doneText(ActionType.SEND_MESSAGE, "배재준", "카카오톡"))
        assertEquals("배재준에게 문자를 보냈어요", StepLabelFormatter.doneText(ActionType.SEND_MESSAGE, "배재준", "메시지"))
        assertEquals("'고양이' 검색을 마쳤어요", StepLabelFormatter.doneText(ActionType.SEARCH, "고양이", "유튜브"))
    }

    @Test
    fun `입력한 글자와 보낼 내용은 받지도 않아서 보이지 않는다`() {
        // 시그니처에 value 가 없다. 입력 단계는 글자 없이 일반 문구만 낸다.
        assertEquals("글자 입력하기", StepLabelFormatter.label(ActionType.INPUT_TEXT, "검색창", "유튜브"))
        assertEquals("글자를 입력했어요", StepLabelFormatter.doneText(ActionType.INPUT_TEXT, "검색창", "유튜브"))
    }

    @Test
    fun `target 이 비면 일반 문구로 바꾼다`() {
        assertEquals("앱 열기", StepLabelFormatter.label(ActionType.OPEN_APP, null, ""))
        assertEquals("버튼 누르기", StepLabelFormatter.label(ActionType.TAP, "  ", "유튜브"))
        assertEquals("받는 사람에게 메시지 보내기", StepLabelFormatter.label(ActionType.SEND_MESSAGE, null, "?"))
    }

    @Test
    fun `긴 target 은 자르고 줄바꿈은 없앤다`() {
        val label = StepLabelFormatter.label(ActionType.SEARCH, "아주 아주 아주 아주\n아주 긴 검색어입니다", "유튜브")
        assertFalse(label.contains('\n'))
        assertEquals("'아주 아주 아주 아주 아주 긴…' 검색", label)
    }

    @Test
    fun `스크롤은 방향만 말한다`() {
        assertEquals("위로 스크롤하기", StepLabelFormatter.label(ActionType.SCROLL, "up", "유튜브"))
        assertEquals("아래로 스크롤하기", StepLabelFormatter.label(ActionType.SCROLL, "down", "유튜브"))
    }
}
