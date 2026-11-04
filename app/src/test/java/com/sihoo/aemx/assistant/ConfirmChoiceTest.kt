package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.PipelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 보내기 확인의 받는 사람 고르기: 후보 상태, 고른 이름으로만 보내기, 취소, 후보 밖 이름 거절(상태와 답 흐름만 본다). */
class ConfirmChoiceTest {

    private fun store() = AssistantStore(CoroutineScope(Dispatchers.Unconfined))

    private fun confirm(
        recipient: String,
        choices: List<String>,
    ) = PipelineEvent.ConfirmSend("r1", 2, "p1-s0", recipient, "테스트", choices)

    /** 답 하나를 기다린다. 구독을 먼저 걸고 답을 보내야 놓치지 않는다. */
    private fun runWithAnswer(store: AssistantStore, action: () -> Unit): SendDecision? = runBlocking {
        var got: SendDecision? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            got = store.sendAnswers.first()
        }
        delay(10)
        action()
        withTimeoutOrNull(300) { job.join() }
        job.cancel()
        got
    }

    private fun AssistantStore.start(event: PipelineEvent) = runBlocking {
        this@start.post(PipelineEvent.RunStarted("r1", 1, "명령"))
        this@start.post(event)
        delay(20)
    }

    @Test
    fun `후보가 여럿이면 아무것도 미리 고르지 않고 고르기 전에는 보내지 않는다`() {
        val store = store()
        store.start(confirm("", listOf("김정우", "이정우", "정우")))
        assertNull(store.state.value.confirmSelected)
        assertEquals(listOf("김정우", "이정우", "정우"), store.state.value.confirmChoices)

        val answer = runWithAnswer(store) { store.answerSend(true) }
        assertNull("고르기 전 보내기는 아무 답도 보내지 않는다", answer)
    }

    @Test
    fun `후보를 고르면 그 이름으로만 보낸다`() {
        val store = store()
        store.start(confirm("", listOf("김정우", "이정우", "정우")))
        store.selectRecipient("김정우")
        assertEquals("김정우", store.state.value.confirmSelected)

        val answer = runWithAnswer(store) { store.answerSend(true) }
        assertEquals(SendDecision(send = true, recipient = "김정우"), answer)
    }

    @Test
    fun `후보 목록에 없는 이름은 고르지 못한다`() {
        val store = store()
        store.start(confirm("", listOf("김정우", "정우")))
        store.selectRecipient("이정우")
        assertNull(store.state.value.confirmSelected)
    }

    @Test
    fun `후보가 하나면 그 후보가 미리 고른 상태다`() {
        val store = store()
        store.start(confirm("김정우", listOf("김정우", "정우")))
        assertEquals("김정우", store.state.value.confirmSelected)

        val answer = runWithAnswer(store) { store.answerSend(true) }
        assertEquals(SendDecision(send = true, recipient = "김정우"), answer)
    }

    @Test
    fun `취소하면 고른 것이 있어도 아무것도 보내지 않는다`() {
        val store = store()
        store.start(confirm("김정우", listOf("김정우", "정우")))

        val answer = runWithAnswer(store) { store.answerSend(false) }
        assertEquals(SendDecision(send = false, recipient = null), answer)
    }

    @Test
    fun `후보가 없으면 확인 중인 이름으로 보낸다`() {
        val store = store()
        store.start(confirm("배재준", emptyList()))
        assertEquals(emptyList<String>(), store.state.value.confirmChoices)

        val answer = runWithAnswer(store) { store.answerSend(true) }
        assertEquals(SendDecision(send = true, recipient = "배재준"), answer)
    }

    @Test
    fun `확인 중이 아니면 고르기도 보내기도 무시한다`() {
        val store = store()
        store.selectRecipient("김정우")
        assertNull(store.state.value.confirmSelected)
        val answer = runWithAnswer(store) { store.answerSend(true) }
        assertNull(answer)
    }

    @Test
    fun `후보 목록이 없으면 고르기 요청은 확인 중인 이름을 바꾸지 않는다`() {
        val store = store()
        store.start(confirm("배재준", emptyList()))
        store.selectRecipient("김정우")
        assertEquals("배재준", store.state.value.confirmSelected)
    }
}
