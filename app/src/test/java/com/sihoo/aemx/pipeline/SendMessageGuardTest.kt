package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.SendMessageGuard
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SendMessageGuardTest {

    private fun plan(app: String, to: String, value: String?) = ActionPlan(
        app = app,
        steps = listOf(ActionStep(0, ActionType.SEND_MESSAGE, to, value)),
    )

    @Test
    fun `value 가 발화에 그대로 있으면 그대로 둔다`() {
        val original = plan("카카오톡", "엄마", "늦을 것 같아")
        assertSame(original, SendMessageGuard.check("엄마한테 늦을 것 같아 라고 카톡 보내줘", original))
    }

    @Test
    fun `value 를 지어냈으면 발화에서 말한 내용을 뽑아 바꾼다`() {
        val fixed = SendMessageGuard.check("엄마한테 늦는다고 보내줘", plan("카카오톡", "엄마", "오늘 회식이라 늦어요"))!!

        assertEquals("늦는다", fixed.steps[0].value)
    }

    @Test
    fun `내용을 뽑을 수 없으면 계획을 버린다`() {
        assertNull(SendMessageGuard.check("엄마한테 카톡 보내줘", plan("카카오톡", "엄마", "사랑해")))
        assertNull(SendMessageGuard.check("엄마한테 카톡 보내줘", plan("카카오톡", "엄마", null)))
    }

    @Test
    fun `공백과 따옴표 차이는 같은 글로 본다`() {
        val original = plan("카카오톡", "엄마", "곧 도착해")
        assertSame(original, SendMessageGuard.check("엄마에게 \"곧도착해\" 라고 보내줘", original))
    }

    @Test
    fun `앱 이름을 카카오톡과 메시지로 바로잡는다`() {
        assertEquals("카카오톡", SendMessageGuard.check("엄마한테 안녕이라고 보내줘", plan("카톡", "엄마", "안녕"))!!.app)
        assertEquals("메시지", SendMessageGuard.check("엄마한테 안녕이라고 보내줘", plan("문자", "엄마", "안녕"))!!.app)
    }

    @Test
    fun `send_message 가 없는 계획은 건드리지 않는다`() {
        val original = ActionPlan("네이버", listOf(ActionStep(0, ActionType.SEARCH, "날씨")))
        assertSame(original, SendMessageGuard.check("네이버에서 날씨 검색해줘", original))
    }

    @Test
    fun `받는 사람과 조사를 걷어 낸다`() {
        assertEquals("늦는다", SendMessageGuard.extractContent("엄마한테 늦는다고 보내줘", "엄마"))
        assertEquals("늦는다", SendMessageGuard.extractContent("늦는다고 문자 보내줘", "엄마"))
    }

    @Test
    fun `value 에 지시 꼬리가 붙어 있어도 뽑은 내용을 우선한다`() {
        // 모델이 "테스트라고 보내줘" 를 통째로 value 로 냈던 경우. 발화에 그대로 있어도 보내면 안 된다.
        val fixed = SendMessageGuard.check(
            "배재준한테 카톡으로 테스트라고 보내줘",
            plan("카카오톡", "배재준", "테스트라고 보내줘"),
        )!!

        assertEquals("테스트", fixed.steps[0].value)
    }

    @Test
    fun `뽑지 못해도 지시 꼬리로 끝나는 value 는 버린다`() {
        assertNull(SendMessageGuard.check("엄마한테 카톡 보내줘", plan("카카오톡", "엄마", "카톡 보내줘")))
        assertNull(SendMessageGuard.check("엄마한테 안부 전해줘", plan("카카오톡", "엄마", "안부 전해줘")))
    }

    @Test
    fun `value 가 지시 꼬리뿐이어도 뽑을 수 있으면 뽑은 내용으로 바꾼다`() {
        val fixed = SendMessageGuard.check("엄마한테 테스트라고 보내줘", plan("카카오톡", "엄마", "라고 보내줘"))!!
        assertEquals("테스트", fixed.steps[0].value)
    }

    @Test
    fun `수단 말과 조사를 걷어 내고 내용만 뽑는다`() {
        assertEquals("테스트", SendMessageGuard.extractContent("배재준한테 카톡으로 테스트라고 보내줘", "배재준"))
    }

    @Test
    fun `보낼 내용 없이 수단 말만 있으면 버린다`() {
        // 폰에서 "배재준한테 문자 보내줘" 의 value 가 "문자" 로 나와 그대로 전송됐던 경우.
        assertNull(SendMessageGuard.check("배재준한테 문자 보내줘", plan("메시지", "배재준", "문자")))
        assertNull(SendMessageGuard.check("배재준한테 카톡 보내줘", plan("카카오톡", "배재준", "카톡")))
        assertNull(SendMessageGuard.check("배재준한테 카톡으로 보내줘", plan("카카오톡", "배재준", "카톡으로")))
        assertNull(SendMessageGuard.check("배재준한테 보내줘", plan("카카오톡", "배재준", "배재준")))
    }

    @Test
    fun `라고 없이 내용을 말해도 보낸다`() {
        val original = plan("카카오톡", "배재준", "안녕")
        assertSame(original, SendMessageGuard.check("배재준한테 안녕 보내줘", original))

        val fixed = SendMessageGuard.check("배재준한테 카톡으로 안녕 보내줘", plan("카카오톡", "배재준", "카톡으로 안녕 보내줘"))!!
        assertEquals("안녕", fixed.steps[0].value)
    }

    @Test
    fun `라고 없는 말에서도 내용을 뽑는다`() {
        assertEquals("안녕", SendMessageGuard.extractLoose("배재준한테 안녕 보내줘", "배재준"))
        assertEquals("안녕", SendMessageGuard.extractLoose("배재준한테 문자로 안녕 보내줘", "배재준"))
        assertNull(SendMessageGuard.extractLoose("배재준한테 문자 보내줘", "배재준"))
        assertNull(SendMessageGuard.extractLoose("배재준한테 보내줘", "배재준"))
    }
}
