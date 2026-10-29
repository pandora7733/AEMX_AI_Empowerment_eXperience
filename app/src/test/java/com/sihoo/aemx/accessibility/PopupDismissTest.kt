package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PopupDismissTest {

    private fun text(label: String, actionable: Boolean = true) =
        PopupDismiss.Candidate(label = label, description = null, actionable = actionable)

    private fun desc(description: String, actionable: Boolean = true) =
        PopupDismiss.Candidate(label = null, description = description, actionable = actionable)

    // ------------------------------------------------------------ 닫기로 고르는 것

    @Test fun `닫기 버튼을 고른다`() {
        assertEquals(1, PopupDismiss.choose(listOf(text("결과 목록"), text("닫기"))))
    }

    @Test fun `오늘 하루 보지 않기는 공백을 무시하고 고른다`() {
        assertEquals(0, PopupDismiss.choose(listOf(text("오늘하루 보지 않기"))))
        assertEquals(0, PopupDismiss.choose(listOf(text("오늘 그만 보기"))))
        assertEquals(0, PopupDismiss.choose(listOf(text("다시 보지 않기"))))
    }

    @Test fun `나중에와 건너뛰기도 닫기로 본다`() {
        assertEquals(0, PopupDismiss.choose(listOf(text("나중에"))))
        assertEquals(0, PopupDismiss.choose(listOf(text("건너뛰기"))))
    }

    @Test fun `content-desc 의 닫기와 X 도 고른다`() {
        assertEquals(0, PopupDismiss.choose(listOf(desc("닫기"))))
        assertEquals(0, PopupDismiss.choose(listOf(desc("닫기 버튼"))))
        assertEquals(0, PopupDismiss.choose(listOf(desc("Close"))))
        assertEquals(0, PopupDismiss.choose(listOf(desc("X"))))
        assertEquals(0, PopupDismiss.choose(listOf(text("x"))))
        assertEquals(0, PopupDismiss.choose(listOf(text("×"))))
    }

    @Test fun `여럿이면 목록 앞쪽 기준의 우선순위로 고른다`() {
        // "닫기"가 "나중에"보다 우선이라 뒤에 있어도 닫기를 고른다.
        assertEquals(2, PopupDismiss.choose(listOf(text("나중에"), text("건너뛰기"), text("닫기"))))
    }

    // ------------------------------------------------------------ 누르지 않는 것

    @Test fun `결제 구매 동의 확인 설치 업데이트 로그인 허용은 닫기처럼 보여도 누르지 않는다`() {
        val blocked = listOf("결제", "구매하기", "동의", "확인", "설치", "업데이트", "로그인", "허용", "승인", "가입")
        blocked.forEach { word ->
            assertNull("$word 는 누르면 안 된다", PopupDismiss.choose(listOf(text(word))))
        }
    }

    @Test fun `닫기라는 글자가 있어도 금지어가 섞이면 누르지 않는다`() {
        assertNull(PopupDismiss.choose(listOf(text("결제 닫기"))))
        assertNull(PopupDismiss.choose(listOf(desc("구매 닫기 버튼"))))
        assertNull(PopupDismiss.choose(listOf(text("동의하고 닫기"))))
    }

    @Test fun `금지어 버튼이 있으면 닫기 버튼이 있어도 금지어 쪽은 건너뛰고 닫기를 고른다`() {
        assertEquals(1, PopupDismiss.choose(listOf(text("허용"), text("닫기"))))
    }

    @Test fun `문장 안에 닫기가 들어 있어도 정확히 맞지 않으면 누르지 않는다`() {
        assertNull(PopupDismiss.choose(listOf(text("나중에 결제하기"))))
        assertNull(PopupDismiss.choose(listOf(text("닫기 전에 저장하세요"))))
    }

    @Test fun `누를 수 없는 닫기는 고르지 않는다`() {
        assertNull(PopupDismiss.choose(listOf(text("닫기", actionable = false))))
    }

    @Test fun `후보가 없으면 null`() {
        assertNull(PopupDismiss.choose(emptyList()))
        assertNull(PopupDismiss.choose(listOf(text("검색창"), text("결과"))))
    }
}
