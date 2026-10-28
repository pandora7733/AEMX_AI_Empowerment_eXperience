package com.sihoo.aemx.pipeline.llm

import android.content.Context
import android.util.Log

/**
 * 클라우드 AI 설정. 키는 이 기기의 앱 전용 저장소에만 둔다(레포·APK 에 넣지 않는다).
 *
 * 앱 전용 저장소는 다른 앱이 읽을 수 없지만 암호화는 아니다. 개인 키를 넣은 기기를 남에게 주지 않는다.
 */
class CloudSettings(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 켜짐이 기본이다. 키가 없으면 켜져 있어도 클라우드를 부르지 않는다. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    val hasKey: Boolean get() = apiKey.isNotBlank()

    /** 지금 쓸 수 있으면 클라우드 계획 생성기, 꺼져 있거나 키가 없으면 null. */
    fun generatorOrNull(): CloudPlanGenerator? {
        if (!enabled || !hasKey) return null
        return CloudPlanGenerator(
            client = GroqChatClient(apiKey = apiKey),
            systemPrompt = AemxPrompts.systemPrompt(context),
            log = { Log.i(TAG, it) },
        )
    }

    private companion object {
        const val TAG = "CloudPlan"
        const val PREFS = "aemx_cloud"
        const val KEY_API_KEY = "api_key"
        const val KEY_ENABLED = "enabled"
    }
}
