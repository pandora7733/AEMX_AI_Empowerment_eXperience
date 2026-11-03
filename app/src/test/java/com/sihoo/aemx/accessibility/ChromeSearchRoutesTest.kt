package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeSearchRoutesTest {

    @Test fun `크롬은 크롬 안의 웹 검색 주소를 먼저 연다`() {
        val routes = SearchRoutes.routes(AppLauncher.CHROME_PACKAGE, "오늘 날씨")

        assertEquals(2, routes.size)
        val first = routes[0] as SearchRoutes.Route.View
        assertEquals(AppLauncher.CHROME_PACKAGE, first.packageName)
        assertTrue(first.uri.startsWith("https://www.google.com/search?q="))
        assertTrue(first.uri.endsWith("%EC%98%A4%EB%8A%98%20%EB%82%A0%EC%94%A8"))
        val browser = routes[1] as SearchRoutes.Route.View
        assertNull(browser.packageName)
    }
}
