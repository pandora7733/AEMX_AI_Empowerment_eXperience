package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.CommandOutcome
import com.sihoo.aemx.pipeline.llm.CommandTiming
import com.sihoo.aemx.pipeline.llm.RouteReport
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandTimingApiTest {

    private val local = RouteReport(RouteReport.Via.LOCAL, null, elapsedMs = 700, cloudFailure = null)

    @Test
    fun `유튜브 API 로 찾은 경우 계획 줄에 걸린 시간을 붙인다`() {
        val timing = CommandTiming(startMs = 0, fromSpeech = true)
        timing.planned(700, local)
        timing.youtubeApi(elapsedMs = 600, hit = true)

        assertEquals(
            "명령 끝까지 2.3초 = 계획 0.7초(기기, 유튜브 API 0.6초) + 실행 1.6초, 결과 성공 [말 끝부터]",
            timing.finish(2_300, CommandOutcome.SUCCESS),
        )
    }

    @Test
    fun `유튜브 API 가 실패했으면 실패로 적는다`() {
        val timing = CommandTiming(startMs = 0, fromSpeech = true)
        timing.planned(700, local)
        timing.youtubeApi(elapsedMs = 1_500, hit = false)

        assertEquals(
            "명령 끝까지 3.0초 = 계획 0.7초(기기, 유튜브 API 실패 1.5초) + 실행 2.3초, 결과 성공 [말 끝부터]",
            timing.finish(3_000, CommandOutcome.SUCCESS),
        )
    }
}
