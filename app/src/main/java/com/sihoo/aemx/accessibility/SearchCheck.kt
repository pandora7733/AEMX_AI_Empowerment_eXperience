package com.sihoo.aemx.accessibility

/**
 * 검색이 끝났다고 볼 조건. 앱 안에서 열었으면 그 앱 화면이고 검색어가 보여야 한다. 브라우저로 열었으면 패키지는
 * 알 수 없으니 검색어가 화면에 보이는 것만 본다. 검색어가 안 보이면 브라우저 경로도 실패다.
 *
 * 이 판정이 통과했을 때 실행기는 [BROWSER_EVIDENCE] 를 증거로 남긴다. 오케스트레이터는 그 증거를 보고
 * 패키지 기대를 빼고 화면 검증을 한다.
 */
internal object SearchCheck {

    /** 브라우저로 검색했을 때 남기는 증거 문장. 오케스트레이터가 이 값을 보고 패키지 기대를 뺀다. */
    const val BROWSER_EVIDENCE = "브라우저에서 검색"

    fun accept(openedInBrowser: Boolean, packageMatches: Boolean, queryVisible: Boolean): Boolean =
        queryVisible && (openedInBrowser || packageMatches)
}
