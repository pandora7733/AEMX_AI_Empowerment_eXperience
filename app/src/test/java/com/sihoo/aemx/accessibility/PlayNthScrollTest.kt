package com.sihoo.aemx.accessibility

import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * N번째 재생이 스크롤하며 이어 세는지. 화면 한 장이 [pages] 의 한 쪽이고, 스크롤하면 다음 쪽이 보인다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PlayNthScrollTest {

    private class Events : ActionExecutor.Listener {
        var completed = false
        var failed = false
        var failureMessage: String? = null
        override fun onStepStarted(index: Int, step: ActionStep) {}
        override fun onCompleted(plan: ActionPlan, evidence: String?) { completed = true }
        override fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription) {
            failed = true
            failureMessage = message
        }
    }

    private val youtube = AppLauncher.YOUTUBE_PACKAGE

    private fun video(title: String, clicked: MutableList<String>) =
        AccessibilityNodeInfo.obtain().apply {
            text = title
            packageName = youtube
            isEnabled = true
            isVisibleToUser = true
            isClickable = true
            contentDescription = "$title - 3분 - 채널 채널로 이동 - 동영상 재생"
            shadowOf(this).setOnPerformActionListener { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_CLICK) clicked += title
                action == AccessibilityNodeInfo.ACTION_CLICK
            }
        }

    /** 한 쪽의 목록. 스크롤하면 [onScroll] 로 다음 쪽을 보여 준다. */
    private fun page(titles: List<String>, clicked: MutableList<String>, scrollTo: () -> Unit): AccessibilityNodeInfo {
        val list = AccessibilityNodeInfo.obtain().apply {
            packageName = youtube
            isEnabled = true
            isVisibleToUser = true
            isScrollable = true
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
        }
        titles.forEach { shadowOf(list).addChild(video(it, clicked)) }
        shadowOf(list).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id) {
                scrollTo()
                true
            } else {
                false
            }
        }
        return list
    }

    /** 쪽 목록을 차례로 보여 주는 화면. 스크롤마다 다음 쪽으로 넘어간다. */
    private fun playWithPages(pages: List<List<String>>, ordinal: Int): Pair<Events, List<String>> {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val clicked = mutableListOf<String>()
        var current = 0
        lateinit var built: List<AccessibilityNodeInfo>
        built = pages.mapIndexed { i, titles ->
            page(titles, clicked) {
                current = minOf(i + 1, pages.lastIndex)
                shadowOf(service).setRootInActiveWindow(built[current])
            }
        }
        shadowOf(service).setRootInActiveWindow(built[0])
        val events = Events()
        ActionExecutor(service, events).execute(ActionPlan("유튜브", listOf(
            ActionStep(ActionType.PLAY_FIRST, "${ordinal}번째 영상", value = ordinal.toString()),
        )))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        return events to clicked
    }

    @Test
    fun `세 번 스크롤해서 다섯 번째 영상을 찾아 재생한다`() {
        val (events, clicked) = playWithPages(
            pages = listOf(
                listOf("영상1", "영상2"),
                listOf("영상3"),
                listOf("영상4"),
                listOf("영상5"),
            ),
            ordinal = 5,
        )

        assertEquals(listOf("영상5"), clicked)
        assertTrue(events.completed)
    }

    @Test
    fun `스크롤해도 새 항목이 없으면 실패한다`() {
        val (events, clicked) = playWithPages(
            pages = listOf(
                listOf("영상1"),
                listOf("영상1"), // 스크롤했지만 같은 항목만 보인다 = 끝
            ),
            ordinal = 5,
        )

        assertTrue(events.failed)
        assertFalse(events.completed)
        assertEquals("5번째 항목을 찾지 못했습니다.", events.failureMessage)
        assertTrue(clicked.isEmpty())
    }

    @Test
    fun `중복 항목은 빼고 세어서 다섯 번째를 찾는다`() {
        val (events, clicked) = playWithPages(
            pages = listOf(
                listOf("영상1", "영상2"),
                listOf("영상2", "영상3"), // 영상2는 이미 셌다
                listOf("영상4", "영상5"),
            ),
            ordinal = 5,
        )

        assertEquals(listOf("영상5"), clicked)
        assertTrue(events.completed)
    }

    @Test
    fun `스크롤은 세 번까지만 하고 그 뒤에는 실패한다`() {
        // 다섯째는 네 번째 쪽에 있어서 세 번 스크롤로는 닿지 않는다.
        val (events, clicked) = playWithPages(
            pages = listOf(
                listOf("영상1"),
                listOf("영상2"),
                listOf("영상3"),
                listOf("영상4"),
                listOf("영상5"),
            ),
            ordinal = 5,
        )

        assertTrue(events.failed)
        assertTrue("잘못 누르면 안 된다: $clicked", clicked.isEmpty())
    }
}
