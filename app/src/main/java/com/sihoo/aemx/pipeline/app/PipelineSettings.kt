package com.sihoo.aemx.pipeline.app

import android.content.Context

/**
 * 파이프라인 설정. 개발 화면과 앱 본체가 같은 값을 읽고 쓴다.
 *
 * 기본값은 시연용이다: 진짜 LLM, 실제 조작, 오케스트레이터 경유, 호출어 필요, 유튜브 API 는 켬.
 * 말한 명령을 바로 실행하는 것(autoRun)도 앱 본체에서는 켬이어야 호출어 뒤 명령이 실행된다.
 * 내 목소리만 듣기는 등록 전에는 쓸 수 없어서 기본 꺼짐이다.
 * 오버레이 캡슐과 유튜브 API 는 각각 AemxAccessibilityService 와 CloudSettings 가 가진다.
 */
class PipelineSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var realLlm: Boolean
        get() = prefs.getBoolean(KEY_REAL_LLM, true)
        set(value) = prefs.edit().putBoolean(KEY_REAL_LLM, value).apply()

    var realExecutor: Boolean
        get() = prefs.getBoolean(KEY_REAL_EXECUTOR, true)
        set(value) = prefs.edit().putBoolean(KEY_REAL_EXECUTOR, value).apply()

    var orchestrator: Boolean
        get() = prefs.getBoolean(KEY_ORCHESTRATOR, true)
        set(value) = prefs.edit().putBoolean(KEY_ORCHESTRATOR, value).apply()

    var wakeWord: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD, value).apply()

    var ownerOnly: Boolean
        get() = prefs.getBoolean(KEY_OWNER_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_OWNER_ONLY, value).apply()

    /** 말이 끝나면 실행 버튼과 같은 경로로 바로 실행한다. 음성 명령이 실행되려면 필요하다. */
    var autoRun: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RUN, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RUN, value).apply()

    /** 메시지를 보내기 전에 받는 사람과 내용을 확인받는다. 켜짐이 기본이다. 끄는 것은 개발 화면과 adb 시험용이다. */
    var confirmSend: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_SEND, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_SEND, value).apply()

    /**
     * 앱을 말하지 않은 웹 검색에 쓸 검색 앱의 키([com.sihoo.aemx.pipeline.llm.SearchApp.key]).
     * 고른 적이 없으면 null 이고, 그때는 지금까지처럼 네이버다.
     */
    var searchApp: String?
        get() = prefs.getString(KEY_SEARCH_APP, null)
        set(value) = prefs.edit().putString(KEY_SEARCH_APP, value).apply()

    private companion object {
        const val PREFS = "aemx_pipeline_settings"
        const val KEY_REAL_LLM = "real_llm"
        const val KEY_REAL_EXECUTOR = "real_executor"
        const val KEY_ORCHESTRATOR = "orchestrator"
        const val KEY_WAKE_WORD = "wake_word"
        const val KEY_OWNER_ONLY = "owner_only"
        const val KEY_AUTO_RUN = "auto_run"
        const val KEY_CONFIRM_SEND = "confirm_send"
        const val KEY_SEARCH_APP = "search_app"
    }
}
