package com.sihoo.aemx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingSpeechTest {

    @Test
    fun `보관한 문장은 한 번만 꺼낼 수 있다`() {
        val pending = PendingSpeech()
        pending.hold("작업을 마쳤습니다")
        assertEquals("작업을 마쳤습니다", pending.take())
        assertNull(pending.take())
    }

    @Test
    fun `여러 번 들어와도 마지막 한 문장만 남긴다`() {
        val pending = PendingSpeech()
        pending.hold("다시 시도합니다")
        pending.hold("작업을 마쳤습니다")
        assertEquals("작업을 마쳤습니다", pending.take())
        assertNull(pending.take())
    }

    @Test
    fun `비우면 아무것도 말하지 않는다`() {
        val pending = PendingSpeech()
        pending.hold("작업을 마쳤습니다")
        pending.clear()
        assertNull(pending.take())
    }

    @Test
    fun `보관한 것이 없으면 null 이다`() {
        assertNull(PendingSpeech().take())
    }
}
