package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `검색 관련 영문 식별자는 검색으로 말하고 따옴표에 넣지 않는다`() {
        assertEquals("검색 누르기", StepLabelFormatter.label(ActionType.TAP, "search button", "교보문고"))
        assertEquals("검색을 누르는 중…", StepLabelFormatter.progressText(ActionType.TAP, "search button", "교보문고"))
        assertEquals("검색을 눌렀어요", StepLabelFormatter.doneText(ActionType.TAP, "search button", "교보문고"))
        assertEquals("검색을 눌렀어요", StepLabelFormatter.doneText(ActionType.TAP, "searchMainKeyword", "교보문고"))
    }

    @Test
    fun `쇼츠 식별자는 쇼츠로, 그 밖의 영문 식별자는 버튼으로 말한다`() {
        assertEquals("쇼츠 누르기", StepLabelFormatter.label(ActionType.TAP, "Shorts", "유튜브"))
        assertEquals("쇼츠를 눌렀어요", StepLabelFormatter.doneText(ActionType.TAP, "Shorts", "유튜브"))
        assertEquals("버튼 누르기", StepLabelFormatter.label(ActionType.TAP, "more_option", "교보문고"))
        assertEquals("버튼을 눌렀어요", StepLabelFormatter.doneText(ActionType.TAP, "more_option", "교보문고"))
    }

    @Test
    fun `영문 입력칸 id 는 검색어 입력으로 말한다`() {
        assertEquals("검색어 입력하기", StepLabelFormatter.label(ActionType.INPUT_TEXT, "edit_text", "쿠팡"))
        assertEquals("검색어 입력 중…", StepLabelFormatter.progressText(ActionType.INPUT_TEXT, "edit_text", "쿠팡"))
        assertEquals("검색어를 입력했어요", StepLabelFormatter.doneText(ActionType.INPUT_TEXT, "edit_text", "쿠팡"))
    }

    @Test
    fun `검색 아이콘이나 버튼 같은 한글 라벨도 검색으로 말하고 검색창은 그대로 따옴표로 보인다`() {
        assertEquals("검색 누르기", StepLabelFormatter.label(ActionType.TAP, "검색 아이콘", "쿠팡"))
        assertEquals("'검색창' 누르기", StepLabelFormatter.label(ActionType.TAP, "검색창", "교보문고"))
        assertEquals("'검색창'을 눌렀어요", StepLabelFormatter.doneText(ActionType.TAP, "검색창", "교보문고"))
    }

    @Test
    fun `영문 식별자가 앱 이름 자리에 오면 대상 앱 이름을 쓴다`() {
        assertEquals("쿠팡 열기", StepLabelFormatter.label(ActionType.OPEN_APP, "edit_text", "쿠팡"))
        assertEquals("쿠팡을 여는 중…", StepLabelFormatter.progressText(ActionType.OPEN_APP, "edit_text", "쿠팡"))
        assertEquals("쿠팡을 열었어요", StepLabelFormatter.doneText(ActionType.OPEN_APP, "edit_text", "쿠팡"))
    }

    @Test
    fun `식별자 판정은 영문과 숫자 기호만 있을 때만 참이다`() {
        assertTrue(StepLabelFormatter.isIdentifier("search button"))
        assertTrue(StepLabelFormatter.isIdentifier("com.coupang.mobile:id/edit_text"))
        assertFalse(StepLabelFormatter.isIdentifier("검색창"))
        assertFalse(StepLabelFormatter.isIdentifier("search 창"))
        assertFalse(StepLabelFormatter.isIdentifier("1234"))
        assertFalse(StepLabelFormatter.isIdentifier(null))
    }
}
