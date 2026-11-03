package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityStatusTest {

    private val expected = "com.sihoo.aemx/com.sihoo.aemx.accessibility.AemxAccessibilityService"

    @Test fun `연결돼 있으면 설정과 관계없이 연결됨이다`() {
        assertEquals(AccessibilityState.Connected, AccessibilityStatus.of(connected = true, configured = false))
        assertEquals(AccessibilityState.Connected, AccessibilityStatus.of(connected = true, configured = true))
    }

    @Test fun `설정에는 켜져 있는데 연결이 끊겼으면 껐다 켜라는 중간 상태다`() {
        assertEquals(
            AccessibilityState.ConfiguredButDisconnected,
            AccessibilityStatus.of(connected = false, configured = true),
        )
    }

    @Test fun `연결도 설정도 없으면 꺼짐이다`() {
        assertEquals(AccessibilityState.Off, AccessibilityStatus.of(connected = false, configured = false))
    }

    @Test fun `설정 값에 서비스 이름이 콜론으로 이어져 있어도 찾는다`() {
        val setting = "com.other/.Foo:$expected"
        assertTrue(AccessibilityStatus.configuredIn(setting, expected))
    }

    @Test fun `대소문자는 구분하지 않는다`() {
        assertTrue(AccessibilityStatus.configuredIn(expected.uppercase(), expected))
    }

    @Test fun `설정 값이 없거나 비었거나 다른 서비스뿐이면 켜진 것이 아니다`() {
        assertFalse(AccessibilityStatus.configuredIn(null, expected))
        assertFalse(AccessibilityStatus.configuredIn("", expected))
        assertFalse(AccessibilityStatus.configuredIn("com.other/.Foo", expected))
    }
}
