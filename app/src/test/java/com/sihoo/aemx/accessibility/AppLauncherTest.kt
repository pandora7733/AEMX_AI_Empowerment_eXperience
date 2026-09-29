package com.sihoo.aemx.accessibility

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppLauncherTest {
    @Test fun `known app aliases map to verified package names`() {
        assertEquals(AppLauncher.NAVER_PACKAGE, AppLauncher.packageFor("네이버"))
        assertEquals(AppLauncher.GOOGLE_PACKAGE, AppLauncher.packageFor("Google"))
        assertEquals(AppLauncher.YOUTUBE_PACKAGE, AppLauncher.packageFor("유튜브"))
        assertEquals(AppLauncher.INSTAGRAM_PACKAGE, AppLauncher.packageFor("인스타"))
        assertNull(AppLauncher.packageFor("추측한 앱"))
    }

    @Test fun `direct search uses the requested app and encoded query`() {
        val context = RuntimeEnvironment.getApplication()
        val launcher = AppLauncher(context)
        assertTrue(launcher.search("네이버", "서울 날씨").isSuccess)
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(AppLauncher.NAVER_PACKAGE, intent.`package`)
        assertEquals("https://search.naver.com/search.naver?query=%EC%84%9C%EC%9A%B8%20%EB%82%A0%EC%94%A8", intent.dataString)
    }

    @Test fun `search rejects apps without a direct search contract`() {
        val launcher = AppLauncher(RuntimeEnvironment.getApplication())
        assertTrue(launcher.search("인스타그램", "뉴스").isFailure)
    }
}
