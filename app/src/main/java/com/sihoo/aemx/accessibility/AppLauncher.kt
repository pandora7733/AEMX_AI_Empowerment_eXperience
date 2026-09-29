package com.sihoo.aemx.accessibility

import android.content.Context
import android.content.Intent
import android.net.Uri

internal class AppLauncher(private val context: Context) {
    fun launch(appName: String): Result<Unit> = runCatching {
        val packageName = packageFor(appName)
            ?: error("지원하지 않는 앱입니다.")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("앱이 설치되어 있지 않거나 실행 Activity를 찾을 수 없습니다.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launchIntent)
    }

    fun search(appName: String, query: String): Result<Unit> = runCatching {
        require(query.isNotBlank()) { "검색어가 비어 있습니다." }
        val packageName = packageFor(appName) ?: error("지원하지 않는 앱입니다.")
        val uri = when (packageName) {
            NAVER_PACKAGE -> Uri.parse("https://search.naver.com/search.naver?query=${Uri.encode(query)}")
            GOOGLE_PACKAGE -> Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
            YOUTUBE_PACKAGE -> Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            else -> error("이 앱은 직접 검색 실행을 지원하지 않습니다.")
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    companion object {
        // Google Play listing verified 2026-09-25; device validation remains required.
        private val PACKAGE_NAMES = mapOf(
            "교보문고" to "mok.android",
            "kyobobook" to "mok.android",
            "알라딘" to "kr.co.aladin.third_shop",
            "aladin" to "kr.co.aladin.third_shop",
            "네이버" to NAVER_PACKAGE,
            "naver" to NAVER_PACKAGE,
            "구글" to GOOGLE_PACKAGE,
            "google" to GOOGLE_PACKAGE,
            "유튜브" to YOUTUBE_PACKAGE,
            "youtube" to YOUTUBE_PACKAGE,
            "인스타그램" to INSTAGRAM_PACKAGE,
            "인스타" to INSTAGRAM_PACKAGE,
            "instagram" to INSTAGRAM_PACKAGE,
        )

        fun packageFor(value: String): String? = PACKAGE_NAMES[value.trim().lowercase(java.util.Locale.ROOT)]

        fun supportsDirectSearch(value: String): Boolean = packageFor(value) in setOf(
            NAVER_PACKAGE, GOOGLE_PACKAGE, YOUTUBE_PACKAGE,
        )

        const val NAVER_PACKAGE = "com.nhn.android.search"
        const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
    }
}
