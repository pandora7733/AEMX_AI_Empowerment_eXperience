package com.sihoo.aemx.pipeline.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 명령을 받을 수 있는지 정하는 규칙, 그리고 낡은 잠금을 푸는 조건(순수). */
class RunGateTest {

    @Test fun `아무것도 돌지 않으면 막힌 이유가 없다`() {
        assertNull(RunGate.busyReason(modelPreparing = false, directRunning = false, orchestratorBusy = false, planRunning = false))
        assertFalse(RunGate.locked(modelPreparing = false, directRunning = false, orchestratorBusy = false))
    }

    @Test fun `돌고 있는 것마다 이유가 다르고 우선순위는 준비, 직접, 명령, 계획 순이다`() {
        assertEquals("LLM 준비 중", RunGate.busyReason(true, true, true, true))
        assertEquals("실기기 직접 실행 중", RunGate.busyReason(false, true, true, true))
        assertEquals("명령 처리 중", RunGate.busyReason(false, false, true, true))
        assertEquals("접근성 계획 실행 중", RunGate.busyReason(false, false, false, true))
    }

    @Test fun `접근성 계획만 도는 동안은 버튼을 잠그지 않는다`() {
        // 예전 동작과 같다. 계획 실행 중인 명령은 자동 실행만 막고 버튼은 그대로 둔다.
        assertFalse(RunGate.locked(modelPreparing = false, directRunning = false, orchestratorBusy = false))
    }

    @Test fun `표시가 잠겨 있는데 돌고 있는 것이 없으면 낡은 값이다`() {
        assertTrue(RunGate.isStale(mirrorLocked = true, reason = null))
        assertFalse("실제로 도는 중이면 낡은 값이 아니다", RunGate.isStale(mirrorLocked = true, reason = "명령 처리 중"))
        assertFalse("잠겨 있지 않으면 풀 것이 없다", RunGate.isStale(mirrorLocked = false, reason = null))
    }

    @Test fun `완료 훅은 작업이 끝난 뒤에 부르므로 그때는 활성 상태가 아니다`() = runBlocking {
        // 명령이 끝나는 상태 방출은 작업 안에서 일어나 그 순간 작업은 아직 활성이다. 그래서 잠금을 다시 맞추는 훅은
        // 작업이 끝난 뒤에 불러야 한다. 이 테스트는 그 순서를 고정한다.
        val wasActiveInHook = CompletableDeferred<Boolean>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            kotlinx.coroutines.yield()
        }
        job.invokeOnCompletion { wasActiveInHook.complete(job.isActive) }
        assertFalse(withTimeout(2_000) { wasActiveInHook.await() })
    }
}
