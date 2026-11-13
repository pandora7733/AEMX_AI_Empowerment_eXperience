package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.accessibility.AppLauncher
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionType

/**
 * 사용자가 평소에 쓰는 검색 앱. 홈 화면에서 고르고, 앱을 말하지 않은 웹 검색에 쓴다.
 *
 * @param key 설정에 저장하는 값. 바꾸면 저장된 설정이 기본값으로 돌아간다.
 * @param appName 계획의 app 에 넣는 이름. 실행모듈이 이 이름으로 앱을 찾는다([AppLauncher]).
 */
enum class SearchApp(val key: String, val appName: String) {
    NAVER("naver", "네이버"),
    GOOGLE("google", "구글"),
    CHROME("chrome", "크롬"),

    /** 기기에 기본으로 설정된 브라우저(삼성 인터넷, 제조사 브라우저 등). 구글 검색 주소를 연다. */
    BROWSER("browser", "브라우저");

    companion object {
        /** 설정을 따로 고르지 않았을 때. 지금까지 모델이 골라 온 앱과 같다. */
        val DEFAULT = NAVER

        fun fromKey(key: String?): SearchApp = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * 앱을 말하지 않은 웹 검색을 사용자가 고른 검색 앱으로 보낸다.
 *
 * "서울 날씨 검색해줘" 처럼 앱을 말하지 않으면 모델은 프롬프트 예시를 따라 네이버를 고른다. 구글이나 크롬을 쓰는
 * 사람은 매번 "구글에서" 를 붙여야 했다. 홈 화면에서 고른 검색 앱([SearchApp])이 있으면 그 앱으로 바꾼다.
 *
 * 바꾸는 조건은 좁다.
 * - 계획에 search 가 있고, 계획의 앱이 웹 검색 앱(네이버, 구글, 크롬, 기본 브라우저)이다.
 *   유튜브, 쿠팡처럼 그 앱 안을 찾는 검색은 건드리지 않는다.
 * - 사용자의 말에 웹 검색 앱 이름이 하나도 없다. "구글에서 …", "네이버로 …" 처럼 직접 말했으면 말한 대로 둔다.
 *   "구글 주가 검색해줘" 처럼 검색어인지 앱인지 애매한 말도 건드리지 않는다(모델이 고른 대로 둔다).
 *
 * 쿠팡은 예외로 함께 본다. 사용자가 쿠팡을 말하지 않았는데 모델이 쿠팡에서 검색하는 계획을 내는 일이 있다
 * ("부산 맛집 찾아줘" 를 쿠팡에서 검색, 실기기에서 확인). 쿠팡이라고 말하지 않았고 장바구니 담기도 없는
 * 단순 검색이면 모델이 앱을 짐작한 것으로 보고 고른 검색 앱으로 보낸다. 유튜브처럼 말의 내용("영상")으로
 * 앱을 고르는 것이 맞는 경우는 건드리지 않는다.
 */
object DefaultSearchGuard {

    /** 지금 고른 검색 앱. 앱이 시작할 때 설정을 읽는 함수로 바꿔 끼운다(PipelineCore.init). */
    @Volatile
    var preferred: () -> SearchApp = { SearchApp.DEFAULT }

    fun apply(utterance: String, plan: ActionPlan, choice: SearchApp = preferred()): ActionPlan {
        if (plan.steps.none { it.action == ActionType.SEARCH }) return plan
        val planPackage = AppLauncher.packageFor(plan.app) ?: return plan
        val webSearch = planPackage in WEB_SEARCH_PACKAGES
        if (!webSearch && !guessedShopping(utterance, plan, planPackage)) return plan
        if (namesWebSearchApp(utterance)) return plan
        if (AppLauncher.packageFor(choice.appName) == planPackage) return plan

        // open_app 의 target 은 plan.app 과 같아야 실행모듈이 받아 준다. 같이 바꾼다.
        val steps = plan.steps.map { step ->
            if (step.action == ActionType.OPEN_APP) step.copy(target = choice.appName) else step
        }
        return plan.copy(app = choice.appName, steps = steps)
    }

    /** 사용자가 쿠팡을 말하지 않았는데 모델이 쿠팡 검색을 낸 것인가. 장바구니에 담으라는 계획이면 쇼핑 의도로 보고 아니다. */
    private fun guessedShopping(utterance: String, plan: ActionPlan, planPackage: String): Boolean {
        if (planPackage != AppLauncher.COUPANG_PACKAGE) return false
        if (plan.steps.any { it.action == ActionType.ADD_TO_CART }) return false
        val text = utterance.lowercase()
        return SHOPPING_WORDS.none { text.contains(it) }
    }

    /** 사용자가 웹 검색 앱을 직접 말했는가. */
    fun namesWebSearchApp(utterance: String): Boolean {
        val text = utterance.lowercase()
        return WEB_SEARCH_WORDS.any { text.contains(it) }
    }

    private val WEB_SEARCH_PACKAGES = setOf(
        AppLauncher.NAVER_PACKAGE,
        AppLauncher.GOOGLE_PACKAGE,
        AppLauncher.CHROME_PACKAGE,
        AppLauncher.DEFAULT_BROWSER,
    )

    private val SHOPPING_WORDS = listOf("쿠팡", "coupang")

    private val WEB_SEARCH_WORDS = listOf("네이버", "naver", "구글", "google", "크롬", "chrome", "브라우저", "인터넷")
}
