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

/** 검색 단계는 이번 검색어가 화면에 보일 때만 성공한다. 옛 검색 결과 화면은 성공이 아니다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SearchTermTest {

    private class Events : ActionExecutor.Listener {
        var completed = false
        var failed = false
        var failureMessage: String? = null
        override fun onStepStarted(index: Int, step: ActionStep) {}
        override fun onCompleted(plan: ActionPlan, evidence: String?) { completed = true }
        override fun onFailed(
            index: Int,
            step: ActionStep,
            message: String,
            screen: ScreenDescription,
        ) {
            failed = true
            failureMessage = message
        }
    }

    private fun screen(label: String, pkg: String = AppLauncher.YOUTUBE_PACKAGE) =
        AccessibilityNodeInfo.obtain().apply {
            text = label
            packageName = pkg
            isEnabled = true
            isVisibleToUser = true
        }

    @Test
    fun `옛 검색 결과 화면이 앞에 있으면 검색 단계는 성공하지 않는다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        shadowOf(service).setRootInActiveWindow(screen("고양이 영상 - YouTube"))
        val events = Events()

        ActionExecutor(service, events).execute(
            ActionPlan("유튜브", listOf(ActionStep(ActionType.SEARCH, "손흥민 하이라이트"))),
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        assertFalse(events.completed)
        assertFalse(events.failed)
    }

    @Test
    fun `옛 화면이 끝내 안 바뀌면 상한에서 검색어가 없다고 실패한다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        shadowOf(service).setRootInActiveWindow(screen("고양이 영상 - YouTube"))
        val events = Events()

        ActionExecutor(service, events).execute(
            ActionPlan("유튜브", listOf(ActionStep(ActionType.SEARCH, "손흥민 하이라이트"))),
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))

        assertTrue(events.failed)
        assertFalse(events.completed)
        assertEquals("검색 결과 화면에 검색어가 보이지 않았습니다.", events.failureMessage)
    }

    @Test
    fun `검색어가 보이면 안정 대기 3초를 기다리지 않고 바로 성공한다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        shadowOf(service).setRootInActiveWindow(screen("손흥민 하이라이트 - YouTube"))
        val events = Events()

        ActionExecutor(service, events).execute(
            ActionPlan("유튜브", listOf(ActionStep(ActionType.SEARCH, "손흥민 하이라이트"))),
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertTrue(events.completed)
    }

    @Test
    fun `검색어는 공백과 대소문자를 무시하고 content-desc 에서도 찾는다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val root = screen("결과")
        val row = screen("").apply {
            text = null
            contentDescription = "Son Heung-min HIGHLIGHTS - 동영상 재생"
        }
        shadowOf(root).addChild(row)
        shadowOf(service).setRootInActiveWindow(root)
        val events = Events()

        ActionExecutor(service, events).execute(
            ActionPlan("유튜브", listOf(ActionStep(ActionType.SEARCH, "son heung-min highlights"))),
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertTrue(events.completed)
    }
}
