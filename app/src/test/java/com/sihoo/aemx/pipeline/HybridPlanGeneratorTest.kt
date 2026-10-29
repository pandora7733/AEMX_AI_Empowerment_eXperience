package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.CloudChatClient
import com.sihoo.aemx.pipeline.llm.CloudPlanGenerator
import com.sihoo.aemx.pipeline.llm.HybridPlanGenerator
import com.sihoo.aemx.pipeline.llm.RouteReport
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
import org.junit.Assert.assertTrue
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

    /** 처리 경로 보고를 모은다. 테스트마다 새로 만든다. */
    private val routes = mutableListOf<RouteReport>()

    private fun hybrid(
        local: PlanGenerator,
        cloud: () -> PlanGenerator?,
        cloudTimeoutMs: Long = HybridPlanGenerator.DEFAULT_CLOUD_TIMEOUT_MS,
    ) = HybridPlanGenerator(local, cloud, cloudTimeoutMs = cloudTimeoutMs, onRoute = { routes += it })

    @Test
    fun `어려운 발화는 기기 계획을 만들지 않고 클라우드를 먼저 쓴다`() = runBlocking {
        val cloudResult = success("유튜브", ActionType.OPEN_APP, ActionType.SEARCH, ActionType.PLAY_FIRST)
        val local = Fake { hardLocal }
        val cloud = Fake { cloudResult }

        val result = hybrid(local, { cloud }).generate(initial("유튜브에서 고양이 영상 틀어줘"))

        assertSame(cloudResult, result)
        assertEquals(0, local.calls)
        assertEquals(RouteReport.Via.CLOUD, routes.single().via)
    }

    @Test
    fun `재분석 요청도 어려운 발화면 클라우드를 먼저 쓴다`() = runBlocking {
        val cloudResult = success("유튜브", ActionType.PLAY_FIRST)
        val local = Fake { hardLocal }
        val replan = PlanRequest(utterance = "유튜브에서 고양이 영상 틀어줘", mode = PlanRequest.Mode.REPLAN)

        val result = hybrid(local, { Fake { cloudResult } }).generate(replan)

        assertSame(cloudResult, result)
        assertEquals(0, local.calls)
    }

    @Test
    fun `키가 없으면 클라우드를 부르지 않고 기기 계획을 쓴다`() = runBlocking {
        val local = Fake { hardLocal }
        val result = hybrid(local, { null }).generate(initial("유튜브에서 고양이 영상 틀어줘"))
        assertSame(hardLocal, result)
        assertEquals(RouteReport.Via.LOCAL, routes.single().via)
    }

    @Test
    fun `간단한 동작은 클라우드를 부르지 않는다`() = runBlocking {
        val simple = success("네이버", ActionType.OPEN_APP, ActionType.SEARCH)
        val cloud = Fake { error("부르면 안 된다") }

        val result = hybrid(Fake { simple }, { cloud }).generate(initial("네이버에서 서울 날씨 검색해줘"))

        assertSame(simple, result)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `메시지 보내기는 클라우드를 부르지 않는다`() = runBlocking {
        val message = success("카카오톡", ActionType.SEND_MESSAGE)
        val cloud = Fake { error("부르면 안 된다") }

        val result = hybrid(Fake { message }, { cloud }).generate(initial("엄마한테 안녕이라고 보내줘"))

        assertSame(message, result)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `클라우드가 실패하면 기기 계획으로 되돌리고 이유를 알린다`() = runBlocking {
        val local = Fake { hardLocal }
        val cloud = Fake { PlanResult.EngineError("네트워크 오류") }

        val result = hybrid(local, { cloud }).generate(initial("유튜브에서 고양이 영상 틀어줘"))

        assertSame(hardLocal, result)
        assertEquals(1, cloud.calls)
        assertEquals(1, local.calls)
        assertEquals(RouteReport.Via.LOCAL_AFTER_CLOUD_FAIL, routes.single().via)
        assertEquals("네트워크 오류", routes.single().cloudFailure)
    }

    @Test
    fun `클라우드가 예외를 던져도 기기 계획으로 되돌린다`() = runBlocking {
        val cloud = Fake { throw IllegalStateException("boom") }
        val result = hybrid(Fake { hardLocal }, { cloud }).generate(initial("유튜브에서 고양이 영상 틀어줘"))
        assertSame(hardLocal, result)
    }

    @Test
    fun `클라우드가 너무 느리면 기기 계획으로 되돌리고 시간 초과를 알린다`() = runBlocking {
        val cloud = Fake {
            delay(5_000)
            success("유튜브", ActionType.PLAY_FIRST)
        }
        val result = hybrid(Fake { hardLocal }, { cloud }, cloudTimeoutMs = 50)
            .generate(initial("유튜브에서 고양이 영상 틀어줘"))

        assertSame(hardLocal, result)
        assertEquals("시간 초과", routes.single().cloudFailure)
    }

    @Test
    fun `클라우드 계획이 개인정보 동작이면 쓰지 않고 기기 계획으로 되돌린다`() = runBlocking {
        val cloud = Fake { success("카카오톡", ActionType.SEND_MESSAGE) }

        val result = hybrid(Fake { hardLocal }, { cloud }).generate(initial("유튜브에서 고양이 영상 틀어줘"))

        assertSame(hardLocal, result)
        assertEquals("개인정보 계획", routes.single().cloudFailure)
    }

    @Test
    fun `발화는 쉬운데 기기 계획에 어려운 동작이 있으면 클라우드에 다시 묻는다`() = runBlocking {
        val cloudResult = success("유튜브", ActionType.OPEN_APP, ActionType.PLAY_FIRST)
        val local = Fake { hardLocal }
        val cloud = Fake { cloudResult }

        val result = hybrid(local, { cloud }).generate(initial("유튜브 열어줘"))

        assertSame(cloudResult, result)
        assertEquals(1, local.calls)
        assertEquals(1, cloud.calls)
        assertEquals(RouteReport.Via.CLOUD, routes.single().via)
    }

    @Test
    fun `발화는 쉬운데 기기 계획도 쉬우면 클라우드를 부르지 않는다`() = runBlocking {
        val simple = success("유튜브", ActionType.OPEN_APP)
        val cloud = Fake { error("부르면 안 된다") }

        val result = hybrid(Fake { simple }, { cloud }).generate(initial("유튜브 열어줘"))

        assertSame(simple, result)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `클라우드가 이미 실패했으면 기기 계획에 어려운 동작이 있어도 다시 묻지 않는다`() = runBlocking {
        val cloud = Fake { PlanResult.EngineError("네트워크 오류") }

        val result = hybrid(Fake { hardLocal }, { cloud }).generate(initial("유튜브 열어줘"))

        assertSame(hardLocal, result)
        assertEquals(1, cloud.calls)
    }

    @Test
    fun `기기 계획이 실패하면 그 결과를 그대로 돌려준다`() = runBlocking {
        val failed = PlanResult.EngineError("모델 없음")
        val cloud = Fake { error("부르면 안 된다") }

        val result = hybrid(Fake { failed }, { cloud }).generate(initial("네이버에서 서울 날씨 검색해줘"))

        assertSame(failed, result)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `형식 재요청은 기기에서만 처리한다`() = runBlocking {
        val cloud = Fake { error("부르면 안 된다") }
        val repair = PlanRequest(utterance = "유튜브에서 고양이 영상 틀어줘", mode = PlanRequest.Mode.REPAIR_JSON)

        val result = hybrid(Fake { hardLocal }, { cloud }).generate(repair)

        assertSame(hardLocal, result)
        assertEquals(0, cloud.calls)
        assertTrue(routes.isEmpty())
    }

    @Test
    fun `클라우드가 답한 모델 이름을 처리 경로에 담는다`() = runBlocking {
        val client = object : CloudChatClient {
            override suspend fun complete(system: String, user: String): String =
                """{"app":"유튜브","steps":[{"action":"open_app","target":"유튜브"},{"action":"search","target":"고양이 영상"},{"action":"play_first","target":"첫 번째 동영상"}]}"""

            override val lastModel: String? get() = "qwen/qwen3.8-27b"
        }

        hybrid(Fake { hardLocal }, { CloudPlanGenerator(client, "system") })
            .generate(initial("유튜브에서 고양이 영상 틀어줘"))

        assertEquals("qwen/qwen3.8-27b", routes.single().model)
        assertTrue(routes.single().summary().startsWith("처리: 클라우드 (qwen/qwen3.8-27b, "))
    }

    @Test
    fun `처리 경로 문장은 세 가지 모양으로 나온다`() {
        assertEquals(
            "처리: 클라우드 (qwen/qwen3.8-27b, 0.5초)",
            RouteReport(RouteReport.Via.CLOUD, "qwen/qwen3.8-27b", 500, null).summary(),
        )
        assertEquals("처리: 기기 (8.3초)", RouteReport(RouteReport.Via.LOCAL, null, 8_300, null).summary())
        assertEquals(
            "처리: 기기 (클라우드 실패: 시간 초과, 13.1초)",
            RouteReport(RouteReport.Via.LOCAL_AFTER_CLOUD_FAIL, null, 13_100, "시간 초과").summary(),
        )
    }
}
