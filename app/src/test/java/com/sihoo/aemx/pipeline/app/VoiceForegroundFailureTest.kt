package com.sihoo.aemx.pipeline.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 마이크 포그라운드 시작 실패를 백그라운드 제한으로 가르는 규칙(순수). */
class VoiceForegroundFailureTest {

    @Test fun `백그라운드 시작 제한으로 나는 SecurityException 은 제한으로 본다`() {
        assertTrue(VoiceAutoStart.isBackgroundStartBlock(SecurityException("Starting FGS with type microphone")))
    }

    @Test fun `그 밖의 실행 예외는 제한으로 보지 않는다`() {
        assertFalse(VoiceAutoStart.isBackgroundStartBlock(IllegalStateException("not allowed")))
        assertFalse(VoiceAutoStart.isBackgroundStartBlock(RuntimeException("boom")))
    }
}
