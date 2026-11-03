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
 * 입력 뒤 칸을 다시 읽는 확인. 쿠팡처럼 글자 넣기를 받아 놓고 힌트만 보이는 칸은 실패로 알린다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SearchInputVerifyTest {

    private val coupang = "com.coupang.mobile"

    private class Events : ActionExecutor.Listener {
        var completed = false
        var failed = false
        override fun onStepStarted(index: Int, step: ActionStep) {}
        override fun onCompleted(plan: ActionPlan, evidence: String?) { completed = true }
        override fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription) { failed = true }
    }

    /** 힌트만 보이는 검색 입력칸. [keepsText] 가 참이면 SET_TEXT 와 붙여넣기가 실제로 글자를 바꾼다. */
    private fun hintField(performed: MutableList<String>, keepsText: Boolean) = AccessibilityNodeInfo.obtain().apply {
        packageName = coupang
        isEnabled = true
        isVisibleToUser = true
        isEditable = true
        isClickable = true
        viewIdResourceName = "$coupang:id/searchKeyword"
        hintText = "검색어 입력"
        setShowingHintText(true)
        setBoundsInScreen(Rect(142, 397, 1012, 490))
        addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        shadowOf(this).setOnPerformActionListener { action, args ->
            when (action) {
                AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                    performed += "set"
                    if (keepsText) {
                        text = args?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                        setShowingHintText(false)
                    }
                    true
                }
                AccessibilityNodeInfo.ACTION_PASTE -> {
                    performed += "paste"
                    if (keepsText) {
                        text = "고양이"
                        setShowingHintText(false)
                    }
                    true
                }
                else -> true
            }
        }
    }

    @Test fun `글자 넣기를 받아도 칸이 비어 있으면 다른 방법으로 넣고, 끝까지 안 들어가면 실패한다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val performed = mutableListOf<String>()
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = coupang
            shadowOf(this).addChild(hintField(performed, keepsText = false))
        }
        shadowOf(service).setRootInActiveWindow(root)
        val events = Events()

        ActionExecutor(service, events).execute(
            ActionPlan("쿠팡", listOf(ActionStep(ActionType.INPUT_TEXT, "검색창", "고양이"))),
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))

        assertTrue("칸이 비어 있으면 실패로 알려야 한다", events.failed)
        assertFalse(events.completed)
        assertTrue("붙여넣기까지 시도해야 한다", performed.contains("paste"))
    }

    @Test fun `글자가 칸에 보이면 한 번만 넣고 끝낸다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val performed = mutableListOf<String>()
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = coupang
            shadowOf(this).addChild(hintField(performed, keepsText = true))
        }
        shadowOf(service).setRootInActiveWindow(root)
        val events = Events()

        ActionExecutor(service, events).execute(
            ActionPlan("쿠팡", listOf(ActionStep(ActionType.INPUT_TEXT, "검색창", "고양이"))),
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(listOf("set"), performed)
        assertTrue(events.completed)
    }

    @Test fun `입력칸이 비어 있으면 검색 버튼을 누르지 않는다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        var clicked = false
        val button = AccessibilityNodeInfo.obtain().apply {
            packageName = coupang
            text = "검색"
            isClickable = true
            isEnabled = true
            isVisibleToUser = true
            setBoundsInScreen(Rect(1053, 397, 1147, 490))
            shadowOf(this).setOnPerformActionListener { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_CLICK) clicked = true
                true
            }
        }
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = coupang
            shadowOf(this).addChild(hintField(mutableListOf(), keepsText = false))
            shadowOf(this).addChild(button)
        }
        shadowOf(service).setRootInActiveWindow(root)
        val events = Events()

        ActionExecutor(service, events).execute(ActionPlan("쿠팡", listOf(ActionStep(ActionType.TAP, "검색 버튼"))))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertFalse("빈 칸에서는 검색 버튼을 누르면 안 된다", clicked)
        assertTrue(events.failed)
    }
}
