package com.sihoo.aemx.accessibility

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test fun `new aliases map to their packages`() {
        assertEquals(AppLauncher.KAKAOTALK_PACKAGE, AppLauncher.packageFor("카톡"))
        assertEquals(AppLauncher.KAKAOTALK_PACKAGE, AppLauncher.packageFor("KakaoTalk"))
        assertEquals(AppLauncher.COUPANG_PACKAGE, AppLauncher.packageFor("쿠팡"))
        assertEquals(AppLauncher.YOUTUBE_MUSIC_PACKAGE, AppLauncher.packageFor("유튜브 뮤직"))
        assertEquals(AppLauncher.YOUTUBE_MUSIC_PACKAGE, AppLauncher.packageFor("유튜브뮤직"))
        assertEquals(AppLauncher.YOUTUBE_MUSIC_PACKAGE, AppLauncher.packageFor("YouTube Music"))
        assertEquals(AppLauncher.YOUTUBE_MUSIC_PACKAGE, AppLauncher.packageFor("유튜브 음악"))
        assertEquals(AppLauncher.YOUTUBE_PACKAGE, AppLauncher.packageFor("유튜브"))
    }

    /** 런처에 보이는 앱을 하나 설치한 것처럼 꾸민다. */
    private fun installLauncherApp(packageName: String, label: String, intent: Intent = launcherIntent()) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.Main"
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            }
            nonLocalizedLabel = label
        }
        shadowOf(RuntimeEnvironment.getApplication().packageManager).addResolveInfoForIntent(intent, info)
    }

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun launcher() = AppLauncher(RuntimeEnvironment.getApplication())

    @Test fun `installed app is found by its exact launcher label ignoring spaces and case`() {
        installLauncherApp("com.example.kurly", "마켓컬리")
        assertEquals("com.example.kurly", launcher().resolve("마켓 컬리"))
    }

    @Test fun `a single partial label match is accepted`() {
        installLauncherApp("com.example.homeplus", "홈플러스 익스프레스")
        assertEquals("com.example.homeplus", launcher().resolve("홈플러스"))
    }

    @Test fun `ambiguous partial matches resolve to null with a reason`() {
        installLauncherApp("com.example.bank", "카카오뱅크")
        installLauncherApp("com.example.pay", "카카오페이")

        assertNull(launcher().resolve("카카오"))
        val failure = launcher().launch("카카오").exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("모호"))
    }

    @Test fun `an exact label wins over longer partial matches`() {
        installLauncherApp("com.example.music", "멜론")
        installLauncherApp("com.example.musicplus", "멜론 플러스")
        assertEquals("com.example.music", launcher().resolve("멜론"))
    }

    @Test fun `packages without a launcher activity are not found by label`() {
        // 테마 같은 패키지는 런처 인텐트에 걸리지 않는다. 다른 인텐트에만 등록해 둔다.
        installLauncherApp("com.kakao.talk.theme.spring", "카카오톡 봄 테마", Intent(Intent.ACTION_VIEW))
        assertNull(launcher().resolve("카카오톡 봄 테마"))
    }

    @Test fun `the fixed table wins over a similarly named installed app`() {
        installLauncherApp("com.example.tube", "유튜브 클론")
        assertEquals(AppLauncher.YOUTUBE_PACKAGE, launcher().resolve("유튜브"))
    }

    @Test fun `unknown and missing apps fail clearly`() {
        val failure = launcher().launch("없는앱이름").exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("설치되지 않았거나"))
    }

    @Test fun `message aliases resolve to some sms app`() {
        assertNotNull(launcher().resolve("메시지"))
        assertNotNull(launcher().resolve("문자"))
    }

    @Test fun `youtube music direct search uses the music site`() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(AppLauncher(context).search("유튜브 뮤직", "아이유").isSuccess)
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(AppLauncher.YOUTUBE_MUSIC_PACKAGE, intent.`package`)
        assertEquals("https://music.youtube.com/search?q=%EC%95%84%EC%9D%B4%EC%9C%A0", intent.dataString)
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
