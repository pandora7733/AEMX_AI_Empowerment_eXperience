package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.SendMessageGuard
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 수단 말(카톡, 문자)과 받는 사람의 순서가 바뀌어도 메시지 내용에 받는 사람이 섞이지 않는지. */
class SendMessageContentTest {

    private fun plan(app: String, target: String, value: String) =
        ActionPlan(app, listOf(ActionStep(0, ActionType.SEND_MESSAGE, target, value)))

    private fun checked(utterance: String, app: String, target: String, value: String): ActionStep? {
        val result = SendMessageGuard.check(utterance, plan(app, target, value)) ?: return null
        return result.steps.single()
    }

    @Test fun `수단 말이 먼저 와도 받는 사람과 내용을 나눈다`() {
        val step = checked("카카오톡으로 배재준한테 테스트라고 보내줘", "카카오톡", "배재준", "테스트")!!

        assertEquals("배재준", step.target)
        assertEquals("테스트", step.value)
    }

    @Test fun `모델이 받는 사람을 내용에 섞어 내도 내용은 발화에서 다시 뽑는다`() {
        val step = checked("카카오톡으로 배재준한테 테스트라고 보내줘", "카카오톡", "배재준", "배재준한테 테스트")!!

        assertEquals("배재준", step.target)
        assertEquals("테스트", step.value)
    }

    @Test fun `받는 사람 뒤에 수단 말이 와도 같은 결과다`() {
        val step = checked("배재준한테 카카오톡으로 테스트라고 보내줘", "카카오톡", "배재준", "테스트")!!

        assertEquals("배재준", step.target)
        assertEquals("테스트", step.value)
    }

    @Test fun `에게 조사와 카톡 줄임말도 같은 결과다`() {
        val step = checked("카톡으로 배재준에게 테스트라고 보내줘", "카카오톡", "배재준", "테스트")!!

        assertEquals("배재준", step.target)
        assertEquals("테스트", step.value)
    }

    @Test fun `문자로 말해도 받는 사람과 내용을 나눈다`() {
        val step = checked("문자로 엄마한테 늦는다고 보내줘", "메시지", "엄마", "늦는다")!!

        assertEquals("엄마", step.target)
        assertEquals("늦는다", step.value)
    }

    @Test fun `라고 없이 말해도 받는 사람과 내용을 나눈다`() {
        val plain = checked("배재준한테 안녕 보내줘", "카카오톡", "배재준", "안녕")!!
        assertEquals("배재준", plain.target)
        assertEquals("안녕", plain.value)

        val channelFirst = checked("카카오톡으로 배재준한테 테스트 보내줘", "카카오톡", "배재준", "테스트")!!
        assertEquals("배재준", channelFirst.target)
        assertEquals("테스트", channelFirst.value)

        val channelAfter = checked("배재준한테 카카오톡으로 테스트 보내줘", "카카오톡", "배재준", "테스트")!!
        assertEquals("배재준", channelAfter.target)
        assertEquals("테스트", channelAfter.value)
    }

    @Test fun `받는 사람 이름이 두 번 나오면 보내지 않는다`() {
        assertNull(checked("배재준한테 카카오톡으로 배재준한테 테스트 보내줘", "카카오톡", "배재준", "테스트"))
        assertNull(checked("카카오톡으로 배재준한테 배재준한테 테스트라고 보내줘", "카카오톡", "배재준", "배재준한테 테스트"))
    }
}
