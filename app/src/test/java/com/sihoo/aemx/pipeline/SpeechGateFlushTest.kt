package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.stt.shouldFlushOnGateClose
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateFlushTest {

    @Test
    fun `문이 열려 있다 닫히며 중간 결과가 남아 있으면 문장을 끝낸다`() {
        assertTrue(shouldFlushOnGateClose(wasOpen = true, pendingText = "IMAX? 알려줘."))
    }

    @Test
    fun `이미 endpoint 로 끝나 남은 글자가 없으면 아무것도 하지 않는다`() {
        assertFalse(shouldFlushOnGateClose(wasOpen = true, pendingText = ""))
        assertFalse(shouldFlushOnGateClose(wasOpen = true, pendingText = "  "))
    }

    @Test
    fun `문이 이미 닫혀 있던 프레임에서는 다시 끝내지 않는다`() {
        assertFalse(shouldFlushOnGateClose(wasOpen = false, pendingText = "IMAX?"))
    }
}
