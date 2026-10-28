package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.HybridPlanGenerator
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import com.sihoo.aemx.pipeline.port.PlanGenerator
import com.sihoo.aemx.pipeline.port.PlanRequest
import com.sihoo.aemx.pipeline.port.PlanResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HybridPlanGeneratorTest {

    private class Fake(private val result: suspend () -> PlanResult) : PlanGenerator {
        var calls = 0
        override fun isReady() = true
        override suspend fun generate(request: PlanRequest): PlanResult {
            calls++
            return result()
        }
    }

    private fun success(app: String, vararg actions: ActionType) = PlanResult.Success(
        ActionPlan(app, actions.mapIndexed { i, a -> ActionStep(i, a, "x") }),
        raw = "{}",
    )

    private fun initial(text: String) = PlanRequest(utterance = text, mode = PlanRequest.Mode.INITIAL)

    private val hardLocal = success("유튜브", ActionType.OPEN_APP, ActionType.PLAY_FIRST)

    @Test
    fun `어려운 동작이면 클라우드 계획을 쓴다`() = runBlocking {
        val cloudResult = success("유튜브", ActionType.OPEN_APP, ActionType.SEARCH, ActionType.PLAY_FIRST)
        val local = Fake { hardLocal }
        val cloud = Fake { cloudResult }

        val result = HybridPlanGenerator(local, { cloud }).generate(initial("유튜브에서 고양이 영상 틀어줘"))

        assertSame(cloudResult, result)
    }

    @Test
    fun `키가 없으면 클라우드를 부르지 않고 로컬 계획을 쓴다`() = runBlocking {
        val local = Fake { hardLocal }
        val result = HybridPlanGenerator(local, { null }).generate(initial("유튜브에서 고양이 영상 틀어줘"))
        assertSame(hardLocal, result)
    }

    @Test
    fun `간단한 동작은 클라우드를 부르지 않는다`() = runBlocking {
        val simple = success("네이버", ActionType.OPEN_APP, ActionType.SEARCH)
        val cloud = Fake { error("부르면 안 된다") }

        val result = HybridPlanGenerator(Fake { simple }, { cloud }).generate(initial("네이버에서 서울 날씨 검색해줘"))

        assertSame(simple, result)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `메시지 보내기는 클라우드를 부르지 않는다`() = runBlocking {
        val message = success("카카오톡", ActionType.SEND_MESSAGE)
        val cloud = Fake { error("부르면 안 된다") }

        val result = HybridPlanGenerator(Fake { message }, { cloud }).generate(initial("엄마한테 안녕이라고 보내줘"))

        assertSame(message, result)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `클라우드가 실패하면 로컬 계획으로 되돌린다`() = runBlocking {
        val cloud = Fake { PlanResult.EngineError("네트워크 오류") }
        val result = HybridPlanGenerator(Fake { hardLocal }, { cloud }).generate(initial("유튜브에서 고양이 영상 틀어줘"))
        assertSame(hardLocal, result)
    }

    @Test
    fun `클라우드가 예외를 던져도 로컬 계획으로 되돌린다`() = runBlocking {
        val cloud = Fake { throw IllegalStateException("boom") }
        val result = HybridPlanGenerator(Fake { hardLocal }, { cloud }).generate(initial("유튜브에서 고양이 영상 틀어줘"))
        assertSame(hardLocal, result)
    }

    @Test
    fun `클라우드가 너무 느리면 로컬 계획으로 되돌린다`() = runBlocking {
        val cloud = Fake {
            delay(5_000)
            success("유튜브", ActionType.PLAY_FIRST)
        }
        val result = HybridPlanGenerator(Fake { hardLocal }, { cloud }, cloudTimeoutMs = 50)
            .generate(initial("유튜브에서 고양이 영상 틀어줘"))
        assertSame(hardLocal, result)
    }

    @Test
    fun `로컬이 실패하면 그 결과를 그대로 돌려준다`() = runBlocking {
        val failed = PlanResult.EngineError("모델 없음")
        val cloud = Fake { error("부르면 안 된다") }
        val result = HybridPlanGenerator(Fake { failed }, { cloud }).generate(initial("유튜브에서 고양이 영상 틀어줘"))
        assertSame(failed, result)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `형식 재요청은 로컬에서만 처리한다`() = runBlocking {
        val cloud = Fake { error("부르면 안 된다") }
        val repair = PlanRequest(utterance = "유튜브에서 고양이 영상 틀어줘", mode = PlanRequest.Mode.REPAIR_JSON)
        val result = HybridPlanGenerator(Fake { hardLocal }, { cloud }).generate(repair)
        assertSame(hardLocal, result)
        assertEquals(0, cloud.calls)
    }
}
