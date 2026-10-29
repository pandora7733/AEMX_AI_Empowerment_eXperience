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
 * 키는 생성자로 받는다. 키·요청 주소·응답 본문은 로그에도 예외 메시지에도 남기지 않는다.
 * 보내는 것은 검색어와 개수뿐이다. 전체 시간은 [timeoutMs] 로 끊는다. 시간이 넘으면 소켓을 닫아 블로킹 read 를 깨운다.
 * 실패(키 없음, 한도, 네트워크, 형식 오류, 시간 초과)는 모두 null 이다. 부르는 쪽은 원래 계획으로 돌아간다.
 */
class YouTubeSearchClient(
    private val apiKey: String,
    private val quota: YouTubeQuota = YouTubeQuota.shared,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val endpoint: String = ENDPOINT,
    private val log: (String) -> Unit = {},
) {

    /** 검색어의 영상 id 를 검색 순서대로. 실패면 null, 결과가 없으면 빈 목록. */
    suspend fun search(query: String, maxResults: Int): List<String>? {
        if (apiKey.isBlank() || quota.isBlocked()) return null
        val started = System.nanoTime()
        val ids = withTimeoutOrNull(timeoutMs) { request(query, maxResults) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        log("검색어 ${query.length}자, 결과 ${ids?.size ?: "실패"}, ${elapsedMs}ms")
        return ids
    }

    private suspend fun request(query: String, maxResults: Int): List<String>? {
        val connection = open(query, maxResults) ?: return null
        return suspendCancellableCoroutine { cont ->
            // 취소되면 소켓을 닫아 막혀 있는 read 를 깨운다. 블록이 끝날 때까지 기다리지 않는다.
            cont.invokeOnCancellation { connection.disconnect() }
            CoroutineScope(Dispatchers.IO).launch {
                cont.resume(read(connection))
            }
        }
    }

    private fun open(query: String, maxResults: Int): HttpURLConnection? = try {
        val params = listOf(
            "part" to "snippet",
            "type" to "video",
            "maxResults" to maxResults.coerceIn(1, MAX_RESULTS).toString(),
            "q" to query,
            "regionCode" to "KR",
            "relevanceLanguage" to "ko",
            "key" to apiKey,
        )
        val encoded = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        (URL("$endpoint?$encoded").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
        }
    } catch (e: IOException) {
        null
    }

    private fun read(connection: HttpURLConnection): List<String>? = try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code in 200..299) {
            YouTubeSearchParser.videoIds(body)
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

        /** 검색 한 번의 상한 시간. 넘으면 원래 계획(화면 검색)으로 되돌린다. */
        const val DEFAULT_TIMEOUT_MS = 1_500L

        /** search.list 가 한 번에 주는 최대 개수. */
        const val MAX_RESULTS = 50
    }
}
