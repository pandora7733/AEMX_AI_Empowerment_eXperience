package com.sihoo.aemx.pipeline.youtube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * YouTube Data API 로 검색 결과의 영상 id 를 가져온다. 라이브러리 없이 HttpURLConnection 만 쓴다.
 *
 * 한 번의 검색은 두 번 부른다. search.list 로 찾고(제목에 #shorts 가 있으면 뺀다), 남은 영상의 길이를
 * videos.list 로 받아 1분 이하는 뺀다(쇼츠). 두 번 합쳐 [timeoutMs] 안에 끝내며, 넘으면 소켓을 닫는다.
 * 유닛은 [YouTubeQuota] 에서 떼고, 예산을 넘으면 부르지 않는다.
 *
 * 키는 생성자로 받는다. 키·요청 주소·응답 본문은 로그에도 예외 메시지에도 남기지 않는다.
 * 실패(키 없음, 한도, 네트워크, 형식 오류, 시간 초과)는 모두 null 이다. 부르는 쪽은 원래 계획으로 돌아간다.
 */
class YouTubeSearchClient(
    private val apiKey: String,
    private val quota: YouTubeQuota = YouTubeQuota.shared,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val endpoint: String = ENDPOINT,
    private val videosEndpoint: String = VIDEOS_ENDPOINT,
    private val log: (String) -> Unit = {},
) {

    /**
     * 검색어의 영상 id 를 검색 순서대로. 쇼츠는 뺀다. 실패면 null, 쇼츠를 뺀 뒤 남은 것이 없으면 빈 목록.
     */
    suspend fun search(query: String, maxResults: Int): List<String>? {
        if (apiKey.isBlank() || quota.isBlocked()) return null
        val started = System.nanoTime()
        val ids = withTimeoutOrNull(timeoutMs) { searchWithoutShorts(query, maxResults) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        log("검색어 ${query.length}자, 결과 ${ids?.size ?: "실패"}, ${elapsedMs}ms")
        return ids
    }

    private suspend fun searchWithoutShorts(query: String, maxResults: Int): List<String>? {
        if (!quota.reserve(YouTubeQuota.SEARCH_UNITS)) return null
        val body = fetch(searchUrl(query, maxResults)) ?: return null
        val candidates = YouTubeSearchParser.videoItems(body).filterNot { YouTubeSearchParser.isShortTitle(it.title) }
        if (candidates.isEmpty()) return emptyList()

        if (!quota.reserve(YouTubeQuota.VIDEOS_UNITS)) return null
        val durations = YouTubeSearchParser.durations(fetch(videosUrl(candidates.map { it.id })) ?: return null)
        // 길이를 못 받은 영상은 쇼츠인지 알 수 없으니 남긴다. 1분 이하가 확실한 것만 뺀다.
        return candidates.map { it.id }.filter {
            (durations[it] ?: Int.MAX_VALUE) > YouTubeSearchParser.SHORT_MAX_SECONDS
        }
    }

    /** 주소로 한 번 부르고 본문을 돌려준다. 실패면 null. 한도 초과면 오늘 자정까지 기억한다. */
    private suspend fun fetch(url: String): String? {
        val connection = open(url) ?: return null
        return suspendCancellableCoroutine { cont ->
            // 취소되면 소켓을 닫아 막혀 있는 read 를 깨운다. 블록이 끝날 때까지 기다리지 않는다.
            cont.invokeOnCancellation { connection.disconnect() }
            CoroutineScope(Dispatchers.IO).launch {
                cont.resume(read(connection))
            }
        }
    }

    private fun searchUrl(query: String, maxResults: Int): String = withParams(
        endpoint,
        listOf(
            "part" to "snippet",
            "type" to "video",
            "maxResults" to maxResults.coerceIn(1, MAX_RESULTS).toString(),
            "q" to query,
            "regionCode" to "KR",
            "relevanceLanguage" to "ko",
            "key" to apiKey,
        ),
    )

    private fun videosUrl(ids: List<String>): String = withParams(
        videosEndpoint,
        listOf(
            "part" to "contentDetails",
            "id" to ids.joinToString(","),
            "key" to apiKey,
        ),
    )

    private fun withParams(base: String, params: List<Pair<String, String>>): String =
        base + "?" + params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun open(url: String): HttpURLConnection? = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
        }
    } catch (e: IOException) {
        null
    }

    private fun read(connection: HttpURLConnection): String? = try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code in 200..299) {
            body
        } else {
            // 본문은 남기지 않는다. 이유 코드만 보고 한도면 오늘은 더 부르지 않는다.
            if (code == 403 && YouTubeSearchParser.isQuotaReason(YouTubeSearchParser.errorReason(body))) {
                quota.blockUntilPacificMidnight()
            }
            log("HTTP $code")
            null
        }
    } catch (e: IOException) {
        log("네트워크 오류 (${e::class.java.simpleName})")
        null
    } finally {
        connection.disconnect()
    }

    companion object {
        const val ENDPOINT = "https://www.googleapis.com/youtube/v3/search"
        const val VIDEOS_ENDPOINT = "https://www.googleapis.com/youtube/v3/videos"

        /** 검색과 길이 조회를 합친 상한 시간. 넘으면 원래 계획(화면 검색)으로 되돌린다. */
        const val DEFAULT_TIMEOUT_MS = 1_500L

        /** search.list 가 한 번에 주는 최대 개수. */
        const val MAX_RESULTS = 50
    }
}
