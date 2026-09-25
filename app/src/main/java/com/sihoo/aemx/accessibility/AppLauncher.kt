package com.sihoo.aemx.accessibility

import android.content.Context
import android.content.Intent

internal class AppLauncher(private val context: Context) {
    fun launch(appName: String): Result<Unit> = runCatching {
        val packageName = packageFor(appName)
            ?: error("지원하지 않는 앱입니다.")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("앱이 설치되어 있지 않거나 실행 Activity를 찾을 수 없습니다.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launchIntent)
    }

    companion object {
        // Google Play listing verified 2026-09-25; device validation remains required.
        private val PACKAGE_NAMES = mapOf(
            "교보문고" to "mok.android",
            "kyobobook" to "mok.android",
            "알라딘" to "kr.co.aladin.third_shop",
            "aladin" to "kr.co.aladin.third_shop",
        )

        fun packageFor(value: String): String? = PACKAGE_NAMES[value.trim().lowercase(java.util.Locale.ROOT)]
    }
}
