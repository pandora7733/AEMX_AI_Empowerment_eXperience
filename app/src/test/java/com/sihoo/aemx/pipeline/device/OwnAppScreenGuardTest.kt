package com.sihoo.aemx.pipeline.device

import com.sihoo.aemx.accessibility.PlanOutcome
import com.sihoo.aemx.pipeline.AppAvailability
import com.sihoo.aemx.pipeline.port.ActionExecutor.Command
import com.sihoo.aemx.pipeline.port.ExecutionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 맨 앞이 우리 앱 화면이면 스크롤·탭 같은 화면 조작은 실행하지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnAppScreenGuardTest {

    private class Bridge : PlanBridge {
        val submitted = mutableListOf<String>()
        override fun isConnected() = true
        override fun submit(json: String, announceCompletion: Boolean): Result<Unit> {
            submitted += json
            return Result.success(Unit)
        }
        override fun setObserver(callback: ((PlanOutcome) -> Unit)?) {}
        override fun cancel() {}
        override fun isRunning() = false
    }

    private fun TestScope.executor(bridge: Bridge, foreground: String?) = AccessibilityStepExecutor(
        bridge = bridge,
        foregroundPackage = { foreground },
        restoreObserver = { null },
        mainDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `맨 앞이 우리 앱이면 스크롤을 실행하지 않고 거절한다`() = runTest {
        val bridge = Bridge()

        val result = executor(bridge, foreground = "com.sihoo.aemx.debug").execute(Command.Scroll(Command.Direction.DOWN))

        assertEquals(ExecutionResult.Failed(AppAvailability.OWN_APP_SCREEN_MESSAGE), result)
        assertTrue("계획이 서비스에 넘어가면 안 된다", bridge.submitted.isEmpty())
    }

    @Test
    fun `맨 앞이 우리 앱이면 탭도 실행하지 않는다`() = runTest {
        val bridge = Bridge()

        val result = executor(bridge, foreground = "com.sihoo.aemx").execute(Command.Tap("검색창"))

        assertEquals(ExecutionResult.Failed(AppAvailability.OWN_APP_SCREEN_MESSAGE), result)
        assertTrue(bridge.submitted.isEmpty())
    }
}
