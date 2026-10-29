package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.CommandOutcome
import com.sihoo.aemx.pipeline.llm.CommandTiming
import com.sihoo.aemx.pipeline.llm.RouteReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandTimingTest {

    private val cloud = RouteReport(RouteReport.Via.CLOUD, "qwen/qwen3.8-27b", elapsedMs = 600, cloudFailure = null)

    @Test
    fun `말 끝부터 잰 성공 한 줄 형식`() {
        // 말 끝 1000ms, 계획 완료 1600ms, 실행 끝 7400ms
        val timing = CommandTiming(startMs = 1_000, fromSpeech = true)
        timing.planned(1_600, cloud)

        assertEquals(
            "명령 끝까지 6.4초 = 계획 0.6초(클라우드 qwen/qwen3.8-27b) + 실행 5.8초, 결과 성공 [말 끝부터]",
            timing.finish(7_400, CommandOutcome.SUCCESS),
        )
    }

    @Test
    fun `글 입력은 계획 시작부터 잰다고 표시하고 기기 경로를 쓴다`() {
        val local = RouteReport(RouteReport.Via.LOCAL, null, elapsedMs = 8_300, cloudFailure = null)
        val timing = CommandTiming(startMs = 0, fromSpeech = false)
        timing.planned(8_300, local)

        assertEquals(
            "명령 끝까지 8.3초 = 계획 8.3초(기기) + 실행 0.0초, 결과 거절 [글 입력, 계획 시작부터]",
            timing.finish(8_300, CommandOutcome.REFUSED),
        )
    }

    @Test
    fun `계획 전에 온 끝 신호는 무시한다`() {
        val timing = CommandTiming(startMs = 0, fromSpeech = true)

        assertNull(timing.finish(500, CommandOutcome.FAILED))

        timing.planned(1_000, cloud)
        assertEquals(
            "명령 끝까지 2.0초 = 계획 1.0초(클라우드 qwen/qwen3.8-27b) + 실행 1.0초, 결과 실패 [말 끝부터]",
            timing.finish(2_000, CommandOutcome.FAILED),
        )
    }

    @Test
    fun `끝 줄은 명령마다 한 번만 나온다`() {
        val timing = CommandTiming(startMs = 0, fromSpeech = true)
        timing.planned(100, cloud)

        assertEquals(true, timing.finish(200, CommandOutcome.SUCCESS) != null)
        assertNull(timing.finish(300, CommandOutcome.FAILED))
    }

    @Test
    fun `클라우드가 실패해 기기로 처리했으면 그 사정을 경로에 적는다`() {
        val fallback = RouteReport(RouteReport.Via.LOCAL_AFTER_CLOUD_FAIL, null, elapsedMs = 5_000, cloudFailure = "시간 초과")
        val timing = CommandTiming(startMs = 0, fromSpeech = true)
        timing.planned(5_000, fallback)

        assertEquals(
            "명령 끝까지 9.0초 = 계획 5.0초(기기, 클라우드 실패) + 실행 4.0초, 결과 성공 [말 끝부터]",
            timing.finish(9_000, CommandOutcome.SUCCESS),
        )
    }
}
