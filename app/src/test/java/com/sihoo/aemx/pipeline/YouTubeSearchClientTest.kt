package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.youtube.YouTubeQuota
import com.sihoo.aemx.pipeline.youtube.YouTubeSearchClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 로컬 소켓 서버로 YouTube 검색 호출을 흉내 낸다. 실제 구글 서버는 부르지 않고, 키도 쓰지 않는다.
 */
class YouTubeSearchClientTest {

    private class LocalServer(
        private val respond: (socket: java.net.Socket) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        val requests = AtomicInteger(0)
        val port: Int get() = server.localPort
        private val thread = Thread {
            runCatching {
                while (!server.isClosed) {
                    val socket = server.accept()
                    requests.incrementAndGet()
                    Thread { runCatching { respond(socket) } }.apply { isDaemon = true }.start()
                }
            }
        }.apply { isDaemon = true; start() }

        val endpoint: String get() = "http://127.0.0.1:$port/youtube/v3/search"

        override fun close() {
            server.close()
        }
    }

    /** 요청 줄과 헤더를 비우고 응답을 쓴다. 요청 내용은 검사하지 않는다. */
    private fun reply(socket: java.net.Socket, status: String, body: String) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            val writer = OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8)
            writer.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
            writer.write(body)
            writer.flush()
        }
    }

    private val okBody = """{"items":[{"id":{"kind":"youtube#video","videoId":"aaaaaa1"}},{"id":{"kind":"youtube#video","videoId":"bbbbbb2"}}]}"""

    @Test
    fun `정상 응답의 영상 id 를 돌려준다`() {
        LocalServer { socket -> reply(socket, "200 OK", okBody) }.use { server ->
            val client = YouTubeSearchClient(
                apiKey = "dummy-key",
                quota = YouTubeQuota(),
                endpoint = server.endpoint,
            )

            val ids = runBlocking { client.search("고양이 영상", 5) }

            assertEquals(listOf("aaaaaa1", "bbbbbb2"), ids)
        }
    }

    @Test
    fun `키가 없으면 요청을 보내지 않는다`() {
        LocalServer { socket -> reply(socket, "200 OK", okBody) }.use { server ->
            val client = YouTubeSearchClient(apiKey = "", quota = YouTubeQuota(), endpoint = server.endpoint)

            assertNull(runBlocking { client.search("고양이", 5) })
            assertEquals(0, server.requests.get())
        }
    }

    @Test
    fun `하루 한도를 넘으면 실패로 돌려주고 그날은 다시 부르지 않는다`() {
        val quotaBody = """{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}]}}"""
        LocalServer { socket -> reply(socket, "403 Forbidden", quotaBody) }.use { server ->
            var now = 1_000_000L
            val quota = YouTubeQuota { now }
            val client = YouTubeSearchClient(apiKey = "dummy-key", quota = quota, endpoint = server.endpoint)

            assertNull(runBlocking { client.search("고양이", 5) })
            assertTrue("한도 초과 뒤에는 막혀야 한다", quota.isBlocked())
            assertNull(runBlocking { client.search("고양이", 5) })
            assertEquals("두 번째 호출은 요청을 보내지 않는다", 1, server.requests.get())

            // 다음 태평양 시간 자정이 지나면 다시 부를 수 있다.
            now += 2L * 24 * 60 * 60 * 1000
            assertTrue(!quota.isBlocked())
        }
    }

    @Test
    fun `한도 초과가 아닌 403 은 그날을 막지 않는다`() {
        val otherBody = """{"error":{"code":403,"errors":[{"reason":"forbidden"}]}}"""
        LocalServer { socket -> reply(socket, "403 Forbidden", otherBody) }.use { server ->
            val quota = YouTubeQuota()
            val client = YouTubeSearchClient(apiKey = "dummy-key", quota = quota, endpoint = server.endpoint)

            assertNull(runBlocking { client.search("고양이", 5) })
            assertTrue(!quota.isBlocked())
        }
    }

    @Test
    fun `응답이 늦으면 시간 안에 취소하고 실패로 돌려준다`() {
        // 연결만 받고 아무것도 보내지 않는 서버. 시간 한도(300ms)가 지나면 소켓을 닫아 막힌 read 를 깨운다.
        LocalServer { socket -> Thread.sleep(10_000) }.use { server ->
            val client = YouTubeSearchClient(
                apiKey = "dummy-key",
                quota = YouTubeQuota(),
                timeoutMs = 300L,
                endpoint = server.endpoint,
            )

            val started = System.currentTimeMillis()
            val ids = runBlocking { withTimeoutOrNull(3_000) { client.search("고양이", 5) } }
            val elapsed = System.currentTimeMillis() - started

            assertNull("시간이 넘으면 실패", ids)
            assertTrue("1.5초 한도 안에서 끝나야 한다: ${elapsed}ms", elapsed < 1_500)
        }
    }

    @Test
    fun `로그에는 키와 요청 주소가 남지 않는다`() {
        LocalServer { socket -> reply(socket, "200 OK", okBody) }.use { server ->
            val logs = mutableListOf<String>()
            val client = YouTubeSearchClient(
                apiKey = "dummy-key",
                quota = YouTubeQuota(),
                endpoint = server.endpoint,
                log = { logs += it },
            )

            runBlocking { client.search("고양이 영상", 5) }

            assertTrue(logs.isNotEmpty())
            assertTrue("키가 로그에 있으면 안 된다", logs.none { it.contains("dummy-key") })
            assertTrue("주소가 로그에 있으면 안 된다", logs.none { it.contains("http") || it.contains("youtube") })
            assertTrue("검색어는 글자 수만", logs.any { it.contains("검색어 ${"고양이 영상".length}자") })
        }
    }
}
