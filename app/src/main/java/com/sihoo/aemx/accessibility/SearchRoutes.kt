package com.sihoo.aemx.accessibility

import java.net.URLEncoder

/**
 * 검색을 열 방법을 시도 순서대로 만든다. 앞에 있을수록 먼저 시도한다. 실제로 여는 일은 [AppLauncher] 가 하고,
 * 어느 앱이 받을 수 없으면(ActivityNotFoundException) 다음 방법으로 넘어간다.
 *
 * 구글 앱은 검색 주소(VIEW https)를 받지 못하는 기기가 있어서 웹 검색 인텐트를 한 번 더 두고,
 * 그래도 안 되면 패키지를 지정하지 않은 주소(브라우저)로 연다.
 */
internal object SearchRoutes {

    sealed interface Route {
        /** 검색 주소를 연다. [packageName] 이 null 이면 아무 앱(보통 브라우저)이나 받는다. */
        data class View(val uri: String, val packageName: String?) : Route

        /** 웹 검색 인텐트를 그 앱에 보낸다(검색어를 extra 로 넘긴다). */
        data class WebSearch(val query: String, val packageName: String) : Route
    }

    /** [packageName] 앱에 검색을 맡길 방법들. 직접 검색을 지원하지 않는 앱이면 빈 목록. */
    fun routes(packageName: String, query: String): List<Route> {
        // 공백은 "+" 가 아니라 "%20" 으로 보낸다(예전 Uri.encode 와 같다).
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val url = when (packageName) {
            AppLauncher.NAVER_PACKAGE -> "https://search.naver.com/search.naver?query=$encoded"
            AppLauncher.GOOGLE_PACKAGE -> "https://www.google.com/search?q=$encoded"
            AppLauncher.YOUTUBE_PACKAGE -> "https://www.youtube.com/results?search_query=$encoded"
            // 유튜브 뮤직 앱이 music.youtube.com 링크를 받는다(실기기에서 확인 필요).
            AppLauncher.YOUTUBE_MUSIC_PACKAGE -> "https://music.youtube.com/search?q=$encoded"
            // 크롬은 웹 검색 주소를 크롬 안에서 연다. 주소를 받지 못하면 아래 브라우저 방법으로 넘어간다.
            AppLauncher.CHROME_PACKAGE -> "https://www.google.com/search?q=$encoded"
            else -> return emptyList()
        }
        val inApp = Route.View(url, packageName)
        val inBrowser = Route.View(url, null)
        return if (packageName == AppLauncher.GOOGLE_PACKAGE) {
            listOf(inApp, Route.WebSearch(query, packageName), inBrowser)
        } else {
            listOf(inApp, inBrowser)
        }
    }
}
