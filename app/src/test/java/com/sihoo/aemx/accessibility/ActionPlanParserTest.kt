package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ActionPlanParserTest {
    @Test
    fun `rejects coercion cross app actions and invalid durations`() {
        val invalid = listOf(
            """{"app":"교보문고","steps":[{"action":"tap","target":12}]}""",
            """{"app":"교보문고","steps":[{"action":"open_app","target":"알라딘"}]}""",
            """{"app":"교보문고","steps":[{"action":"wait","value":"1.5s"}]}""",
            """{"app":"교보문고","steps":[{"action":"wait","value":"-5"}]}""",
            """{"app":"교보문고","steps":[{"action":"wait","value":"9999999999999999999s"}]}""",
            """{"app":"교보문고","steps":[{"action":"scroll","target":"left"}]}""",
            """{"app":"교보문고","steps":[{"action":"input_credential","target":"id","value":"forbidden"}]}""",
        )
        invalid.forEach { json ->
            assertThrows(ActionPlanParseException::class.java) { ActionPlanParser.parse(json) }
        }
    }

    @Test
    fun `wait uses explicit units and bounded default`() {
        assertEquals(1000L, WaitDuration.millis(""))
        assertEquals(2000L, WaitDuration.millis("2"))
        assertEquals(250L, WaitDuration.millis("250ms"))
        assertEquals(30000L, WaitDuration.millis("30s"))
        assertThrows(IllegalArgumentException::class.java) { WaitDuration.millis("31s") }
    }

    @Test
    fun `rejects excessive step counts and preserves input whitespace`() {
        val steps = List(51) { """{"action":"tap","target":"검색"}""" }.joinToString(",")
        assertThrows(ActionPlanParseException::class.java) {
            ActionPlanParser.parse("""{"app":"교보문고","steps":[$steps]}""")
        }
        val plan = ActionPlanParser.parse("""{"app":"교보문고","steps":[{"action":"input_text","target":"검색","value":" a b "}]}""")
        assertEquals(" a b ", plan.steps.single().value)
    }

    @Test
    fun `parses steps in source order`() {
        val json = """
            {
              "app": "교보문고",
              "steps": [
                {"action":"open_app","target":"교보문고"},
                {"action":"tap","target":"검색창"},
                {"action":"input_text","target":"검색창","value":"데미안"},
                {"action":"wait","target":"1"}
              ]
            }
        """.trimIndent()

        val plan = ActionPlanParser.parse(json)

        assertEquals("교보문고", plan.app)
        assertEquals(
            listOf(ActionType.OPEN_APP, ActionType.TAP, ActionType.INPUT_TEXT, ActionType.WAIT),
            plan.steps.map { it.action },
        )
        assertEquals("데미안", plan.steps[2].value)
    }

    @Test
    fun `rejects unknown action`() {
        val json = """{"app":"교보문고","steps":[{"action":"delete","target":"책"}]}"""
        assertThrows(ActionPlanParseException::class.java) { ActionPlanParser.parse(json) }
    }

    @Test
    fun `requires value for input text`() {
        val json = """{"app":"알라딘","steps":[{"action":"input_text","target":"검색창"}]}"""
        assertThrows(ActionPlanParseException::class.java) { ActionPlanParser.parse(json) }
    }

    @Test
    fun `allows only known credential fields`() {
        val json = """{"app":"알라딘","steps":[{"action":"input_credential","target":"token"}]}"""
        assertThrows(ActionPlanParseException::class.java) { ActionPlanParser.parse(json) }
    }

    @Test
    fun `rejects an empty step list`() {
        val json = """{"app":"교보문고","steps":[]}"""
        assertThrows(ActionPlanParseException::class.java) { ActionPlanParser.parse(json) }
    }

    @Test
    fun `rejects malformed json`() {
        assertThrows(ActionPlanParseException::class.java) {
            ActionPlanParser.parse("not-json")
        }
    }
}
