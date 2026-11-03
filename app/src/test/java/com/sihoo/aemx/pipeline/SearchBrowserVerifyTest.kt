package com.sihoo.aemx.pipeline

import com.sihoo.aemx.accessibility.SearchCheck
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 구글 검색을 브라우저로 열었을 때: 패키지 대신 검색어가 화면에 보여야 성공이다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchBrowserVerifyTest {

    @Test
    fun `브라우저 검색은 검색어가 보여야만 받아들인다`() {
        assertTrue(SearchCheck.accept(openedInBrowser = true, packageMatches = false, queryVisible = true))
        assertFalse(SearchCheck.accept(openedInBrowser = true, packageMatches = false, queryVisible = false))
    }

    @Test
    fun `앱 안 검색은 앱 패키지와 검색어가 모두 있어야 받아들인다`() {
        assertTrue(SearchCheck.accept(openedInBrowser = false, packageMatches = true, queryVisible = true))
        assertFalse(SearchCheck.accept(openedInBrowser = false, packageMatches = true, queryVisible = false))
        assertFalse(SearchCheck.accept(openedInBrowser = false, packageMatches = false, queryVisible = true))
    }

    /** 실행기가 브라우저로 열었다고 알리고, 화면을 미리 정해진 대로 보여 주는 가짜. */
    private class BrowserSearch(private val observer: FakeScreenObserver, private val page: ScreenSnapshot) : ActionExecutor {
        val performed = mutableListOf<ActionExecutor.Command>()
        override fun isReady() = true
        override suspend fun execute(command: ActionExecutor.Command): ExecutionResult {
            performed += command
            observer.advanceTo(page)
            return ExecutionResult.Performed(SearchCheck.BROWSER_EVIDENCE)
        }
    }

    private val plan = """{"app":"구글","steps":[{"action":"search","target":"페이커"}]}"""

    @Test
    fun `브라우저 결과에 검색어가 보이면 성공으로 끝낸다`() = runTest {
        val home = ScreenSnapshot(packageName = "com.android.launcher", className = "Home", texts = listOf("홈"))
        val observer = FakeScreenObserver(home)
        val page = ScreenSnapshot(packageName = "com.android.chrome", className = "Page", texts = listOf("페이커 - Google 검색"))
        val executor = BrowserSearch(observer, page)
        val planner = FakePlanGenerator(listOf(plan, plan))
        val orchestrator = PipelineOrchestrator(
            planner = planner,
            executor = executor,
            screenObserver = observer,
            appResolver = FakeAppResolver(mapOf("구글" to "com.google.android.googlequicksearchbox")),
            scope = this,
            config = PipelineConfig(quietPeriodMs = 10),
            worker = UnconfinedTestDispatcher(testScheduler),
        )

        orchestrator.submit("구글에서 페이커 검색해줘")
        advanceUntilIdle()

        assertEquals("검색은 한 번이면 된다", 1, planner.requests.size)
        assertEquals(PipelineState.Finished("계획의 모든 단계를 끝냈다"), orchestrator.state.value)
    }

    @Test
    fun `브라우저 결과에 검색어가 없으면 실패로 안내하고 다시 계획한다`() = runTest {
        val home = ScreenSnapshot(packageName = "com.android.launcher", className = "Home", texts = listOf("홈"))
        val observer = FakeScreenObserver(home)
        val page = ScreenSnapshot(packageName = "com.android.chrome", className = "Page", texts = listOf("전혀 다른 글"))
        val executor = BrowserSearch(observer, page)
        val planner = FakePlanGenerator(listOf(plan, plan))
        val orchestrator = PipelineOrchestrator(
            planner = planner,
            executor = executor,
            screenObserver = observer,
            appResolver = FakeAppResolver(mapOf("구글" to "com.google.android.googlequicksearchbox")),
            scope = this,
            config = PipelineConfig(quietPeriodMs = 10),
            worker = UnconfinedTestDispatcher(testScheduler),
        )

        orchestrator.submit("구글에서 페이커 검색해줘")
        advanceUntilIdle()

        assertTrue("검색어가 없으면 성공하지 않는다", planner.requests.size >= 2)
        assertFalse(orchestrator.state.value is PipelineState.Finished)
    }
}
