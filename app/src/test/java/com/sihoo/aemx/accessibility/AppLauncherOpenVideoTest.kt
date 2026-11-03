package com.sihoo.aemx.accessibility

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 영상 열기: vnd.youtube 를 먼저 쓰고, 받을 앱이 없을 때만 https 주소로 넘긴다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppLauncherOpenVideoTest {

    /** 시작한 인텐트를 모으고, [rejectVnd] 가 참이면 vnd.youtube 인텐트는 받을 앱이 없다고 던진다. */
    private class RecordingContext(base: Context, private val rejectVnd: Boolean) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            if (rejectVnd && intent.data?.scheme == "vnd.youtube") throw ActivityNotFoundException("no app")
            started += intent
        }
    }

    @Test fun `vnd youtube 주소를 먼저 연다`() {
        val context = RecordingContext(RuntimeEnvironment.getApplication(), rejectVnd = false)

        val result = AppLauncher(context).openVideo("HP0a4pkXM3Q")

        assertTrue(result.isSuccess)
        assertEquals(1, context.started.size)
        val intent = context.started.single()
        assertEquals("vnd.youtube:HP0a4pkXM3Q", intent.dataString)
        assertEquals(AppLauncher.YOUTUBE_PACKAGE, intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `vnd 주소를 받을 앱이 없으면 https 주소로 넘긴다`() {
        val context = RecordingContext(RuntimeEnvironment.getApplication(), rejectVnd = true)

        val result = AppLauncher(context).openVideo("HP0a4pkXM3Q")

        assertTrue(result.isSuccess)
        assertEquals(1, context.started.size)
        val intent = context.started.single()
        assertEquals("https://www.youtube.com/watch?v=HP0a4pkXM3Q", intent.dataString)
        assertEquals(AppLauncher.YOUTUBE_PACKAGE, intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `영상 id 형식이 아니면 아무것도 열지 않는다`() {
        val context = RecordingContext(RuntimeEnvironment.getApplication(), rejectVnd = false)

        val result = AppLauncher(context).openVideo("bad id!")

        assertFalse(result.isSuccess)
        assertTrue(context.started.isEmpty())
    }
}
