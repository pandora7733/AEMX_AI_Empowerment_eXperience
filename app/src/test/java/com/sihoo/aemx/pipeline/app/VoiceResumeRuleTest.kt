package com.sihoo.aemx.pipeline.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서비스가 떠 있는데 마이크가 꺼졌을 때 다시 켜는 규칙(순수). */
class VoiceResumeRuleTest {

    @Test fun `떠 있고 마이크가 꺼져 있고 여는 중이 아니면 다시 켠다`() {
        assertTrue(VoiceAutoStart.shouldResumeIdle(micGranted = true, userStopped = false, running = true, micIdle = true))
    }

    @Test fun `사용자가 끈 상태면 다시 켜지 않는다`() {
        assertFalse(VoiceAutoStart.shouldResumeIdle(micGranted = true, userStopped = true, running = true, micIdle = true))
    }

    @Test fun `이미 듣고 있거나 여는 중이면 다시 켜지 않는다`() {
        assertFalse(VoiceAutoStart.shouldResumeIdle(micGranted = true, userStopped = false, running = true, micIdle = false))
    }

    @Test fun `서비스가 떠 있지 않으면 이 규칙으로 켜지 않는다(autoStart 가 맡는다)`() {
        assertFalse(VoiceAutoStart.shouldResumeIdle(micGranted = true, userStopped = false, running = false, micIdle = true))
    }

    @Test fun `마이크 권한이 없으면 다시 켜지 않는다`() {
        assertFalse(VoiceAutoStart.shouldResumeIdle(micGranted = false, userStopped = false, running = true, micIdle = true))
    }
}
