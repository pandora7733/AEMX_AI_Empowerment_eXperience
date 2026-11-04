package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.fake.FakeAppResolver
import com.sihoo.aemx.pipeline.fake.FakePlanGenerator
import com.sihoo.aemx.pipeline.fake.FakeActionExecutor
import com.sihoo.aemx.pipeline.fake.FakeScreenObserver
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

/** 멈출 명령이 없을 때 중단 요청은 처리되지 않았다고 돌아온다(캡슐을 치우는 근거). */
@OptIn(ExperimentalCoroutinesApi::class)
class CancelWithoutRunTest {

    @Test fun `명령이 없는 상태의 중단 요청은 처리되지 않는다`() = runTest {
        val home = ScreenSnapshot(packageName = "com.android.launcher", className = "Home", texts = listOf("홈"))
        val observer = FakeScreenObserver(home)
        val orchestrator = PipelineOrchestrator(
            planner = FakePlanGenerator(emptyList()),
            executor = FakeActionExecutor(),
            screenObserver = observer,
            appResolver = FakeAppResolver(emptyMap()),
            scope = this,
            config = PipelineConfig(quietPeriodMs = 10),
            worker = UnconfinedTestDispatcher(testScheduler),
        )

        assertFalse(orchestrator.requestCancel(runId = "없는-명령"))
        assertFalse(orchestrator.requestCancel())
    }
}
