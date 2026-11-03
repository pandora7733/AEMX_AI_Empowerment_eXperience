package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import com.sihoo.aemx.pipeline.youtube.YouTubeQuota
import com.sihoo.aemx.pipeline.youtube.YouTubeSearchClient
import com.sihoo.aemx.pipeline.youtube.YouTubeSearchParser
import com.sihoo.aemx.pipeline.youtube.YouTubeShortcut
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** API 결과에서 쇼츠를 거르는 규칙과 유닛 예산. 로컬 소켓 서버로 응답을 흉내 낸다. */
class YouTubeShortsFilterTest {

    /** 요청마다 같은 본문을 주고, 받은 요청 수를 센다. */
    private class Server(private val body: String) : AutoCloseable {
        private val server = ServerSocket(0)
        val requests = AtomicInteger(0)
        val endpoint: String get() = "http://127.0.0.1:${server.localPort}/v3/x"
        private val thread = Thread {
            runCatching {
                while (!server.isClosed) {
                    val socket = server.accept()
                    requests.incrementAndGet()
                    socket.use {
                        val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val writer = OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8)
                        writer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                        writer.write(body)
                        writer.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            server.close()
        }
    }

    private val searchBody = """{"items":[
        {"id":{"videoId":"shortsA11"},"snippet":{"title":"고양이 #shorts"}},
        {"id":{"videoId":"longB222"},"snippet":{"title":"고양이 영상 3분"}},
        {"id":{"videoId":"shortC33"},"snippet":{"title":"고양이 짧은 영상"}}
    ]}"""

    private val videosBody = """{"items":[
        {"id":"longB222","contentDetails":{"duration":"PT3M5S"}},
        {"id":"shortC33","contentDetails":{"duration":"PT40S"}}
    ]}"""

    // ------------------------------------------------------------ 파싱

    @Test
    fun `ISO 8601 길이를 초로 읽는다`() {
        assertEquals(45, YouTubeSearchParser.parseDurationSeconds("PT45S"))
        assertEquals(62, YouTubeSearchParser.parseDurationSeconds("PT1M2S"))
        assertEquals(3_600, YouTubeSearchParser.parseDurationSeconds("PT1H"))
        assertEquals(600, YouTubeSearchParser.parseDurationSeconds("PT10M"))
        assertNull(YouTubeSearchParser.parseDurationSeconds("P1D"))
        assertNull(YouTubeSearchParser.parseDurationSeconds(""))
    }

    @Test
    fun `제목에 shorts 가 있으면 쇼츠로 본다`() {
        assertTrue(YouTubeSearchParser.isShortTitle("고양이 #Shorts"))
        assertTrue(YouTubeSearchParser.isShortTitle("#shorts 모음"))
        assertFalse(YouTubeSearchParser.isShortTitle("고양이 영상"))
    }

    @Test
    fun `검색 응답에서 제목을 읽고 길이 응답에서 영상별 길이를 읽는다`() {
        val items = YouTubeSearchParser.videoItems(searchBody)
        assertEquals("고양이 #shorts", items[0].title)
        assertEquals(3, items.size)

        val durations = YouTubeSearchParser.durations(videosBody)
        assertEquals(185, durations["longB222"])
        assertEquals(40, durations["shortC33"])
    }

    // ------------------------------------------------------------ 거르기

    @Test
    fun `쇼츠 제목과 1분 이하 영상을 빼고 남은 영상만 순서대로 돌려준다`() {
        Server(searchBody).use { search ->
            Server(videosBody).use { videos ->
                val client = YouTubeSearchClient(
                    apiKey = "dummy-key",
                    quota = YouTubeQuota(),
                    endpoint = search.endpoint,
                    videosEndpoint = videos.endpoint,
                )

                val ids = runBlocking { client.search("고양이", 5) }

                assertEquals(listOf("longB222"), ids)
                assertEquals(1, videos.requests.get())
            }
        }
    }

    @Test
    fun `길이를 모르는 영상은 쇼츠인지 알 수 없으니 남긴다`() {
        val noDuration = """{"items":[]}"""
        Server(searchBody).use { search ->
            Server(noDuration).use { videos ->
                val client = YouTubeSearchClient(
                    apiKey = "dummy-key",
                    quota = YouTubeQuota(),
                    endpoint = search.endpoint,
                    videosEndpoint = videos.endpoint,
                )

                val ids = runBlocking { client.search("고양이", 5) }

                assertEquals(listOf("longB222", "shortC33"), ids)
            }
        }
    }

    @Test
    fun `쇼츠만 있으면 길이를 묻지 않고 빈 목록을 돌려준다`() {
        val onlyShorts = """{"items":[{"id":{"videoId":"shortsA11"},"snippet":{"title":"x #shorts"}}]}"""
        Server(onlyShorts).use { search ->
            Server(videosBody).use { videos ->
                val client = YouTubeSearchClient(
                    apiKey = "dummy-key",
                    quota = YouTubeQuota(),
                    endpoint = search.endpoint,
                    videosEndpoint = videos.endpoint,
                )

                val ids = runBlocking { client.search("고양이", 5) }

                assertEquals(emptyList<String>(), ids)
                assertEquals("길이 조회는 하지 않는다", 0, videos.requests.get())
            }
        }
    }

    // ------------------------------------------------------------ 유닛 예산

    @Test
    fun `검색 한 번은 100유닛과 길이 조회 1유닛을 쓴다`() {
        Server(searchBody).use { search ->
            Server(videosBody).use { videos ->
                val quota = YouTubeQuota()
                val client = YouTubeSearchClient(
                    apiKey = "dummy-key",
                    quota = quota,
                    endpoint = search.endpoint,
                    videosEndpoint = videos.endpoint,
                )

                runBlocking { client.search("고양이", 5) }

                assertEquals(YouTubeQuota.SEARCH_UNITS + YouTubeQuota.VIDEOS_UNITS, quota.unitsToday())
            }
        }
    }

    @Test
    fun `하루 예산을 넘으면 막고 그날은 더 부르지 않는다`() {
        var now = 1_000_000L
        val quota = YouTubeQuota { now }

        assertTrue(quota.reserve(YouTubeQuota.DAILY_UNITS - 50))
        assertFalse("예산을 넘는 호출은 떼지 못한다", quota.reserve(100))
        assertTrue(quota.isBlocked())
        assertFalse(quota.reserve(1))

        now += 2L * 24 * 60 * 60 * 1000
        assertFalse("날이 지나면 막힘이 풀린다", quota.isBlocked())
        assertTrue(quota.reserve(1))
        assertEquals(1, quota.unitsToday())
    }

    // ------------------------------------------------------------ 쇼츠를 말한 명령

    @Test
    fun `쇼츠를 말한 명령은 API 를 쓰지 않는다`() {
        val plan = ActionPlan(
            "유튜브",
            listOf(
                ActionStep(0, ActionType.SEARCH, "고양이"),
                ActionStep(1, ActionType.PLAY_FIRST, "첫 쇼츠", null),
            ),
        )

        assertNull(YouTubeShortcut.target(plan, "유튜브에서 고양이 쇼츠 첫 번째 틀어줘"))
        assertNull(YouTubeShortcut.target(plan, "유튜브 shorts 고양이 틀어줘"))
    }
}
