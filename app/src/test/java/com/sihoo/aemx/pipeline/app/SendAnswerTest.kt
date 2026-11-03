package com.sihoo.aemx.pipeline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendAnswerTest {

    @Test
    fun `짧은 긍정은 보내기다`() {
        assertEquals(true, SendAnswer.parse("응"))
        assertEquals(true, SendAnswer.parse("네, 보내 주세요"))
        assertEquals(true, SendAnswer.parse("맞아"))
    }

    @Test
    fun `취소 말은 어디에 섞여도 취소다`() {
        assertEquals(false, SendAnswer.parse("아니요"))
        assertEquals(false, SendAnswer.parse("취소해"))
        assertEquals(false, SendAnswer.parse("보내지 마"))
        assertEquals(false, SendAnswer.parse("그만"))
    }

    @Test
    fun `길게 떠든 말에 든 긍정 글자는 보내기로 치지 않는다`() {
        assertNull(SendAnswer.parse("배재준한테 보내는 건 나중에 생각해 볼 테니 네 기다려"))
    }

    @Test
    fun `답이 아닌 말은 null 이다`() {
        assertNull(SendAnswer.parse(""))
        assertNull(SendAnswer.parse("음악 틀어줘"))
    }
}
