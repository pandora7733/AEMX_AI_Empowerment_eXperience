package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.fake.FakeAppResolver
import com.sihoo.aemx.pipeline.fake.FakePlanGenerator
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.ActionExecutor
import com.sihoo.aemx.pipeline.port.ExecutionResult
import com.sihoo.aemx.pipeline.port.ScreenChange
import com.sihoo.aemx.pipeline.port.ScreenObserver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 검색 결과 화면 확인: 옛 결과 화면을 검색 성공으로 보지 않고, 재생은 이번 검색어가 보일 때만 누른다.
 *
 * 화면은 [SequenceObserver] 가 호출 순서대로 준다. 오케스트레이터가 화면을 읽는 횟수에 따라 결과가
 * 달라지므로, 마지막 화면이 계속 나온다고 가정한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchTermGuardTest {

    /** 읽을 때마다 다음 화면을 준다. 목록이 끝나면 마지막 화면이 계속 나온다. 변화 스트림은 비어 있다. */
    private class SequenceObserver(private val shots: List<ScreenSnapshot>) : ScreenObserver {
        private var next = 0
        override fun isReady() = true
        override val changes: Flow<ScreenChange> = emptyFlow()
        override fun capture(): ScreenSnapshot {
            val shot = shots[minOf(next, shots.lastIndex)]
            next++
            return shot
        }
    }

    private class RecordingExecutor : ActionExecutor {
        val performed = mutableListOf<ActionExecutor.Command>()
        override fun isReady() = true
        override suspend fun execute(command: ActionExecutor.Command): ExecutionResult {
            performed += command
            return ExecutionResult.Performed()
        }
    }

    private fun snapshot(vararg texts: String) =
        ScreenSnapshot(packageName = YOUTUBE, className = "Results", texts = texts.toList())

    private val home = ScreenSnapshot(packageName = "com.android.launcher", className = "Home", texts = listOf("홈"))

    /** [FakeAppResolver] 가 유튜브를 주는 패키지. 검색 확인의 앱 조건이 여기에 맞아야 한다. */
    private val YOUTUBE = "com.example.fake.youtube"
    private val oldResults = snapshot("고양이 영상 - YouTube", "고양이 영상 3분 12초")
    private val newResults = snapshot("손흥민 하이라이트 - YouTube", "손흥민 하이라이트 3분")

    private val searchAndPlay =
        """{"app":"유튜브","steps":[{"action":"search","target":"손흥민 하이라이트"},{"action":"play_first","target":"세 번째 영상"}]}"""

    private fun kotlinx.coroutines.test.TestScope.orchestrator(
        planner: FakePlanGenerator,
        executor: RecordingExecutor,
        observer: ScreenObserver,
    ) = PipelineOrchestrator(
        planner = planner,
        executor = executor,
        screenObserver = observer,
        appResolver = FakeAppResolver(),
        scope = this,
        config = PipelineConfig(quietPeriodMs = 10),
        worker = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `옛 결과 화면이면 검색 단계가 성공하지 않고 재분석으로 간다`() = runTest {
        val planner = FakePlanGenerator(listOf(searchAndPlay, searchAndPlay))
        val executor = RecordingExecutor()
        val observer = SequenceObserver(listOf(home, oldResults))

        orchestrator(planner, executor, observer).submit("유튜브에서 손흥민 하이라이트 세 번째 영상 틀어줘")
        advanceUntilIdle()

        assertTrue("검색 실패를 재분석으로 넘겨야 한다", planner.requests.size >= 2)
        assertTrue(executor.performed.none { it is ActionExecutor.Command.PlayFirst || it is ActionExecutor.Command.PlayNth })
    }

    @Test
    fun `검색이 끝난 뒤 화면이 옛 결과로 바뀌었으면 재생하지 않는다`() = runTest {
        val planner = FakePlanGenerator(listOf(searchAndPlay, searchAndPlay))
        val executor = RecordingExecutor()
        // 검색 확인 때는 새 결과가 보이고, 재생 전에는 옛 결과가 보인다.
        val observer = SequenceObserver(listOf(home, newResults, oldResults))

        orchestrator(planner, executor, observer).submit("유튜브에서 손흥민 하이라이트 세 번째 영상 틀어줘")
        advanceUntilIdle()

        // 재생이 막히면 재분석으로 가므로 검색을 다시 할 수 있다. 중요한 것은 재생이 한 번도 눌리지 않는 것이다.
        assertTrue(executor.performed.any { it is ActionExecutor.Command.Search })
        assertTrue(
            "재생 전에 검색어가 안 보이면 누르지 않아야 한다",
            executor.performed.none { it is ActionExecutor.Command.PlayFirst || it is ActionExecutor.Command.PlayNth },
        )
    }

    @Test
    fun `검색어가 보이면 재생 단계가 실행된다`() = runTest {
        val planner = FakePlanGenerator(listOf(searchAndPlay))
        val executor = RecordingExecutor()
        val observer = SequenceObserver(listOf(home, newResults))

        orchestrator(planner, executor, observer).submit("유튜브에서 손흥민 하이라이트 세 번째 영상 틀어줘")
        advanceUntilIdle()

        assertEquals(1, executor.performed.count { it is ActionExecutor.Command.Search })
        assertEquals(
            1,
            executor.performed.count { it is ActionExecutor.Command.PlayFirst || it is ActionExecutor.Command.PlayNth },
        )
    }
}
