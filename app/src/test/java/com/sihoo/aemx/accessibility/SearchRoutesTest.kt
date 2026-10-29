package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRoutesTest {

    @Test
    fun `구글은 앱 주소, 웹 검색 인텐트, 브라우저 순서로 시도한다`() {
        val routes = SearchRoutes.routes(AppLauncher.GOOGLE_PACKAGE, "페이커")

        assertEquals(3, routes.size)
        val first = routes[0] as SearchRoutes.Route.View
        assertEquals(AppLauncher.GOOGLE_PACKAGE, first.packageName)
        assertTrue(first.uri.startsWith("https://www.google.com/search?q="))
        assertEquals(SearchRoutes.Route.WebSearch("페이커", AppLauncher.GOOGLE_PACKAGE), routes[1])
        val browser = routes[2] as SearchRoutes.Route.View
        assertEquals(null, browser.packageName)
        assertEquals(first.uri, browser.uri)
    }

    @Test
    fun `네이버와 유튜브는 앱 주소 다음에 브라우저로 시도한다`() {
        listOf(AppLauncher.NAVER_PACKAGE, AppLauncher.YOUTUBE_PACKAGE, AppLauncher.YOUTUBE_MUSIC_PACKAGE).forEach { pkg ->
            val routes = SearchRoutes.routes(pkg, "고양이")

            assertEquals(2, routes.size)
            assertEquals(pkg, (routes[0] as SearchRoutes.Route.View).packageName)
            assertEquals(null, (routes[1] as SearchRoutes.Route.View).packageName)
        }
    }

    @Test
    fun `검색어는 주소에서 공백 없이 인코딩된다`() {
        val uri = (SearchRoutes.routes(AppLauncher.NAVER_PACKAGE, "서울 날씨")[0] as SearchRoutes.Route.View).uri

        assertTrue(uri.startsWith("https://search.naver.com/search.naver?query="))
        assertFalse(uri.contains(" "))
    }

    @Test
    fun `직접 검색을 지원하지 않는 앱은 방법이 없다`() {
        assertTrue(SearchRoutes.routes("com.example.other", "고양이").isEmpty())
    }
}
