package com.sihoo.aemx.accessibility

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.provider.Telephony
import java.util.Locale

internal class AppLauncher(private val context: Context) {

    /** 이름을 풀어낸 결과. 못 찾았으면 [packageName] 이 null 이고 [reason] 에 이유가 있다. */
    internal class Resolution(val packageName: String?, val reason: String? = null)

    /** 한 번 풀어낸 이름. 계획을 실행하는 동안 같은 앱을 여러 번 묻는다. 못 찾은 것은 담지 않는다. */
    private val resolved = HashMap<String, String>()

    /**
     * 앱 이름을 패키지명으로 바꾼다. 못 찾거나 모호하면 null.
     *
     * 순서: 고정 표(별칭 포함, 메시지 앱은 기본 SMS 앱) → 설치된 런처 앱의 라벨 정확 일치 →
     * 라벨 포함 일치가 하나뿐일 때.
     */
    fun resolve(appName: String): String? = resolution(appName).packageName

    private fun resolution(appName: String): Resolution {
        val key = normalizeName(appName)
        resolved[key]?.let { return Resolution(it) }
        val found = when {
            key in SMS_ALIASES -> Resolution(defaultSmsPackage())
            else -> packageFor(appName)?.let { Resolution(it) } ?: findByLabel(appName)
        }
        found.packageName?.let { resolved[key] = it }
        return found
    }

    private fun defaultSmsPackage(): String =
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull() ?: SAMSUNG_MESSAGES_PACKAGE

    /**
     * 설치된 런처 앱의 라벨로 찾는다. 런처 액티비티가 있는 앱만 보므로 테마 같은 패키지
     * (com.kakao.talk.theme.* 등)는 섞이지 않는다. Android 11+ 에서는 매니페스트 `<queries>` 에
     * MAIN/LAUNCHER 인텐트가 있어야 목록이 보인다.
     */
    private fun findByLabel(appName: String): Resolution {
        val query = normalizeName(appName)
        if (query.length < MIN_LABEL_QUERY) return Resolution(null, "설치되지 않았거나 찾을 수 없는 앱입니다: $appName")

        val packageManager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0).mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            packageName to normalizeName(info.loadLabel(packageManager).toString())
        }.distinct()

        return pickByLabel(appName, query, apps)
    }

    fun launch(appName: String): Result<Unit> = runCatching {
        val found = resolution(appName)
        val packageName = found.packageName
            ?: error(found.reason ?: "설치되지 않았거나 찾을 수 없는 앱입니다.")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("앱이 설치되어 있지 않거나 실행 Activity를 찾을 수 없습니다.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { Log.i(TAG, "앱 실행: $appName -> $packageName, 시작 화면=${launchIntent.component?.flattenToShortString()}") }
        context.startActivity(launchIntent)
    }

    /**
     * 유튜브 영상 하나를 유튜브 앱으로 연다. 영상 id 형식이 아니면 열지 않는다.
     *
     * 재생 중인 영상이 있으면 https 주소는 무시되고(유튜브가 최상위 인스턴스에 전달만 한다), vnd.youtube 주소는 바뀐다.
     * 그래서 vnd.youtube 를 먼저 쓰고, 받을 앱이 없을 때만 https 주소로 넘긴다.
     */
    fun openVideo(videoId: String): Result<Unit> = runCatching {
        require(isVideoId(videoId)) { "영상 id 형식이 아닙니다." }
        val native = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
            setPackage(YOUTUBE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(native)
        } catch (_: ActivityNotFoundException) {
            val uri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(YOUTUBE_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    /** 마지막 검색을 브라우저가 열었는지. 검색 확인은 앱 패키지 대신 검색어로 한다. */
    @Volatile
    var openedInBrowser = false
        private set

    fun search(appName: String, query: String): Result<Unit> = runCatching {
        require(query.isNotBlank()) { "검색어가 비어 있습니다." }
        val found = resolution(appName)
        val packageName = found.packageName ?: error(found.reason ?: "지원하지 않는 앱입니다.")
        val routes = SearchRoutes.routes(packageName, query)
        if (routes.isEmpty()) error("이 앱은 직접 검색 실행을 지원하지 않습니다.")
        // 앞의 방법이 받을 앱이 없으면(ActivityNotFoundException) 다음 방법으로 넘어간다. 모두 실패하면 마지막 원인을 알린다.
        var lastError: Exception? = null
        openedInBrowser = false
        for (route in routes) {
            try {
                context.startActivity(route.toIntent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                // 패키지를 지정하지 않은 주소는 브라우저가 연다. 그 화면은 앱 패키지가 아니므로 검색어로만 확인한다.
                openedInBrowser = route is SearchRoutes.Route.View && route.packageName == null
                return@runCatching
            } catch (e: ActivityNotFoundException) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("검색을 열 방법이 없습니다.")
    }

    private fun SearchRoutes.Route.toIntent(): Intent = when (this) {
        is SearchRoutes.Route.View -> Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            packageName?.let { setPackage(it) }
        }
        is SearchRoutes.Route.WebSearch -> Intent(Intent.ACTION_WEB_SEARCH).apply {
            // SearchManager.QUERY 와 같은 키.
            putExtra("query", query)
            setPackage(packageName)
        }
    }

    companion object {
        private const val TAG = "AemxAppLauncher"

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
            "유튜브 뮤직" to YOUTUBE_MUSIC_PACKAGE,
            "유튜브뮤직" to YOUTUBE_MUSIC_PACKAGE,
            "유튜브 음악" to YOUTUBE_MUSIC_PACKAGE,
            "youtube music" to YOUTUBE_MUSIC_PACKAGE,
            "인스타그램" to INSTAGRAM_PACKAGE,
            "인스타" to INSTAGRAM_PACKAGE,
            "instagram" to INSTAGRAM_PACKAGE,
            "카카오톡" to KAKAOTALK_PACKAGE,
            "카톡" to KAKAOTALK_PACKAGE,
            "kakaotalk" to KAKAOTALK_PACKAGE,
            "멜론" to MELON_PACKAGE,
            "melon" to MELON_PACKAGE,
            "쿠팡" to COUPANG_PACKAGE,
            "coupang" to COUPANG_PACKAGE,
            // 메시지 앱은 기기마다 다르다. 실제로 열 때는 기본 SMS 앱으로 바꾼다([resolve]).
            "메시지" to SAMSUNG_MESSAGES_PACKAGE,
            "문자" to SAMSUNG_MESSAGES_PACKAGE,
            "메세지" to SAMSUNG_MESSAGES_PACKAGE,
        ).mapKeys { normalizeName(it.key) }

        private val SMS_ALIASES = setOf("메시지", "문자", "메세지").map(::normalizeName).toSet()

        /** 라벨 검색에 쓸 최소 길이. 한 글자로는 너무 많은 앱에 걸린다. */
        private const val MIN_LABEL_QUERY = 2

        /**
         * 설치된 앱의 (패키지, 정규화한 라벨) 목록에서 [query] 에 맞는 앱 하나를 고른다. 순수 함수다.
         *
         * 1. 라벨 정확 일치가 하나면 그것, 여럿이면 모호해서 null.
         * 2. 정확 일치가 없으면 라벨이 query 를 포함하고 **query 가 라벨의 대부분을 차지할 때만** 후보로 본다.
         *    라벨에 query 말고 남는 글자가 "앱"/"app" 뿐이거나, query 가 라벨 길이의 3/4 이상이면 같은 앱으로 본다.
         *    "멜론" 이 "멜론티켓"(2/4), "쿠팡" 이 "쿠팡이츠"(2/4), "네이버" 가 "네이버 지도"(3/5)를 여는 일을 막는다.
         *    "카카오"→"카카오톡"(3/4)처럼 줄여 부르는 말은 계속 열린다.
         * 3. 후보가 하나일 때만 연다. 여럿이면 모호, 없으면 못 찾음. 불확실하면 열지 않는다.
         */
        internal fun pickByLabel(appName: String, query: String, apps: List<Pair<String, String>>): Resolution {
            val exact = apps.filter { it.second == query }.map { it.first }.distinct()
            if (exact.size == 1) return Resolution(exact.single())
            if (exact.size > 1) return Resolution(null, "이름이 같은 앱이 여러 개입니다: $appName")

            val partial = apps.filter { isCloseLabel(query, it.second) }.map { it.first }.distinct()
            return when {
                partial.size == 1 -> Resolution(partial.single())
                partial.size > 1 -> Resolution(null, "앱 이름이 모호합니다. 더 정확히 말해 주세요: $appName")
                else -> Resolution(null, "설치되지 않았거나 찾을 수 없는 앱입니다: $appName")
            }
        }

        private fun isCloseLabel(query: String, label: String): Boolean {
            if (!label.contains(query)) return false
            val rest = label.replaceFirst(query, "")
            return rest in GENERIC_LABEL_WORDS || query.length * 4 >= label.length * 3
        }

        /** 라벨에 붙어도 같은 앱으로 보는 말. */
        private val GENERIC_LABEL_WORDS = setOf("앱", "app", "어플")

        /** 공백을 없애고 소문자로. "유튜브 뮤직" 과 "유튜브뮤직" 을 같게 본다. */
        internal fun normalizeName(value: String): String =
            value.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)

        /** 고정 표에서만 찾는다. 컨텍스트가 없는 곳(계획 파서 등)에서 쓴다. 설치된 앱까지 찾으려면 [resolve]. */
        fun packageFor(value: String): String? = PACKAGE_NAMES[normalizeName(value)]

        /** 유튜브 영상 id 형식(영문·숫자·_·-, 6~20자). 그 밖의 문자는 주소에 넣지 않는다. */
        fun isVideoId(value: String): Boolean = VIDEO_ID.matches(value)

        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{6,20}")

        fun supportsDirectSearch(value: String): Boolean = packageFor(value) in setOf(
            NAVER_PACKAGE, GOOGLE_PACKAGE, YOUTUBE_PACKAGE, YOUTUBE_MUSIC_PACKAGE,
        )

        const val NAVER_PACKAGE = "com.nhn.android.search"
        const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val KAKAOTALK_PACKAGE = "com.kakao.talk"
        const val MELON_PACKAGE = "com.iloen.melon"
        const val COUPANG_PACKAGE = "com.coupang.mobile"
        const val SAMSUNG_MESSAGES_PACKAGE = "com.samsung.android.messaging"
    }
}
