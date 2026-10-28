package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.CloudChatClient
import com.sihoo.aemx.pipeline.llm.CloudChatException
import com.sihoo.aemx.pipeline.llm.CloudPlanGenerator
import com.sihoo.aemx.pipeline.llm.GroqChatClient
import com.sihoo.aemx.pipeline.port.PlanRequest
import com.sihoo.aemx.pipeline.port.PlanResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPlanGeneratorTest {

    private class FakeClient(private val answer: () -> String) : CloudChatClient {
        var calls = 0
        var lastUser: String? = null
        override suspend fun complete(system: String, user: String): String {
            calls++
            lastUser = user
            return answer()
        }
    }

    private val youtubeJson =
        """{"app":"유튜브","steps":[{"action":"open_app","target":"유튜브"},{"action":"search","target":"고양이 영상"},{"action":"play_first","target":"첫 번째 동영상"}]}"""

    private fun initial(text: String) = PlanRequest(utterance = text, mode = PlanRequest.Mode.INITIAL)

    @Test
    fun `정상 응답은 계획으로 읽는다`() = runBlocking {
        val gen = CloudPlanGenerator(FakeClient { youtubeJson }, "system")
        val result = gen.generate(initial("유튜브에서 고양이 영상 틀어줘"))

        assertTrue(result is PlanResult.Success)
        assertEquals(3, (result as PlanResult.Success).plan.steps.size)
    }

    @Test
    fun `네트워크 오류는 EngineError 로 돌려준다`() = runBlocking {
        val gen = CloudPlanGenerator(FakeClient { throw CloudChatException("네트워크 오류") }, "system")
        assertTrue(gen.generate(initial("유튜브에서 고양이 영상 틀어줘")) is PlanResult.EngineError)
    }

    @Test
    fun `JSON 이 아닌 응답은 Malformed 이다`() = runBlocking {
        val gen = CloudPlanGenerator(FakeClient { "죄송하지만 할 수 없습니다" }, "system")
        assertTrue(gen.generate(initial("유튜브에서 고양이 영상 틀어줘")) is PlanResult.Malformed)
    }

    @Test
    fun `개인정보 명령은 클라우드 호출 자체를 하지 않는다`() = runBlocking {
        val client = FakeClient { youtubeJson }
        val gen = CloudPlanGenerator(client, "system")

        val result = gen.generate(initial("엄마한테 늦는다고 카톡 보내줘"))

        assertTrue(result is PlanResult.EngineError)
        assertEquals(0, client.calls)
    }

    @Test
    fun `재분석에서 개인정보 앱 화면은 보내지 않는다`() = runBlocking {
        val client = FakeClient { youtubeJson }
        val gen = CloudPlanGenerator(client, "system")
        val screen = com.sihoo.aemx.pipeline.model.ScreenSnapshot(
            packageName = "com.kakao.talk",
            className = null,
            texts = listOf("엄마"),
        )
        val result = gen.generate(
            PlanRequest(
                utterance = "노래 틀어줘",
                mode = PlanRequest.Mode.REPLAN,
                screen = screen,
            ),
        )

        assertTrue(result is PlanResult.EngineError)
        assertEquals(0, client.calls)
    }

    @Test
    fun `요청 본문에 모델 system user 가 들어간다`() {
        val body = JSONObject(GroqChatClient.buildBody("m", "sys", "usr"))
        assertEquals("m", body.getString("model"))
        assertEquals(0, body.getInt("temperature"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("usr", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `응답에서 choices 의 content 를 꺼낸다`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"{\"app\":\"x\"}"}}]}"""
        assertEquals("""{"app":"x"}""", GroqChatClient.extractContent(body))
    }

    @Test
    fun `이상한 응답이면 null 이다`() {
        assertNull(GroqChatClient.extractContent("""{"error":"x"}"""))
        assertNull(GroqChatClient.extractContent("not json"))
        assertNull(GroqChatClient.extractContent("""{"choices":[{"message":{"content":"  "}}]}"""))
    }
}
