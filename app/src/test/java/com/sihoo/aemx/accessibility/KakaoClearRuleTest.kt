package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KakaoClearRuleTest {

    @Test
    fun `입력란이 비고 지우기 버튼도 없으면 지우기가 끝났다`() {
        assertTrue(KakaoShareRules.clearDone(inputEmpty = true, clearButtonVisible = false))
    }

    @Test
    fun `지우기 버튼이 남아 있으면 아직 끝난 것이 아니다`() {
        assertFalse(KakaoShareRules.clearDone(inputEmpty = true, clearButtonVisible = true))
        assertFalse(KakaoShareRules.clearDone(inputEmpty = false, clearButtonVisible = true))
    }

    @Test
    fun `입력란에 글자가 남아 있으면 버튼이 없어도 끝난 것이 아니다`() {
        assertFalse(KakaoShareRules.clearDone(inputEmpty = false, clearButtonVisible = false))
    }

    @Test
    fun `찾지 못했을 때의 안내 문장`() {
        assertEquals("서우진 님을 카카오톡에서 찾지 못했어요", KakaoShareRules.notFoundMessage("서우진"))
    }
}
