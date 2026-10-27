package com.sihoo.aemx.accessibility

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeFilterTest {
    private val own = "com.sihoo.aemx"

    @Test
    fun `우리 패키지의 접근성 오버레이 창만 오버레이로 본다`() {
        assertTrue(NodeFilter.isOwnOverlay(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, own, own))
        // 다른 앱의 오버레이는 우리 것이 아니다.
        assertFalse(NodeFilter.isOwnOverlay(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, "com.google.android.youtube", own))
        // 우리 앱의 일반 창(개발 화면)은 오버레이가 아니다.
        assertFalse(NodeFilter.isOwnOverlay(AccessibilityWindowInfo.TYPE_APPLICATION, own, own))
        assertFalse(NodeFilter.isOwnOverlay(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, null, own))
    }

    @Test
    fun `맨 앞 창이 우리 패키지인지 본다`() {
        assertTrue(NodeFilter.isOwnPackage(own, own))
        assertFalse(NodeFilter.isOwnPackage("com.kakao.talk", own))
        assertFalse(NodeFilter.isOwnPackage(null, own))
    }
}
