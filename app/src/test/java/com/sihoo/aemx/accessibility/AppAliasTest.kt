package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 인스타 릴스·쇼츠처럼 앱 이름 뒤에 기능어가 붙은 말과 크롬 별칭. */
class AppAliasTest {

    @Test fun `앱 이름 뒤 기능어는 앞의 앱으로 본다`() {
        assertEquals(AppLauncher.INSTAGRAM_PACKAGE, AppLauncher.packageFor("인스타 릴스"))
        assertEquals(AppLauncher.INSTAGRAM_PACKAGE, AppLauncher.packageFor("인스타그램 릴스"))
        assertEquals(AppLauncher.YOUTUBE_PACKAGE, AppLauncher.packageFor("유튜브 쇼츠"))
    }

    @Test fun `유튜브 뮤직은 별개 앱이라 기능어 규칙에 걸리지 않는다`() {
        assertEquals(AppLauncher.YOUTUBE_MUSIC_PACKAGE, AppLauncher.packageFor("유튜브 뮤직"))
        assertEquals(AppLauncher.YOUTUBE_MUSIC_PACKAGE, AppLauncher.packageFor("유튜브뮤직"))
    }

    @Test fun `기능어만 있는 말은 앱으로 보지 않는다`() {
        assertNull(AppLauncher.packageFor("릴스"))
        assertNull(AppLauncher.packageFor("쇼츠"))
    }

    @Test fun `크롬은 한글과 영문 이름 모두 크롬 패키지로 본다`() {
        listOf("크롬", "구글 크롬", "구글크롬", "Chrome", "chrome").forEach {
            assertEquals(it, AppLauncher.CHROME_PACKAGE, AppLauncher.packageFor(it))
        }
    }

    @Test fun `크롬에서 검색은 직접 검색을 지원한다`() {
        assertTrue(AppLauncher.supportsDirectSearch("크롬"))
    }
}
