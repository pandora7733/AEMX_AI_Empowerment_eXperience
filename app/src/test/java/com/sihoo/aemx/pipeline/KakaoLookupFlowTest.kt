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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 카톡 받는 사람을 카톡 검색 결과에서 고르는 흐름(가짜 실행기). 검색 결과가 0명이면 보내지 않고, 1명이면 미리 고르고 확인을 강제하고,
 * 여럿이면 확인 화면에서 고르게 하며, 확인이 검색 결과 밖의 이름을 돌려주면 보내지 않는다. 연락처에 정확히 있으면 검색하지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KakaoLookupFlowTest {

    /** 보내기 명령을 기록하고 실패로 끝낸다(검증 단계가 없어도 흐름을 끝낼 수 있게). */
    private class RecordingExecutor : ActionExecutor {
        val sent = mutableListOf<String>()
        override fun isReady() = true
        override suspend fun execute(command: ActionExecutor.Command): ExecutionResult {
            if (command is ActionExecutor.Command.SendMessage) sent += command.target
            return ExecutionResult.Failed("테스트 종료")
        }
    }

    private val plan = """{"app":"카카오톡","steps":[{"action":"send_message","target":"정우","value":"안녕"}]}"""

    private fun orchestrator(
        scope: kotlinx.coroutines.CoroutineScope,
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        executor: RecordingExecutor,
        lookup: suspend (String) -> RecipientLookup?,
        hasExactContact: (String) -> Boolean = { false },
        confirm: suspend (String, String, Boolean, List<String>) -> String? = { r, _, _, _ -> r },
        confirmRecords: MutableList<Triple<String, Boolean, List<String>>> = mutableListOf(),
    ) = PipelineOrchestrator(
        planner = FakePlanGenerator(listOf(plan)),
        executor = executor,
        screenObserver = FakeScreenObserver(ScreenSnapshot(packageName = "com.kakao.talk", className = "Main", texts = listOf("채팅"))),
        appResolver = FakeAppResolver(),
        scope = scope,
        config = PipelineConfig(quietPeriodMs = 10),
        worker = UnconfinedTestDispatcher(testScheduler),
        logSink = {},
        confirmSend = { recipient, content, forced, choices ->
            confirmRecords += Triple(recipient, forced, choices)
            confirm(recipient, content, forced, choices)
        },
        confirmRequired = { false },
        recipientLookup = lookup,
        hasExactContact = hasExactContact,
    )

    @Test
    fun `검색 결과가 없으면 보내지 않고 확인도 하지 않는다`() = runTest {
        val executor = RecordingExecutor()
        val confirms = mutableListOf<Triple<String, Boolean, List<String>>>()
        val orchestrator = orchestrator(this, testScheduler, executor, lookup = { RecipientLookup.Names(emptyList()) }, confirmRecords = confirms)
        orchestrator.submit("카톡으로 정우한테 안녕이라고 보내줘")
        advanceUntilIdle()
        assertTrue("검색 결과가 없으면 보내기 명령이 없어야 한다", executor.sent.isEmpty())
        assertTrue("검색 결과가 없으면 확인도 없어야 한다", confirms.isEmpty())
    }

    @Test
    fun `검색 결과 한 명은 미리 고르고 확인을 강제한다`() = runTest {
        val executor = RecordingExecutor()
        val confirms = mutableListOf<Triple<String, Boolean, List<String>>>()
        val orchestrator = orchestrator(
            this, testScheduler, executor,
            lookup = { RecipientLookup.Names(listOf("김정우", "김철수")) },
            confirmRecords = confirms,
        )
        orchestrator.submit("카톡으로 정우한테 안녕이라고 보내줘")
        advanceUntilIdle()
        assertEquals(1, confirms.size)
        val (recipient, forced, choices) = confirms.single()
        assertEquals("김정우", recipient)
        assertTrue("후보가 있으면 확인을 강제한다", forced)
        assertEquals(listOf("김정우"), choices)
        assertEquals(listOf("김정우"), executor.sent)
    }

    @Test
    fun `검색 결과 여럿은 고르기 전에는 보내지 않고 칩으로 고르게 한다`() = runTest {
        val executor = RecordingExecutor()
        val confirms = mutableListOf<Triple<String, Boolean, List<String>>>()
        val orchestrator = orchestrator(
            this, testScheduler, executor,
            lookup = { RecipientLookup.Names(listOf("김정우", "이정우")) },
            confirm = { _, _, _, _ -> "이정우" },
            confirmRecords = confirms,
        )
        orchestrator.submit("카톡으로 정우한테 안녕이라고 보내줘")
        advanceUntilIdle()
        val (recipient, forced, choices) = confirms.single()
        assertEquals("", recipient)
        assertTrue(forced)
        assertEquals(listOf("김정우", "이정우"), choices)
        assertEquals("사용자가 고른 이름으로 보낸다", listOf("이정우"), executor.sent)
    }

    @Test
    fun `확인이 검색 결과 밖의 이름을 돌려주면 보내지 않는다`() = runTest {
        val executor = RecordingExecutor()
        val orchestrator = orchestrator(
            this, testScheduler, executor,
            lookup = { RecipientLookup.Names(listOf("김정우", "이정우")) },
            confirm = { _, _, _, _ -> "배재준" },
        )
        orchestrator.submit("카톡으로 정우한테 안녕이라고 보내줘")
        advanceUntilIdle()
        assertTrue("목록 밖 이름으로는 보내지 않는다", executor.sent.isEmpty())
    }

    @Test
    fun `검색이 실패하면 보내지 않는다`() = runTest {
        val executor = RecordingExecutor()
        val orchestrator = orchestrator(
            this, testScheduler, executor,
            lookup = { RecipientLookup.Unavailable("접근성 서비스가 연결되어 있지 않다") },
        )
        orchestrator.submit("카톡으로 정우한테 안녕이라고 보내줘")
        advanceUntilIdle()
        assertTrue(executor.sent.isEmpty())
    }

    @Test
    fun `연락처에 정확히 있으면 검색하지 않고 그대로 보낸다`() = runTest {
        val executor = RecordingExecutor()
        var lookups = 0
        val orchestrator = orchestrator(
            this, testScheduler, executor,
            lookup = { lookups++; RecipientLookup.Names(emptyList()) },
            hasExactContact = { true },
        )
        orchestrator.submit("카톡으로 정우한테 안녕이라고 보내줘")
        advanceUntilIdle()
        assertEquals("연락처에 있으면 검색하지 않는다", 0, lookups)
        assertEquals(listOf("정우"), executor.sent)
    }
}
