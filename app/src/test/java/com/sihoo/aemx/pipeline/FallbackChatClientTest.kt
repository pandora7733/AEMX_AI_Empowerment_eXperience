package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.CloudChatClient
import com.sihoo.aemx.pipeline.llm.CloudChatException
import com.sihoo.aemx.pipeline.llm.FallbackChatClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackChatClientTest {

    private class Fake(
        override val lastModel: String,
        private val answer: () -> String,
    ) : CloudChatClient {
        var calls = 0
        override suspend fun complete(system: String, user: String): String {
            calls++
            return answer()
        }
    }

    private fun ask(client: CloudChatClient): Result<String> =
        runCatching { runBlocking { client.complete("system", "user") } }

    @Test
    fun `첫 모델이 답하면 예비 모델은 부르지 않는다`() {
        val primary = Fake("qwen/qwen3.8-27b") { "첫 답" }
        val fallback = Fake("openai/gpt-oss-120b") { "예비 답" }

        val client = FallbackChatClient(primary, fallback)

        assertEquals("첫 답", ask(client).getOrThrow())
        assertEquals(0, fallback.calls)
        assertEquals("qwen/qwen3.8-27b", client.lastModel)
    }

    @Test
    fun `한도 초과(429)면 예비 모델이 답한다`() {
        val primary = Fake("qwen/qwen3.8-27b") { throw CloudChatException("한도", 429) }
        val fallback = Fake("openai/gpt-oss-120b") { "예비 답" }
        val client = FallbackChatClient(primary, fallback)

        assertEquals("예비 답", ask(client).getOrThrow())
        assertEquals(1, fallback.calls)
        assertEquals("openai/gpt-oss-120b", client.lastModel)
    }

    @Test
    fun `서버 오류(5xx)와 잘못된 요청(400, 404)도 예비 모델로 넘긴다`() {
        listOf(400, 404, 500, 503).forEach { code ->
            val primary = Fake("qwen/qwen3.8-27b") { throw CloudChatException("오류", code) }
            val fallback = Fake("openai/gpt-oss-120b") { "예비 답" }
            assertEquals("HTTP $code", "예비 답", ask(FallbackChatClient(primary, fallback)).getOrThrow())
        }
    }

    @Test
    fun `네트워크 오류는 예비 모델로 넘긴다`() {
        val primary = Fake("qwen/qwen3.8-27b") { throw CloudChatException("네트워크 오류 (SocketTimeoutException)") }
        val fallback = Fake("openai/gpt-oss-120b") { "예비 답" }

        assertEquals("예비 답", ask(FallbackChatClient(primary, fallback)).getOrThrow())
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `인증 오류(401)는 모델을 바꿔도 같으므로 예비로 넘기지 않는다`() {
        val primary = Fake("qwen/qwen3.8-27b") { throw CloudChatException("인증", 401) }
        val fallback = Fake("openai/gpt-oss-120b") { "예비 답" }

        val error = ask(FallbackChatClient(primary, fallback)).exceptionOrNull()

        assertTrue(error is CloudChatException)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `두 모델이 모두 실패하면 예비 모델의 오류를 던진다`() {
        val primary = Fake("qwen/qwen3.8-27b") { throw CloudChatException("한도", 429) }
        val fallback = Fake("openai/gpt-oss-120b") { throw CloudChatException("서버", 503) }
        val client = FallbackChatClient(primary, fallback)

        val error = ask(client).exceptionOrNull() as? CloudChatException

        assertEquals(503, error?.httpCode)
        assertNull(client.lastModel)
    }

    private class Scripted(
        override val lastModel: String,
        private val answer: suspend () -> String,
    ) : CloudChatClient {
        var calls = 0
        override suspend fun complete(system: String, user: String): String {
            calls++
            return answer()
        }
    }

    @Test
    fun `첫 모델이 느리면 시간 한도에서 끊고 남은 시간으로 예비 모델이 답한다`() = runBlocking {
        val primary = Scripted("qwen/qwen3.8-27b") { delay(10_000); "첫 답" }
        val fallback = Scripted("openai/gpt-oss-120b") { "예비 답" }
        val client = FallbackChatClient(
            primary, fallback, totalBudgetMs = 600, primaryTimeoutMs = 200, minFallbackMs = 300,
        )

        val started = System.currentTimeMillis()
        val answer = client.complete("system", "user")
        val elapsed = System.currentTimeMillis() - started

        assertEquals("예비 답", answer)
        assertTrue("전체 한도 안에서 끝나야 한다: ${elapsed}ms", elapsed < 600)
    }

    @Test
    fun `남은 시간이 부족하면 예비 모델을 부르지 않고 실패한다`() = runBlocking {
        val primary = Scripted("qwen/qwen3.8-27b") { delay(400); throw CloudChatException("한도", 429) }
        val fallback = Scripted("openai/gpt-oss-120b") { "예비 답" }
        val client = FallbackChatClient(
            primary, fallback, totalBudgetMs = 600, primaryTimeoutMs = 500, minFallbackMs = 300,
        )

        val error = runCatching { client.complete("system", "user") }.exceptionOrNull()

        assertTrue(error is CloudChatException)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `예비 모델도 남은 시간을 넘지 않는다`() = runBlocking {
        val primary = Scripted("qwen/qwen3.8-27b") { delay(10_000); "첫 답" }
        val fallback = Scripted("openai/gpt-oss-120b") { delay(10_000); "예비 답" }
        val client = FallbackChatClient(
            primary, fallback, totalBudgetMs = 600, primaryTimeoutMs = 200, minFallbackMs = 100,
        )

        val started = System.currentTimeMillis()
        val error = runCatching { client.complete("system", "user") }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - started

        assertTrue(error is CloudChatException)
        assertTrue("예비 모델까지 합쳐도 한도를 넘으면 안 된다: ${elapsed}ms", elapsed < 1_000)
    }
}
