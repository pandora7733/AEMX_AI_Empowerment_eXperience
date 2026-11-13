package com.sihoo.aemx.pipeline

import com.sihoo.aemx.accessibility.AppLauncher
import com.sihoo.aemx.accessibility.SearchRoutes
import com.sihoo.aemx.pipeline.llm.DefaultSearchGuard
import com.sihoo.aemx.pipeline.llm.SearchApp
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSearchGuardTest {

    private fun searchPlan(app: String, query: String = "서울 날씨") = ActionPlan(
        app,
        listOf(ActionStep(0, ActionType.OPEN_APP, app), ActionStep(1, ActionType.SEARCH, query)),
    )

    /** 앱을 말하지 않으면 모델은 네이버를 고른다. */
    private val modelNaver = searchPlan("네이버")

    @Test
    fun `앱을 말하지 않은 검색은 고른 검색 앱으로 바꾼다`() {
        val fixed = DefaultSearchGuard.apply("서울 날씨 검색해줘", modelNaver, SearchApp.CHROME)

        assertEquals("크롬", fixed.app)
        // open_app 의 target 도 같이 바뀌어야 실행모듈이 계획을 받는다.
        assertEquals("크롬", fixed.steps[0].target)
        assertEquals("서울 날씨", fixed.steps[1].target)
    }

    @Test
    fun `기기 기본 브라우저를 골랐으면 브라우저로 보낸다`() {
        val fixed = DefaultSearchGuard.apply("서울 날씨 검색해줘", modelNaver, SearchApp.BROWSER)

        assertEquals("브라우저", fixed.app)
        assertEquals(AppLauncher.DEFAULT_BROWSER, AppLauncher.packageFor(fixed.app))
    }

    @Test
    fun `고른 앱과 계획의 앱이 같으면 그대로 둔다`() {
        assertSame(modelNaver, DefaultSearchGuard.apply("서울 날씨 검색해줘", modelNaver, SearchApp.NAVER))
    }

    @Test
    fun `검색 앱을 직접 말했으면 말한 대로 둔다`() {
        assertSame(modelNaver, DefaultSearchGuard.apply("네이버에서 서울 날씨 검색해줘", modelNaver, SearchApp.CHROME))
        val google = searchPlan("구글")
        assertSame(google, DefaultSearchGuard.apply("구글로 서울 날씨 찾아줘", google, SearchApp.NAVER))
        val chrome = searchPlan("크롬")
        assertSame(chrome, DefaultSearchGuard.apply("크롬에서 서울 날씨 검색해줘", chrome, SearchApp.NAVER))
    }

    @Test
    fun `검색어인지 앱인지 애매하게 검색 앱 이름이 들어 있으면 건드리지 않는다`() {
        val plan = searchPlan("네이버", "구글 주가")

        assertSame(plan, DefaultSearchGuard.apply("구글 주가 검색해줘", plan, SearchApp.CHROME))
    }

    @Test
    fun `유튜브나 쿠팡 안을 찾는 검색은 건드리지 않는다`() {
        val youtube = searchPlan("유튜브", "고양이 영상")
        val coupang = searchPlan("쿠팡", "생수")

        assertSame(youtube, DefaultSearchGuard.apply("고양이 영상 검색해줘", youtube, SearchApp.CHROME))
        assertSame(coupang, DefaultSearchGuard.apply("쿠팡에서 생수 검색해줘", coupang, SearchApp.CHROME))
    }

    @Test
    fun `쿠팡을 말하지 않았는데 모델이 쿠팡 검색을 내면 고른 검색 앱으로 보낸다`() {
        // 실기기에서 "부산 맛집 찾아줘" 에 모델이 낸 계획이다.
        val guessed = searchPlan("쿠팡", "부산 맛집")

        val fixed = DefaultSearchGuard.apply("부산 맛집 찾아줘", guessed, SearchApp.BROWSER)

        assertEquals("브라우저", fixed.app)
        assertEquals("브라우저", fixed.steps[0].target)
        assertEquals("부산 맛집", fixed.steps[1].target)
        // 고른 앱이 네이버여도 쿠팡이 아니라 네이버로 간다.
        assertEquals("네이버", DefaultSearchGuard.apply("부산 맛집 찾아줘", guessed, SearchApp.NAVER).app)
    }

    @Test
    fun `쿠팡에서 담으라는 계획은 쿠팡을 말하지 않았어도 그대로 둔다`() {
        val cart = ActionPlan(
            "쿠팡",
            listOf(ActionStep(0, ActionType.SEARCH, "생수"), ActionStep(1, ActionType.ADD_TO_CART, "첫 번째 상품")),
        )

        assertSame(cart, DefaultSearchGuard.apply("생수 장바구니에 담아줘", cart, SearchApp.CHROME))
    }

    @Test
    fun `검색이 없는 계획은 건드리지 않는다`() {
        val open = ActionPlan("네이버", listOf(ActionStep(0, ActionType.OPEN_APP, "네이버")))

        assertSame(open, DefaultSearchGuard.apply("앱 열어줘", open, SearchApp.CHROME))
    }

    @Test
    fun `고르지 않았으면 네이버이고 모르는 값도 네이버다`() {
        assertEquals(SearchApp.NAVER, SearchApp.fromKey(null))
        assertEquals(SearchApp.NAVER, SearchApp.fromKey("없는 값"))
        assertEquals(SearchApp.CHROME, SearchApp.fromKey("chrome"))
        assertEquals(SearchApp.BROWSER, SearchApp.fromKey("browser"))
    }

    @Test
    fun `검색 앱 이름을 말했는지 가린다`() {
        assertTrue(DefaultSearchGuard.namesWebSearchApp("구글에서 날씨 검색해줘"))
        assertTrue(DefaultSearchGuard.namesWebSearchApp("Chrome 으로 찾아줘"))
        assertTrue(DefaultSearchGuard.namesWebSearchApp("인터넷에서 찾아줘"))
        assertFalse(DefaultSearchGuard.namesWebSearchApp("서울 날씨 검색해줘"))
        assertFalse(DefaultSearchGuard.namesWebSearchApp("쿠팡에서 생수 검색해줘"))
    }

    @Test
    fun `고를 수 있는 검색 앱은 모두 바로 검색을 지원한다`() {
        SearchApp.entries.forEach { app ->
            assertTrue("${app.appName} 은 바로 검색을 지원해야 한다", AppLauncher.supportsDirectSearch(app.appName))
        }
    }

    @Test
    fun `기본 브라우저는 그 브라우저 앱으로 먼저 열고 안 되면 아무 앱으로 넘긴다`() {
        val routes = SearchRoutes.browserRoutes("com.example.browser", "서울 날씨")

        assertEquals(2, routes.size)
        val first = routes[0] as SearchRoutes.Route.View
        assertEquals("com.example.browser", first.packageName)
        assertEquals("https://www.google.com/search?q=%EC%84%9C%EC%9A%B8%20%EB%82%A0%EC%94%A8", first.uri)
        assertEquals(null, (routes[1] as SearchRoutes.Route.View).packageName)
    }
}
