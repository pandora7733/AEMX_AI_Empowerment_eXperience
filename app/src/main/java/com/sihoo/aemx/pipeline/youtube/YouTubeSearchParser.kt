package com.sihoo.aemx.pipeline.youtube

import org.json.JSONObject

/** YouTube Data API search.list 응답을 읽는다. 키·URL 은 여기서도 다루지 않는다. */
object YouTubeSearchParser {

    /** 응답의 영상 id 를 검색 순서대로. 형식이 깨졌거나 항목이 없으면 빈 목록. */
    fun videoIds(body: String): List<String> = runCatching {
        val items = JSONObject(body).getJSONArray("items")
        buildList {
            for (index in 0 until items.length()) {
                val videoId = items.getJSONObject(index)
                    .optJSONObject("id")
                    ?.optString("videoId")
                    .orEmpty()
                if (videoId.isNotBlank()) add(videoId)
            }
        }
    }.getOrDefault(emptyList())

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
