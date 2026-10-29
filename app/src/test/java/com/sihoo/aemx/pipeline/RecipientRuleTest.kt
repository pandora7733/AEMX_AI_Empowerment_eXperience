package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.SendMessageGuard
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 받는 사람은 발화에서 정한다. 모델이 고른 target 은 따르지 않는다.
 */
class RecipientRuleTest {

    private fun plan(app: String, target: String, value: String) = ActionPlan(
        app = app,
        steps = listOf(
            ActionStep(0, ActionType.OPEN_APP, app),
            ActionStep(1, ActionType.SEND_MESSAGE, target, value),
        ),
    )

    private fun sendStep(checked: ActionPlan): ActionStep =
        checked.steps.single { it.action == ActionType.SEND_MESSAGE }

    @Test
    fun `카톡으로 말해도 받는 사람은 앞의 이름이고 내용은 병신이다`() {
        val checked = SendMessageGuard.check(
            "서우진한테 카카오톡으로 병신이라고 보내줘",
            plan("카카오톡", target = "병신", value = "병신"),
        )

        assertNotNull(checked)
        assertEquals("서우진", sendStep(checked!!).target)
        assertEquals("병신", sendStep(checked).value)
    }

    @Test
    fun `내용이 받는 사람과 달라도 받는 사람은 발화의 이름이다`() {
        val checked = SendMessageGuard.check("서우진한테 엄마라고 보내줘", plan("카카오톡", "엄마", "엄마"))

        assertEquals("서우진", sendStep(checked!!).target)
        assertEquals("엄마", sendStep(checked).value)
    }

    @Test
    fun `받는 사람과 내용이 같은 단어여도 받는 사람은 앞의 이름이다`() {
        val checked = SendMessageGuard.check("엄마한테 엄마라고 보내줘", plan("카카오톡", "엄마", "엄마"))

        assertEquals("엄마", sendStep(checked!!).target)
        assertEquals("엄마", sendStep(checked).value)
    }

    @Test
    fun `문자로 보내도 에게 조사 앞의 이름을 받는 사람으로 본다`() {
        val checked = SendMessageGuard.check("배재준에게 문자로 늦는다고 보내줘", plan("메시지", "배재준", "늦는다"))

        assertEquals("배재준", sendStep(checked!!).target)
        assertEquals("늦는다", sendStep(checked).value)
    }

    @Test
    fun `께 조사도 받는 사람으로 본다`() {
        val checked = SendMessageGuard.check("할머니께 사랑한다고 보내줘", plan("카카오톡", "할머니", "사랑한다"))

        assertEquals("할머니", sendStep(checked!!).target)
        assertEquals("사랑한다", sendStep(checked).value)
    }

    @Test
    fun `조사가 떨어져 있어도 앞 어절을 받는 사람으로 본다`() {
        assertEquals("배재준", SendMessageGuard.recipientOf("배재준 한테 안녕이라고 보내줘"))
    }

    @Test
    fun `모델이 엉뚱한 이름을 골라도 발화의 받는 사람으로 고치고 글자 수만 로그로 남긴다`() {
        val logs = mutableListOf<String>()
        SendMessageGuard.logSink = { logs += it }
        try {
            val checked = SendMessageGuard.check("서우진한테 안녕이라고 보내줘", plan("카카오톡", "김철수", "안녕"))

            assertEquals("서우진", sendStep(checked!!).target)
            assertEquals(1, logs.size)
            assertTrue(logs.single().contains("모델 3자 -> 발화 3자"))
            assertTrue(logs.none { it.contains("서우진") || it.contains("김철수") })
        } finally {
            SendMessageGuard.logSink = {}
        }
    }

    @Test
    fun `받는 사람이 둘이면 보내지 않는다`() {
        val utterance = "서우진한테 배재준한테 보내라고 해줘"

        assertNull(SendMessageGuard.recipientOf(utterance))
        assertNull(SendMessageGuard.check(utterance, plan("카카오톡", "서우진", "보내라고 해줘")))
        assertEquals(
            "누구에게 보낼지 확실하지 않아 보내지 않았어요",
            SendMessageGuard.recipientRefusal(utterance, plan("카카오톡", "서우진", "보내라고 해줘")),
        )
    }

    @Test
    fun `받는 사람이 없으면 보내지 않는다`() {
        val utterance = "카톡으로 안녕 보내줘"

        assertNull(SendMessageGuard.recipientOf(utterance))
        assertNull(SendMessageGuard.check(utterance, plan("카카오톡", "안녕", "안녕")))
        assertNotNull(SendMessageGuard.recipientRefusal(utterance, plan("카카오톡", "안녕", "안녕")))
    }

    @Test
    fun `보내기가 없는 계획에는 받는 사람 거절을 걸지 않는다`() {
        val noSend = ActionPlan(app = "유튜브", steps = listOf(ActionStep(0, ActionType.OPEN_APP, "유튜브")))

        assertNull(SendMessageGuard.recipientRefusal("유튜브 열어줘", noSend))
    }
}
