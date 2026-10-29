package com.sihoo.aemx.pipeline.llm

/** 명령 하나의 끝 결과. 로그 한 줄의 "결과" 자리에 들어간다. */
enum class CommandOutcome(val label: String) {
    SUCCESS("성공"),
    FAILED("실패"),
    STOPPED("중단"),
    REFUSED("거절"),
}

/**
 * 명령 하나의 단계별 시각을 모은다. 말 끝 → 계획 완료 → 실행 끝. 시각은 부르는 쪽이 넣는다(테스트에서는 값을 직접 준다).
 *
 * [startMs] 는 말 끝 판정 시각이거나, 글 입력이면 계획 시작 시각이다([fromSpeech] 로 구분).
 * 계획 완료 시각은 처음 나온 계획 기준이다. 재분석에 든 시간은 실행 쪽에 들어간다.
 */
class CommandTiming(
    private val startMs: Long,
    private val fromSpeech: Boolean,
) {
    private var planDoneMs: Long? = null
    private var route: RouteReport? = null
    private var apiNote: String? = null
    private var finished = false

    @Synchronized
    fun planned(atMs: Long, report: RouteReport) {
        if (planDoneMs != null) return
        planDoneMs = atMs
        route = report
    }

    /** 유튜브 API 검색을 부른 기록. 걸린 시간과 결과가 있었는지를 계획 줄에 붙인다. */
    @Synchronized
    fun youtubeApi(elapsedMs: Long, hit: Boolean) {
        apiNote = if (hit) "유튜브 API ${seconds(elapsedMs)}" else "유튜브 API 실패 ${seconds(elapsedMs)}"
    }

    /**
     * 계획이 끝난 뒤의 끝 신호만 받는다. 계획 전에 온 신호는 지난 명령의 잔여 상태일 수 있어서 무시한다.
     * 한 명령에 한 번만 줄을 돌려주고, 그 뒤의 신호에는 null 을 돌려준다.
     */
    @Synchronized
    fun finish(atMs: Long, outcome: CommandOutcome): String? {
        val planDone = planDoneMs ?: return null
        if (finished) return null
        finished = true
        return formatTimingLine(
            totalMs = atMs - startMs,
            planMs = planDone - startMs,
            execMs = atMs - planDone,
            route = route,
            outcome = outcome,
            fromSpeech = fromSpeech,
            apiNote = apiNote,
        )
    }
}

/**
 * 한 줄 형식. 예: "명령 끝까지 6.4초 = 계획 0.6초(클라우드 qwen/qwen3.8-27b) + 실행 5.8초, 결과 성공 [말 끝부터]"
 * 글 입력이면 말 끝 시각이 없으므로 계획 시작부터 잰다고 뒤에 붙인다.
 */
internal fun formatTimingLine(
    totalMs: Long,
    planMs: Long,
    execMs: Long,
    route: RouteReport?,
    outcome: CommandOutcome,
    fromSpeech: Boolean,
    apiNote: String? = null,
): String {
    val basis = if (fromSpeech) "말 끝부터" else "글 입력, 계획 시작부터"
    val label = routeLabel(route) + (apiNote?.let { ", $it" } ?: "")
    return "명령 끝까지 ${seconds(totalMs)} = 계획 ${seconds(planMs)}($label)" +
        " + 실행 ${seconds(execMs)}, 결과 ${outcome.label} [$basis]"
}

internal fun routeLabel(report: RouteReport?): String {
    if (report == null) return "경로 미상"
    return when (report.via) {
        RouteReport.Via.CLOUD -> "클라우드 ${report.model ?: "모델 미상"}"
        RouteReport.Via.LOCAL -> "기기"
        RouteReport.Via.LOCAL_AFTER_CLOUD_FAIL -> "기기, 클라우드 실패"
    }
}

private fun seconds(ms: Long): String = "%.1f초".format(ms.coerceAtLeast(0) / 1000.0)
