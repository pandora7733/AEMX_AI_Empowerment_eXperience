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
 * 검색 제출: 버튼은 역할로 고르고(같은 이름의 엉뚱한 노드를 누르지 않는다), 제출 뒤에는 결과가 떴을 때만 성공이다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SearchSubmitTest {

    private val pkg = "com.coupang.mobile"

    private class Events : ActionExecutor.Listener {
        var completed = false
        var failed = false
        override fun onStepStarted(index: Int, step: ActionStep) {}
        override fun onCompleted(plan: ActionPlan, evidence: String?) { completed = true }
        override fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription) { failed = true }
    }

    /** 검색 입력칸. 글자를 넣으면 칸의 글자가 바뀐다. */
    private fun field(): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg
        viewIdResourceName = "$pkg:id/searchKeyword"
        isEnabled = true
        isVisibleToUser = true
        isEditable = true
        isClickable = true
        setBoundsInScreen(Rect(142, 397, 1012, 490))
        addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        shadowOf(this).setOnPerformActionListener { action, args ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                text = args?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
            }
            true
        }
    }

    /** 입력칸 오른쪽의 검색 버튼. [onClick] 이 화면을 어떻게 바꾸는지 정한다. */
    private fun searchButton(onClick: () -> Unit): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg
        contentDescription = "검색"
        isClickable = true
        isEnabled = true
        isVisibleToUser = true
        setBoundsInScreen(Rect(1053, 397, 1147, 490))
        shadowOf(this).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) onClick()
            true
        }
    }

    private fun resultRow(text: String): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg
        this.text = text
        isVisibleToUser = true
        isEnabled = true
    }

    private fun run(root: AccessibilityNodeInfo, plan: ActionPlan, idleSeconds: Long): Events {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        shadowOf(service).setRootInActiveWindow(root)
        val events = Events()
        ActionExecutor(service, events).execute(plan)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(idleSeconds))
        return events
    }

    @Test fun `같은 이름의 엉뚱한 노드 대신 입력칸 옆 검색 버튼을 누른다`() {
        var wrongClicked = false
        val wrong = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            text = "검색"
            isClickable = true
            isEnabled = true
            isVisibleToUser = true
            setBoundsInScreen(Rect(0, 0, 200, 100))
            shadowOf(this).setOnPerformActionListener { _, _ -> wrongClicked = true; true }
        }
        var buttonClicked = false
        val input = field().apply { text = "고양이" }
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            shadowOf(this).addChild(wrong)
            shadowOf(this).addChild(input)
            shadowOf(this).addChild(searchButton { buttonClicked = true })
        }

        run(root, ActionPlan("쿠팡", listOf(ActionStep(ActionType.TAP, "검색"))), idleSeconds = 1)

        assertFalse("이름만 같은 노드는 누르지 않는다", wrongClicked)
        assertTrue("옆의 검색 버튼을 누른다", buttonClicked)
    }

    @Test fun `제출 뒤 결과 글자가 보이면 성공이다`() {
        lateinit var root: AccessibilityNodeInfo
        val input = field()
        root = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            shadowOf(this).addChild(input)
            shadowOf(this).addChild(searchButton {
                shadowOf(root).addChild(resultRow("고양이 검색결과 120건"))
            })
        }

        val events = run(
            root,
            ActionPlan("쿠팡", listOf(ActionStep(ActionType.INPUT_TEXT, "검색창", "고양이"), ActionStep(ActionType.TAP, "검색"))),
            idleSeconds = 3,
        )

        assertTrue(events.completed)
        assertFalse(events.failed)
    }

    @Test fun `제출 뒤 입력칸이 비어 버리면 성공으로 보지 않는다`() {
        lateinit var input: AccessibilityNodeInfo
        input = field()
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            shadowOf(this).addChild(input)
            shadowOf(this).addChild(searchButton { input.text = "" })
        }

        val events = run(
            root,
            ActionPlan("쿠팡", listOf(ActionStep(ActionType.INPUT_TEXT, "검색창", "고양이"), ActionStep(ActionType.TAP, "검색"))),
            idleSeconds = 3,
        )

        assertFalse(events.completed)
        assertTrue(events.failed)
    }

    @Test fun `제출했는데 결과 글자가 안 보이면 성공으로 보지 않는다`() {
        val input = field()
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            shadowOf(this).addChild(input)
            shadowOf(this).addChild(searchButton { })
        }

        val events = run(
            root,
            ActionPlan("쿠팡", listOf(ActionStep(ActionType.INPUT_TEXT, "검색창", "고양이"), ActionStep(ActionType.TAP, "검색"))),
            idleSeconds = 10,
        )

        assertFalse(events.completed)
        assertEquals(true, events.failed)
    }
}
