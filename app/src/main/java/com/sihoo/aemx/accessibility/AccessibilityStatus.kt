package com.sihoo.aemx.accessibility

/**
 * 접근성 서비스 상태. 설정에 이름이 남아 있어도 실제로 연결되지 않았을 수 있다(재설치나 강제 종료 뒤).
 * 그래서 "연결됨"만 완료로 본다.
 */
enum class AccessibilityState {
    /** 실제로 연결돼 있다. 실행할 수 있다. */
    Connected,

    /** 설정에는 켜져 있는데 연결이 끊겼다. 껐다 켜 달라고 안내한다. */
    ConfiguredButDisconnected,

    /** 설정에서 꺼져 있다. */
    Off,
}

/** 접근성 상태를 판정하는 순수 함수. 안드로이드 없이 테스트한다. */
object AccessibilityStatus {

    fun of(connected: Boolean, configured: Boolean): AccessibilityState = when {
        connected -> AccessibilityState.Connected
        configured -> AccessibilityState.ConfiguredButDisconnected
        else -> AccessibilityState.Off
    }

    /**
     * 설정(enabled_accessibility_services)의 값에 [expected] 서비스 이름이 있는지. 값은 콜론으로 이어져 있고
     * 대소문자는 구분하지 않는다. 값이 없으면 거짓이다.
     */
    fun configuredIn(settingValue: String?, expected: String): Boolean {
        if (settingValue.isNullOrBlank()) return false
        return settingValue.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
