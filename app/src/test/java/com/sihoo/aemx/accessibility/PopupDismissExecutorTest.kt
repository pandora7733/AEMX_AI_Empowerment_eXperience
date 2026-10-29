package com.sihoo.aemx.accessibility

import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
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
 * 대상 앱 화면에 뜬 팝업을 닫는 흐름. 누를 대상을 못 찾은 조작 앞에서 닫기 버튼을 누르고, 금지 버튼은 누르지 않는다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PopupDismissExecutorTest {

    private class Events : ActionExecutor.Listener {
        var completed = false
        var failed = false
        override fun onStepStarted(index: Int, step: ActionStep) {}
        override fun onCompleted(plan: ActionPlan, evidence: String?) { completed = true }
        override fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription) { failed = true }
    }

    private fun node(label: String, pkg: String) = AccessibilityNodeInfo.obtain().apply {
        text = label
        packageName = pkg
        isEnabled = true
        isVisibleToUser = true
        isClickable = true
    }

    /** 교보문고 화면(검색창 없음)에 팝업 버튼 하나. 누르면 [clicks] 에 라벨을 남긴다. */
    private fun appWithButton(button: String, clicks: MutableList<String>): AccessibilityNodeInfo {
        val pkg = AppLauncher.YOUTUBE_PACKAGE
        val root = node("광고 안내", pkg).apply { isClickable = false }
        val close = node(button, pkg)
        shadowOf(close).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) clicks += button
            true
        }
        shadowOf(root).addChild(close)
        return root
    }

    @Test
    fun `대상 조작 앞에서 닫기 버튼을 누르고 다시 시도한다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val clicks = mutableListOf<String>()
        shadowOf(service).setRootInActiveWindow(appWithButton("닫기", clicks))
        val events = Events()

        ActionExecutor(service, events).execute(ActionPlan("유튜브", listOf(
            ActionStep(ActionType.TAP, "검색창"),
        )))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertTrue("닫기 버튼을 눌러야 한다", clicks.contains("닫기"))
    }

    @Test
    fun `결제 버튼은 팝업처럼 보여도 누르지 않는다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val clicks = mutableListOf<String>()
        shadowOf(service).setRootInActiveWindow(appWithButton("결제", clicks))
        val events = Events()

        ActionExecutor(service, events).execute(ActionPlan("유튜브", listOf(
            ActionStep(ActionType.TAP, "검색창"),
        )))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertFalse("결제 버튼을 누르면 안 된다", clicks.contains("결제"))
    }

    @Test
    fun `한 단계에서 팝업 닫기는 두 번까지만 한다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val clicks = mutableListOf<String>()
        // 닫기 버튼이 계속 남아 있어도(닫히지 않는 팝업) 두 번 넘게 누르지 않는다.
        shadowOf(service).setRootInActiveWindow(appWithButton("닫기", clicks))
        val events = Events()

        ActionExecutor(service, events).execute(ActionPlan("유튜브", listOf(
            ActionStep(ActionType.TAP, "검색창"),
        )))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))

        assertTrue("두 번까지만 누른다: ${clicks.size}번", clicks.size <= 2)
        assertFalse(events.completed)
    }
}
