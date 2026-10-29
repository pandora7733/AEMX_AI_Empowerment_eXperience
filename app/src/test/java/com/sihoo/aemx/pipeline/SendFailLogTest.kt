package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.fake.FakeAppResolver
import com.sihoo.aemx.pipeline.fake.FakePlanGenerator
import com.sihoo.aemx.pipeline.fake.FakeScreenObserver
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.ActionExecutor
import com.sihoo.aemx.pipeline.port.ExecutionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendFailLogTest {

    private class FailingSend : ActionExecutor {
        override fun isReady() = true
        override suspend fun execute(command: ActionExecutor.Command): ExecutionResult =
            ExecutionResult.Failed("서우진 님을 카카오톡에서 찾지 못했어요")
    }

    @Test
    fun `보내기 실패 로그에는 받는 사람 이름이 나가지 않는다`() = runTest {
        val plan = """{"app":"카카오톡","steps":[{"action":"send_message","target":"서우진","value":"안녕"}]}"""
        val logs = mutableListOf<String>()
        val orchestrator = PipelineOrchestrator(
            planner = FakePlanGenerator(listOf(plan)),
            executor = FailingSend(),
            screenObserver = FakeScreenObserver(ScreenSnapshot(packageName = "com.kakao.talk", className = "Main", texts = listOf("채팅"))),
            appResolver = FakeAppResolver(),
            scope = this,
            config = PipelineConfig(quietPeriodMs = 10),
            worker = UnconfinedTestDispatcher(testScheduler),
            logSink = { logs += it },
        )

        orchestrator.submit("서우진한테 안녕이라고 보내줘")
        advanceUntilIdle()

        assertTrue("로그가 나가야 한다", logs.isNotEmpty())
        assertTrue("이름이 로그에 있으면 안 된다: $logs", logs.none { it.contains("서우진") })
        assertTrue("실패 줄은 길이로만 남아야 한다", logs.any { it.startsWith("[ERROR] ***(") })
    }
}
