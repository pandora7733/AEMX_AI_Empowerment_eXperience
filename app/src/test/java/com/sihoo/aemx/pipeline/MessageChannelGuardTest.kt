package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.MessageChannelGuard
import com.sihoo.aemx.pipeline.llm.MessageChannelGuard.Result
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageChannelGuardTest {

    private fun plan(app: String, to: String = "김경환", value: String = "안녕") = ActionPlan(
        app = app,
        steps = listOf(ActionStep(0, ActionType.SEND_MESSAGE, to, value)),
    )

    private fun ok(utterance: String, plan: ActionPlan): ActionPlan =
        (MessageChannelGuard.apply(utterance, plan) as Result.Ok).plan

    @Test
    fun `인스타로 보내 달라는데 카카오톡 계획이면 버린다`() {
        val result = MessageChannelGuard.apply("김경환한테 인스타로 안녕이라고 보내줘", plan("카카오톡"))

        assertTrue(result is Result.Unsupported)
        assertEquals("인스타그램 메시지는 아직 보낼 수 없어요", (result as Result.Unsupported).reason)
    }

    @Test
    fun `지원하지 않는 다른 수단도 버린다`() {
        listOf(
            "김경환한테 인스타그램으로 안녕이라고 보내줘",
            "김경환한테 디엠으로 안녕이라고 보내줘",
            "김경환한테 DM 보내줘",
            "김경환한테 페북으로 안녕이라고 보내줘",
            "김경환한테 텔레그램으로 안녕이라고 보내줘",
            "인스타로 김경환한테 안녕이라고 보내줘",
            "김경환한테 안녕이라고 인스타 보내줘",
        ).forEach {
            assertTrue(it, MessageChannelGuard.apply(it, plan("카카오톡")) is Result.Unsupported)
        }
    }

    @Test
    fun `카톡으로라고 했는데 계획 앱이 메시지면 카카오톡으로 바로잡는다`() {
        val fixed = ok("김경환한테 카톡으로 안녕이라고 보내줘", plan("메시지"))

        assertEquals("카카오톡", fixed.app)
        assertEquals(plan("메시지").steps, fixed.steps)
    }

    @Test
    fun `문자로라고 했는데 계획 앱이 카카오톡이면 메시지로 바로잡는다`() {
        assertEquals("메시지", ok("김경환한테 문자로 안녕이라고 보내줘", plan("카카오톡")).app)
        assertEquals("메시지", ok("김경환한테 문자 보내줘", plan("카카오톡")).app)
    }

    @Test
    fun `수단이 맞으면 같은 계획을 그대로 돌려준다`() {
        val original = plan("카카오톡")
        assertSame(original, ok("김경환한테 카톡으로 안녕이라고 보내줘", original))
    }

    @Test
    fun `수단 말이 없으면 그대로 둔다`() {
        val original = plan("카카오톡")
        assertSame(original, ok("김경환한테 안녕이라고 보내줘", original))
    }

    @Test
    fun `보낼 내용 안의 수단 말은 수단으로 읽지 않는다`() {
        val original = plan("카카오톡", value = "오늘 인스타 올렸어")
        assertSame(original, ok("김경환한테 오늘 인스타 올렸어라고 보내줘", original))
        assertEquals("카톡", MessageChannelGuard.channelWord("김경환한테 카톡으로 인스타 올렸다고 보내줘"))
    }

    @Test
    fun `받는 사람과 보내가 함께 있어야 보내기 요청으로 본다`() {
        assertTrue(MessageChannelGuard.asksToSend("서우진한테 문자로 안녕이라고 보내줘"))
        assertTrue(MessageChannelGuard.asksToSend("엄마에게 카톡 보내줘"))
        assertTrue(!MessageChannelGuard.asksToSend("사진 보내줘"))
        assertTrue(!MessageChannelGuard.asksToSend("엄마한테 안부 전해줘"))
        assertTrue(!MessageChannelGuard.asksToSend("유튜브 열어줘"))
    }

    @Test
    fun `send_message 가 없는 계획은 건드리지 않는다`() {
        val search = ActionPlan("인스타그램", listOf(ActionStep(0, ActionType.OPEN_APP, "인스타그램")))
        assertSame(search, ok("인스타그램 열어줘", search))
    }

    private fun refusal(utterance: String, plan: ActionPlan) = MessageChannelGuard.refusal(utterance, plan)

    @Test
    fun `잡담에 지어낸 send_message 는 거절한다`() {
        assertEquals(
            "메시지를 보내 달라는 말이 아니라서 보내지 않았어요",
            refusal("안녕. 오 좋네.", plan("카카오톡", to = "오 좋네", value = "안녕. 오 좋네.")),
        )
        assertEquals(
            "메시지를 보내 달라는 말이 아니라서 보내지 않았어요",
            refusal("줴줴이야", plan("카카오톡", to = "재미있는 일", value = "안녕하세요!")),
        )
    }

    @Test
    fun `보내기 요청이면 통과한다`() {
        assertNull(refusal("배재준한테 카톡으로 테스트라고 보내줘", plan("카카오톡", to = "배재준", value = "테스트")))
        assertNull(refusal("배재준한테 안녕 보내줘", plan("카카오톡", to = "배재준", value = "안녕")))
        assertNull(refusal("이일일공오 배재준한테 안녕 보내줘", plan("카카오톡", to = "21105 배재준", value = "안녕")))
    }

    @Test
    fun `받는 사람이 발화에 없으면 거절한다`() {
        assertEquals(
            "받는 사람을 알아듣지 못해 보내지 않았어요",
            refusal("배재준한테 안녕 보내줘", plan("카카오톡", to = "김경환", value = "안녕")),
        )
    }

    @Test
    fun `value 가 발화 전체이면 거절한다`() {
        assertEquals(
            "보낼 내용을 알아듣지 못해 보내지 않았어요",
            refusal("배재준한테 안녕 보내줘", plan("카카오톡", to = "배재준", value = "배재준한테 안녕 보내줘")),
        )
    }

    @Test
    fun `send_message 가 없는 계획은 거절하지 않는다`() {
        val open = ActionPlan("유튜브", listOf(ActionStep(0, ActionType.OPEN_APP, "유튜브")))
        assertNull(refusal("유튜브 열어줘", open))
    }
}
