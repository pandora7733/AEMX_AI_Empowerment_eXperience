package com.sihoo.aemx.pipeline.youtube

import org.json.JSONObject

/** YouTube Data API 응답을 읽는다. 키·URL·본문은 여기서도 로그에 남기지 않는다. */
object YouTubeSearchParser {

    /** 응답의 영상 id 를 검색 순서대로. 형식이 깨졌거나 항목이 없으면 빈 목록. */
    fun videoIds(body: String): List<String> = videoItems(body).map { it.id }

    /** search.list 항목 하나: 영상 id 와 제목. 제목이 없으면 빈 문자열. */
    data class Item(val id: String, val title: String)

    /** search.list 응답의 항목을 검색 순서대로. */
    fun videoItems(body: String): List<Item> = runCatching {
        val items = JSONObject(body).getJSONArray("items")
        buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val videoId = item.optJSONObject("id")?.optString("videoId").orEmpty()
                if (videoId.isBlank()) continue
                val title = item.optJSONObject("snippet")?.optString("title").orEmpty()
                add(Item(videoId, title))
            }
        }
    }.getOrDefault(emptyList())

    /** videos.list 응답에서 영상 id 별 길이(초). 길이를 못 읽은 항목은 빠진다. */
    fun durations(body: String): Map<String, Int> = runCatching {
        val items = JSONObject(body).getJSONArray("items")
        buildMap {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val id = item.optString("id")
                val seconds = item.optJSONObject("contentDetails")
                    ?.optString("duration")
                    ?.let(::parseDurationSeconds)
                if (id.isNotBlank() && seconds != null) put(id, seconds)
            }
        }
    }.getOrDefault(emptyMap())

    private val DURATION = Regex("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$")

    /** ISO 8601 기간(PT1M2S)을 초로. 시·분·초 꼴이 아니면 null. */
    fun parseDurationSeconds(iso: String): Int? {
        val match = DURATION.find(iso.trim()) ?: return null
        val (h, m, s) = match.destructured
        if (h.isEmpty() && m.isEmpty() && s.isEmpty()) return null
        return (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
    }

    /** 제목에 #shorts 가 있으면 쇼츠로 본다(대소문자 무시). */
    fun isShortTitle(title: String): Boolean = title.contains("#shorts", ignoreCase = true)

    /** 쇼츠로 볼 길이(초). 이보다 짧거나 같으면 쇼츠다. */
    const val SHORT_MAX_SECONDS = 60

    /** 오류 응답의 이유(error.errors[0].reason). 없으면 null. */
    fun errorReason(body: String): String? = runCatching {
        JSONObject(body)
            .getJSONObject("error")
            .getJSONArray("errors")
            .getJSONObject(0)
            .getString("reason")
    }.getOrNull()

    /** 하루 한도를 다 쓴 오류인가. */
    fun isQuotaReason(reason: String?): Boolean = reason == "quotaExceeded" || reason == "dailyLimitExceeded"
}
