package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.GroqChatClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class GroqChatClientTest {

    private fun body(model: String): JSONObject =
        JSONObject(GroqChatClient.buildBody(model, "system", "user"))

    @Test
    fun `기본 모델과 예비 모델 이름`() {
        assertEquals("qwen/qwen3.8-27b", GroqChatClient.DEFAULT_MODEL)
        assertEquals("openai/gpt-oss-120b", GroqChatClient.FALLBACK_MODEL)
        assertEquals(3_000, GroqChatClient.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun `qwen 모델은 reasoning_effort 를 none 으로 보낸다`() {
        assertEquals("none", body("qwen/qwen3.8-27b").getString("reasoning_effort"))
    }

    @Test
    fun `gpt-oss 모델은 reasoning_effort 를 low 로 보낸다`() {
        assertEquals("low", body("openai/gpt-oss-120b").getString("reasoning_effort"))
    }

    @Test
    fun `그 밖의 모델은 reasoning_effort 를 보내지 않는다`() {
        assertFalse(body("llama-3.3-70b-versatile").has("reasoning_effort"))
    }

    @Test
    fun `모든 모델에서 temperature 0 과 JSON 객체 형식은 그대로다`() {
        listOf("qwen/qwen3.8-27b", "openai/gpt-oss-120b", "llama-3.3-70b-versatile").forEach { model ->
            val json = body(model)
            assertEquals(model, json.getString("model"))
            assertEquals(0, json.getInt("temperature"))
            assertEquals("json_object", json.getJSONObject("response_format").getString("type"))
            assertTrue(json.getJSONArray("messages").length() == 2)
        }
    }

    @Test
    fun `취소되면 응답을 기다리지 않고 바로 취소로 끝난다`() {
        ServerSocket(0).use { server ->
            // 연결만 받고 아무 응답도 보내지 않는다. 클라이언트는 read 에서 막힌다.
            val silent = Thread { runCatching { server.accept().use { Thread.sleep(5_000) } } }
            silent.isDaemon = true
            silent.start()
            val client = GroqChatClient(
                apiKey = "local-test-only",
                timeoutMs = 10_000,
                endpoint = "http://127.0.0.1:${server.localPort}/v1/chat/completions",
            )

            val started = System.currentTimeMillis()
            // 취소로 끝나면 null 로 돌아온다. CloudChatException 이 새어 나오면 여기서 테스트가 실패한다.
            val answer = runBlocking { withTimeoutOrNull(300) { client.complete("system", "user") } }
            val elapsed = System.currentTimeMillis() - started

            assertNull(answer)
            assertTrue("취소 뒤 바로 돌아와야 한다: ${elapsed}ms", elapsed < 3_000)
        }
    }
}
