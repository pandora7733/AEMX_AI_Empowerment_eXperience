package com.sihoo.aemx.pipeline.llm

import android.content.Context

/**
 * LLM 에 넣는 시스템 프롬프트.
 *
 * 원본은 `LLM/system_prompt.txt` 하나뿐이고, 빌드할 때 assets 로 복사된다
 * (`app/build.gradle.kts` 의 copySystemPrompt). 프롬프트를 고칠 일이 있으면
 * 그 파일만 고치면 된다.
 */
object AemxPrompts {

    private const val ASSET_NAME = "system_prompt.txt"

    private var cached: String? = null

    fun systemPrompt(context: Context): String = cached ?: context.assets
        .open(ASSET_NAME)
        .bufferedReader()
        .use { it.readText() }
        .also { cached = it }
}
