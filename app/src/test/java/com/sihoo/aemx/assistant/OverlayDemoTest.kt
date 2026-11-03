package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.PipelineEvent
import com.sihoo.aemx.pipeline.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayDemoTest {

    private fun scene(name: String): List<PipelineEvent>? {
        var sequence = 0L
        return OverlayDemo.events(name, "run-1") { ++sequence }
    }

    @Test fun `모르는 장면 이름이면 아무 사건도 만들지 않는다`() {
        assertNull(scene("unknown"))
    }

    @Test fun `실행 중 장면은 두 번째 단계가 진행 중이다`() {
        val events = scene(OverlayDemo.EXECUTING)!!
        val last = events.last() as PipelineEvent.StepChanged

        assertEquals("s2", last.stepId)
        assertEquals(StepStatus.InProgress, last.status)
    }

    @Test fun `다시 계획 중 장면은 재계획 사건으로 끝난다`() {
        assertTrue(scene(OverlayDemo.REPLANNING)!!.last() is PipelineEvent.Replanning)
    }

    @Test fun `직접 처리 장면은 직접 처리 사건으로 끝난다`() {
        assertTrue(scene(OverlayDemo.NEEDS_HUMAN)!!.last() is PipelineEvent.NeedsUser)
    }

    @Test fun `성공 장면은 모든 단계를 끝내고 결과를 확인한다`() {
        val events = scene(OverlayDemo.SUCCESS)!!
        val done = events.filterIsInstance<PipelineEvent.StepChanged>().map { it.status }

        assertEquals(listOf(StepStatus.Completed, StepStatus.Completed, StepStatus.Completed), done)
        assertTrue(events.last() is PipelineEvent.ResultVerified)
    }

    @Test fun `사건 순서 번호는 장면 안에서 늘어난다`() {
        var sequence = 0L
        val events = OverlayDemo.events(OverlayDemo.REPLANNING, "run-1") { ++sequence }!!

        val numbers = events.map { it.sequence }
        assertEquals(numbers.sorted(), numbers)
        assertEquals(numbers.size, numbers.toSet().size)
    }
}
