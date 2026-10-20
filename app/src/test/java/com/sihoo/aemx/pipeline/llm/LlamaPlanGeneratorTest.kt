package com.sihoo.aemx.pipeline.llm

import com.arm.aichat.InferenceEngine
import com.sihoo.aemx.pipeline.port.PlanRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LlamaPlanGeneratorTest {

    /** 호출 순서만 기록하는 가짜 엔진. */
    private class FakeEngine : InferenceEngine {
        val calls = mutableListOf<String>()
        var failReset = false

        override val state: StateFlow<InferenceEngine.State> =
            MutableStateFlow(InferenceEngine.State.ModelReady)

        override suspend fun loadModel(pathToModel: String) {}
        override suspend fun setSystemPrompt(systemPrompt: String) {}
        override suspend fun resetConversation() {
            calls += "reset"
            if (failReset) throw IllegalStateException("리셋 실패")
        }
        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> {
            calls += "send"
            return flowOf("""{"app":"네이버","steps":[{"id":0,"action":"open_app","target":"네이버"}]}""")
        }
        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String = ""
        override fun cleanUp() {}
        override fun destroy() {}
    }

    private fun generator(engine: FakeEngine) =
        LlamaPlanGenerator(RuntimeEnvironment.getApplication(), { engine }).also { it.prepared = true }

    @Test
    fun `새 명령과 재분석은 요청 전에 대화 기록을 비운다`() = runTest {
        for (mode in listOf(PlanRequest.Mode.INITIAL, PlanRequest.Mode.REPLAN)) {
            val engine = FakeEngine()
            generator(engine).generate(PlanRequest("네이버 열어줘", mode))
            assertEquals(listOf("reset", "send"), engine.calls)
        }
    }

    @Test
    fun `JSON 복구 요청은 기록을 비우지 않는다`() = runTest {
        val engine = FakeEngine()
        generator(engine).generate(PlanRequest("네이버 열어줘", PlanRequest.Mode.REPAIR_JSON, parseError = "x"))
        assertEquals(listOf("send"), engine.calls)
    }

    @Test
    fun `리셋이 실패하면 프롬프트를 보내지 않고 엔진 오류로 돌려준다`() = runTest {
        val engine = FakeEngine().apply { failReset = true }
        val result = generator(engine).generate(PlanRequest("네이버 열어줘", PlanRequest.Mode.INITIAL))
        assertEquals(listOf("reset"), engine.calls)
        assertEquals(true, result is com.sihoo.aemx.pipeline.port.PlanResult.EngineError)
    }
}
