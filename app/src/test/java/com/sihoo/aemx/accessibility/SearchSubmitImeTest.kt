package com.sihoo.aemx.accessibility

import android.graphics.Rect
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
 * IME 엔터(ACTION_IME_ENTER)를 받아도 결과가 안 뜨면 성공으로 보지 않는다(쿠팡: 엔터를 무시하고 자동완성만 남았다).
 * 입력기 연결 방법은 API 33 이상에서만 있어 여기서는 API 30 의 IME 엔터까지만 본다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class SearchSubmitImeTest {

    private val pkg = "com.coupang.mobile"

    private class Events : ActionExecutor.Listener {
        var completed = false
        var failed = false
        override fun onStepStarted(index: Int, step: ActionStep) {}
        override fun onCompleted(plan: ActionPlan, evidence: String?) { completed = true }
        override fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription) { failed = true }
    }

    @Test fun `엔터를 받아도 결과가 안 뜨면 실패로 알린다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        var imeEnters = 0
        val input = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            viewIdResourceName = "$pkg:id/edit_text"
            // 쿠팡 칸은 힌트로 "검색어 입력"을 보여 준다. 역할로 검색 입력칸을 고르는 근거다.
            hintText = "검색어 입력"
            isEnabled = true
            isVisibleToUser = true
            isEditable = true
            isFocused = true
            setBoundsInScreen(Rect(142, 397, 1012, 490))
            addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
            shadowOf(this).setOnPerformActionListener { action, args ->
                when (action) {
                    AccessibilityNodeInfo.ACTION_SET_TEXT ->
                        text = args?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id -> imeEnters++
                }
                true
            }
        }
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            shadowOf(this).addChild(input)
        }
        shadowOf(service).setRootInActiveWindow(root)
        val events = Events()

        ActionExecutor(service, events).execute(
            ActionPlan("쿠팡", listOf(ActionStep(ActionType.INPUT_TEXT, "검색창", "고양이"), ActionStep(ActionType.TAP, "검색"))),
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        assertEquals("엔터는 한 번 보냈다", 1, imeEnters)
        assertFalse(events.completed)
        assertTrue(events.failed)
    }
}
