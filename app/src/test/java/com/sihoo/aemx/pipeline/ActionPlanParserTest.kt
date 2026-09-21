package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.json.ActionPlanParser
import com.sihoo.aemx.pipeline.json.ParseResult
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 파서 테스트.
 *
 * 입력 문자열은 실제 시스템 프롬프트의 예시 출력을 그대로 가져다 쓴다
 * (`LLM/system_prompt.txt`). 스키마가 바뀌면 이 테스트가 먼저 깨져야 한다.
 */
class ActionPlanParserTest {

    @Test
    fun `프롬프트 예시 그대로의 로그인 계획을 읽는다`() {
        val raw = """
            {"app": "알라딘", "steps": [{"action": "open_app", "target": "알라딘"}, {"action": "tap", "target": "로그인"}, {"action": "input_credential", "target": "id"}, {"action": "input_credential", "target": "password"}, {"action": "tap", "target": "로그인"}]}
        """.trimIndent()

        val result = ActionPlanParser.parse(raw)
        assertTrue(result is ParseResult.Success)

        val plan = (result as ParseResult.Success).plan
        assertEquals("알라딘", plan.app)
        assertEquals(5, plan.steps.size)
        assertEquals(ActionType.OPEN_APP, plan.steps[0].action)
        assertEquals(ActionType.INPUT_CREDENTIAL, plan.steps[2].action)
        assertEquals("password", plan.steps[3].target)
        // 모델은 id 를 주지 않으므로 파서가 0부터 순번을 붙인다.
        assertEquals(listOf(0, 1, 2, 3, 4), plan.steps.map { it.index })
    }

    @Test
    fun `검색 계획의 value 를 읽는다`() {
        val raw =
            """{"app": "교보문고", "steps": [{"action": "open_app", "target": "교보문고"}, {"action": "tap", "target": "검색창"}, {"action": "input_text", "target": "검색창", "value": "토지"}, {"action": "tap", "target": "검색"}]}"""

        val plan = (ActionPlanParser.parse(raw) as ParseResult.Success).plan
        val input = plan.steps.first { it.action == ActionType.INPUT_TEXT }
        assertEquals("검색창", input.target)
        assertEquals("토지", input.value)
    }

    @Test
    fun `마크다운 울타리와 앞뒤 설명이 붙어 있어도 읽는다`() {
        val raw = """
            네, 알겠습니다. 다음과 같이 실행하겠습니다.

            ```json
            {"app": "알라딘", "steps": [{"action": "tap", "target": "로그인"}]}
            ```

            이대로 진행하면 됩니다.
        """.trimIndent()

        val result = ActionPlanParser.parse(raw)
        assertTrue(result is ParseResult.Success)
        assertEquals("알라딘", (result as ParseResult.Success).plan.app)
    }

    @Test
    fun `thinking 블록이 앞에 붙어도 읽는다`() {
        // Qwen3 는 thinking 모드가 켜져 있어 생각 블록이 먼저 나올 수 있다.
        val raw =
            """<think>사용자가 로그인을 원한다. open_app 후 tap 이 필요하다.</think>{"app": "알라딘", "steps": [{"action": "open_app", "target": "알라딘"}]}"""

        val result = ActionPlanParser.parse(raw)
        assertTrue(result is ParseResult.Success)
    }

    @Test
    fun `문자열 안의 중괄호에 속지 않는다`() {
        val raw = """{"app": "알라딘", "steps": [{"action": "input_text", "target": "검색창", "value": "중괄호 } 포함"}]}"""

        val plan = (ActionPlanParser.parse(raw) as ParseResult.Success).plan
        assertEquals("중괄호 } 포함", plan.steps[0].value)
    }

    @Test
    fun `모르는 action 은 UNKNOWN 으로 두고 경고를 남긴다`() {
        val raw = """{"app": "알라딘", "steps": [{"action": "double_tap", "target": "로그인"}]}"""

        val result = ActionPlanParser.parse(raw) as ParseResult.Success
        assertEquals(ActionType.UNKNOWN, result.plan.steps[0].action)
        assertTrue(result.warnings.any { it.contains("double_tap") })
    }

    @Test
    fun `JSON 이 아예 없으면 실패한다`() {
        val result = ActionPlanParser.parse("죄송합니다. 무슨 말씀인지 모르겠어요.")
        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `steps 가 비어 있으면 실패한다`() {
        val result = ActionPlanParser.parse("""{"app": "알라딘", "steps": []}""")
        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `중간에 끊긴 JSON 은 실패한다`() {
        // 토큰 상한에 걸려 잘리는 경우.
        val result = ActionPlanParser.parse("""{"app": "알라딘", "steps": [{"action": "tap", """)
        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `value 가 없으면 null 이다`() {
        val plan = (ActionPlanParser.parse(
            """{"app": "알라딘", "steps": [{"action": "tap", "target": "로그인"}]}"""
        ) as ParseResult.Success).plan
        assertNull(plan.steps[0].value)
    }
}
