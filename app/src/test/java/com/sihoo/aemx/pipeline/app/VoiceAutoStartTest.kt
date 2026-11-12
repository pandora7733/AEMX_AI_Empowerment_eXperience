package com.sihoo.aemx.pipeline.app

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 자동 시작 판정(순수)과 끈 상태 저장(Robolectric). */
class VoiceAutoStartTest {

    @Test fun `마이크 권한이 있고 끄지 않았고 돌고 있지 않을 때만 자동으로 켠다`() {
        assertTrue(VoiceAutoStart.shouldAutoStart(micGranted = true, userStopped = false, running = false))
    }

    @Test fun `마이크 권한이 없으면 자동으로 켜지 않는다`() {
        assertFalse(VoiceAutoStart.shouldAutoStart(micGranted = false, userStopped = false, running = false))
    }

    @Test fun `사용자가 끈 상태면 부팅 뒤에도 자동으로 켜지 않는다`() {
        assertFalse(VoiceAutoStart.shouldAutoStart(micGranted = true, userStopped = true, running = false))
    }

    @Test fun `이미 돌고 있으면 다시 켜지 않는다`() {
        assertFalse(VoiceAutoStart.shouldAutoStart(micGranted = true, userStopped = false, running = true))
    }

    @Test
    fun `완전히 종료한 상태면 자동으로 켜지 않는다`() {
        assertFalse(VoiceAutoStart.shouldAutoStart(micGranted = true, userStopped = false, running = false, quit = true))
    }

    @Test
    fun `부팅과 업데이트는 완전히 종료한 상태를 지운다`() {
        assertTrue(VoiceAutoStart.clearsQuit("android.intent.action.BOOT_COMPLETED"))
        assertTrue(VoiceAutoStart.clearsQuit("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(VoiceAutoStart.clearsQuit(null))
    }

    @Test fun `무음 마이크는 듣기가 돌고 있을 때만 다시 시작한다`() {
        assertTrue(VoiceAutoStart.shouldRetryMic(silenced = true, running = true))
        assertFalse(VoiceAutoStart.shouldRetryMic(silenced = true, running = false))
        assertFalse(VoiceAutoStart.shouldRetryMic(silenced = false, running = true))
    }

    @Test fun `업데이트 설치 때만 예전 끈 상태를 지운다`() {
        assertTrue(VoiceAutoStart.clearsUserStop("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(VoiceAutoStart.clearsUserStop("android.intent.action.BOOT_COMPLETED"))
        assertFalse(VoiceAutoStart.clearsUserStop(null))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoicePrefsTest {

    @Test fun `끈 상태는 저장되고 다시 켜면 지워진다`() {
        val context: Context = RuntimeEnvironment.getApplication()
        assertFalse(VoicePrefs.userStopped(context))

        VoicePrefs.setUserStopped(context, true)
        assertTrue(VoicePrefs.userStopped(context))

        VoicePrefs.setUserStopped(context, false)
        assertFalse(VoicePrefs.userStopped(context))
    }
}
