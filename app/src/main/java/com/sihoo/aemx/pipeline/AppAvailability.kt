package com.sihoo.aemx.pipeline

/**
 * 앱이 기기에 없거나 열 화면(Activity)이 없어서 여는 것 자체가 안 되는 실패인지 가린다.
 *
 * 이 실패는 화면을 다시 봐도 풀리지 않으므로 재분석하지 않고 바로 끝낸다. 실패 문구는 접근성 서비스의
 * AppLauncher 와 실행 경로가 만든다.
 */
object AppAvailability {

    private val NOT_INSTALLED_MARKERS = listOf(
        "설치되어 있지 않",
        "설치되지 않았거나",
        "실행 Activity",
    )

    fun isNotInstalled(message: String): Boolean = NOT_INSTALLED_MARKERS.any { message.contains(it) }

    /** 화면을 가진 대상이 우리 앱일 때의 거절 문장. 재분석해도 우리 화면을 조작하지 않도록 바로 끝낸다. */
    const val OWN_APP_SCREEN_MESSAGE = "우리 앱 화면은 조작하지 않아요"

    /** 사용자에게 그대로 읽어 줄 안내. TTS 와 캡슐에 쓰인다. */
    fun userMessage(app: String): String = "$app 앱이 설치되어 있지 않아요"
}
