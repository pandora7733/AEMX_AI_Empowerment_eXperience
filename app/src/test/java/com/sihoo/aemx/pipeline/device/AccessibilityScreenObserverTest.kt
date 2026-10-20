package com.sihoo.aemx.pipeline.device

import com.sihoo.aemx.accessibility.ScreenDescription
import com.sihoo.aemx.pipeline.port.ScreenChange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityScreenObserverTest {

    /** 바꿔 가며 읽히는 화면. null 이면 읽을 수 없는 상태. */
    private class FakeSource(var reading: ScreenReading? = null, var ready: Boolean = true) : ScreenSource {
        override fun isReady() = ready
        override fun read() = reading
    }

    private fun reading(
        vararg texts: String,
        pkg: String = "com.google.android.youtube",
        title: String? = "YouTube"
    ) = ScreenReading(ScreenDescription(pkg, texts.toList(), texts.toList()), title)

    /** 글자와 누를 수 있는 라벨을 따로 정해서 만든다. */
    private fun split(
        texts: List<String>,
        clickables: List<String>,
        pkg: String = "com.nhn.android.search",
        title: String? = "Naver"
    ) = ScreenReading(ScreenDescription(pkg, texts, clickables), title)

    private fun observer(source: ScreenSource) =
        AccessibilityScreenObserver(source, pollMs = 100, pollContext = EmptyCoroutineContext)

    @Test
    fun `capture 는 서비스가 읽은 내용을 스냅샷으로 옮긴다`() {
        val source = FakeSource(reading("검색", "홈"))
        val snapshot = observer(source).capture()!!

        assertEquals("com.google.android.youtube", snapshot.packageName)
        assertEquals("YouTube", snapshot.className)
        assertEquals(listOf("검색", "홈"), snapshot.texts)
        assertEquals(listOf("검색", "홈"), snapshot.clickableLabels)
    }

    @Test
    fun `읽을 수 없으면 capture 는 null 이다`() {
        assertNull(observer(FakeSource(reading = null)).capture())
    }

    @Test
    fun `읽다가 예외가 나도 null 로 받아 넘긴다`() {
        val throwing = object : ScreenSource {
            override fun isReady() = true
            override fun read(): ScreenReading? = throw IllegalStateException("노드 무효")
        }
        assertNull(observer(throwing).capture())
    }

    @Test
    fun `isReady 는 서비스 연결 상태를 따른다`() {
        val source = FakeSource(ready = false)
        assertFalse(observer(source).isReady())
        source.ready = true
        assertTrue(observer(source).isReady())
    }

    @Test
    fun `내용이 바뀐 때만 변화가 흐른다`() = runTest {
        val source = FakeSource(reading("목록"))
        val obs = observer(source)
        obs.capture() // 오케스트레이터가 실행 직전에 한 번 찍는 것과 같다.

        val seen = mutableListOf<ScreenChange>()
        backgroundScope.launch { obs.changes.collect { seen += it } }

        advanceTimeBy(250)
        assertTrue("그대로면 변화가 없다", seen.isEmpty())

        source.reading = reading("목록", "더 보기")
        advanceTimeBy(150)
        assertEquals(1, seen.size)
        assertEquals(ScreenChange.Kind.CONTENT_CHANGED, seen[0].kind)
        assertEquals(listOf("목록", "더 보기"), seen[0].snapshot.texts)

        advanceTimeBy(300)
        assertEquals("같은 내용이 또 흐르면 안 된다", 1, seen.size)
    }

    @Test
    fun `앱이나 창이 바뀌면 WINDOW_CHANGED 다`() = runTest {
        val source = FakeSource(reading("목록"))
        val obs = observer(source)
        obs.capture()

        val seen = mutableListOf<ScreenChange>()
        backgroundScope.launch { obs.changes.collect { seen += it } }

        source.reading = reading("영상", title = "Watch")
        advanceTimeBy(150)
        assertEquals(ScreenChange.Kind.WINDOW_CHANGED, seen.single().kind)
    }

    @Test
    fun `구독 전에 이미 바뀐 화면도 기준과 달라서 첫 폴링에서 잡힌다`() = runTest {
        val source = FakeSource(reading("전"))
        val obs = observer(source)
        obs.capture()
        source.reading = reading("후")

        val seen = mutableListOf<ScreenChange>()
        backgroundScope.launch { obs.changes.collect { seen += it } }
        advanceTimeBy(150)

        assertEquals(listOf("후"), seen.single().snapshot.texts)
    }

    @Test
    fun `읽을 수 없는 순간은 건너뛰고 변화로 치지 않는다`() = runTest {
        val source = FakeSource(reading("목록"))
        val obs = observer(source)
        obs.capture()

        val seen = mutableListOf<ScreenChange>()
        backgroundScope.launch { obs.changes.collect { seen += it } }

        source.reading = null
        advanceTimeBy(350)
        assertTrue(seen.isEmpty())

        source.reading = reading("목록")
        advanceTimeBy(150)
        assertTrue("같은 화면으로 돌아왔으니 변화가 아니다", seen.isEmpty())
    }

    @Test
    fun `일반 글자만 바뀌면 변화로 보지 않는다`() = runTest {
        val source = FakeSource(split(listOf("배너 1", "검색"), listOf("검색")))
        val obs = observer(source)
        obs.capture()

        val seen = mutableListOf<ScreenChange>()
        backgroundScope.launch { obs.changes.collect { seen += it } }

        source.reading = split(listOf("배너 2", "검색"), listOf("검색"))
        advanceTimeBy(150)
        source.reading = split(listOf("배너 3", "검색"), listOf("검색"))
        advanceTimeBy(150)
        assertTrue("배너 글자 변화는 무시", seen.isEmpty())
    }

    @Test
    fun `누를 수 있는 라벨이 바뀌면 변화로 본다`() = runTest {
        val source = FakeSource(split(listOf("검색", "홈"), listOf("검색", "홈")))
        val obs = observer(source)
        obs.capture()

        val seen = mutableListOf<ScreenChange>()
        backgroundScope.launch { obs.changes.collect { seen += it } }

        source.reading = split(listOf("검색", "홈", "더 보기"), listOf("검색", "홈", "더 보기"))
        advanceTimeBy(150)
        assertEquals(ScreenChange.Kind.CONTENT_CHANGED, seen.single().kind)
    }

    @Test
    fun `라벨 순서만 바뀌는 건 변화가 아니다`() {
        val a = split(listOf("가", "나"), listOf("가", "나")).toSnapshot()
        val b = split(listOf("나", "가"), listOf("나", "가")).toSnapshot()
        assertTrue(a.sameContent(b))
    }

    @Test
    fun `패키지가 바뀌면 변화로 본다`() = runTest {
        val source = FakeSource(split(listOf("검색"), listOf("검색")))
        val obs = observer(source)
        obs.capture()

        val seen = mutableListOf<ScreenChange>()
        backgroundScope.launch { obs.changes.collect { seen += it } }

        source.reading = split(listOf("검색"), listOf("검색"), pkg = "com.google.android.youtube")
        advanceTimeBy(150)
        assertEquals(ScreenChange.Kind.WINDOW_CHANGED, seen.single().kind)
    }

    @Test
    fun `누를 수 있는 라벨이 없는 화면은 글자까지 비교한다`() {
        val a = split(listOf("로딩 중"), emptyList()).toSnapshot()
        val b = split(listOf("결과 3건"), emptyList()).toSnapshot()
        assertFalse(a.sameContent(b))
    }

    @Test
    fun `스냅샷에 담는 글자와 라벨 수에 상한이 있다`() {
        val texts = (1..100).map { "글자$it" }
        val snapshot = split(texts, texts).toSnapshot()

        assertEquals(MAX_SNAPSHOT_TEXTS, snapshot.texts.size)
        assertEquals(MAX_SNAPSHOT_CLICKABLES, snapshot.clickableLabels.size)
        assertEquals("앞쪽부터 남긴다", "글자1", snapshot.texts.first())
    }
}
