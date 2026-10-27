package com.sihoo.aemx.accessibility

import android.view.accessibility.AccessibilityWindowInfo

/**
 * 우리 오버레이 창을 실행 대상에서 가려내는 판정.
 *
 * 캡슐은 접근성 서비스가 띄우는 창(TYPE_ACCESSIBILITY_OVERLAY)이라 `service.windows` 에 우리 패키지의
 * 창으로 나타난다. 대상을 찾거나 맨 앞 창을 가릴 때 이 창이 섞이면 캡슐의 "중단" 같은 글자를 앱 화면으로
 * 착각할 수 있다. 판정은 안드로이드 없이 테스트하려고 값(창 종류, 패키지)만 받는다.
 */
internal object NodeFilter {

    /** 우리 앱이 띄운 접근성 오버레이 창인가. */
    fun isOwnOverlay(windowType: Int, packageName: CharSequence?, ownPackage: String): Boolean =
        windowType == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            packageName?.toString() == ownPackage

    /** 맨 앞 창의 패키지가 우리 앱인가. 오버레이가 앞이거나(포커스를 가져간 경우) 개발 화면이 앞이다. */
    fun isOwnPackage(packageName: CharSequence?, ownPackage: String): Boolean =
        packageName?.toString() == ownPackage
}
