package com.sihoo.aemx.pipeline.llm

import android.content.Context
import android.util.Log
import com.sihoo.aemx.BuildConfig

/**
 * 클라우드 AI 설정.
 *
 * 키는 두 군데서 온다. 평소에는 빌드할 때 넣은 키([BuildConfig.GROQ_API_KEY], 소스·레포에는 없음)를 쓰고,
 * 디버그 화면에서 직접 입력하면 그 키가 우선한다(이 기기의 앱 전용 저장소, 암호화는 아님).
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

    /**
     * 실제로 쓰는 키. 화면에서 입력한 키가 있으면 그것이 우선이고, 없으면 빌드할 때 넣은 키를 쓴다.
     * 둘 다 없으면 빈 값이라 클라우드를 쓰지 않는다.
     */
    private val effectiveKey: String get() = apiKey.ifBlank { BuildConfig.GROQ_API_KEY }

    val hasKey: Boolean get() = effectiveKey.isNotBlank()

    /** 지금 쓸 수 있으면 클라우드 계획 생성기, 꺼져 있거나 키가 없으면 null. */
    fun generatorOrNull(): CloudPlanGenerator? {
        if (!enabled || !hasKey) return null
        return CloudPlanGenerator(
            client = GroqChatClient(apiKey = effectiveKey),
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
