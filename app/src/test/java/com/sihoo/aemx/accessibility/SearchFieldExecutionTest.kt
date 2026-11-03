package com.sihoo.aemx.accessibility

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * 교보문고 화면 구성(글자 없는 검색 입력칸, 옆의 검색 버튼)에서 역할로 칸과 버튼을 고르는 흐름.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SearchFieldExecutionTest {

    private val kyobo = "mok.android"

    private class Events : ActionExecutor.Listener {
        var completed = false
        var failed = false
        override fun onStepStarted(index: Int, step: ActionStep) {}
        override fun onCompleted(plan: ActionPlan, evidence: String?) { completed = true }
        override fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription) { failed = true }
    }

    private fun node(pkg: String) = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg
        isEnabled = true
        isVisibleToUser = true
    }

    /** 글자 없는 검색 입력칸. 설정 글자를 SET_TEXT 로 받으면 칸의 글자가 바뀐다. */
    private fun searchField(pkg: String, acceptSetText: Boolean, typed: MutableList<String>) = node(pkg).apply {
        viewIdResourceName = "$pkg:id/searchMainKeyword"
        isEditable = true
        isClickable = true
        setBoundsInScreen(Rect(142, 397, 1012, 490))
        addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        shadowOf(this).setOnPerformActionListener { action, args ->
            when (action) {
                AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                    if (!acceptSetText) return@setOnPerformActionListener false
                    val value = args?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                    text = value
                    typed += value.toString()
                    true
                }
                AccessibilityNodeInfo.ACTION_PASTE -> {
                    val clip = (RuntimeEnvironment.getApplication()
                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                    text = clip?.getItemAt(0)?.text
                    typed += text.toString()
                    true
                }
                else -> true
            }
        }
    }

    private fun play(root: AccessibilityNodeInfo, service: AemxAccessibilityService, plan: ActionPlan, idleSeconds: Long): Events {
        shadowOf(service).setRootInActiveWindow(root)
        val events = Events()
        ActionExecutor(service, events).execute(plan)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(idleSeconds))
        return events
    }

    @Test
    fun `이름이 맞지 않아도 검색 입력칸을 역할로 찾아 글자를 넣는다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val typed = mutableListOf<String>()
        val root = node(kyobo).apply { shadowOf(this).addChild(searchField(kyobo, acceptSetText = true, typed = typed)) }

        val events = play(root, service, ActionPlan("교보문고", listOf(ActionStep(ActionType.INPUT_TEXT, "검색창", "고양이"))), idleSeconds = 2)

        assertEquals(listOf("고양이"), typed)
        assertTrue(events.completed)
    }

    @Test
    fun `글자 넣기를 받지 않는 입력칸은 포커스와 붙여넣기로 넣는다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val typed = mutableListOf<String>()
        val root = node(kyobo).apply { shadowOf(this).addChild(searchField(kyobo, acceptSetText = false, typed = typed)) }

        val events = play(root, service, ActionPlan("교보문고", listOf(ActionStep(ActionType.INPUT_TEXT, "검색창", "고양이"))), idleSeconds = 2)

        assertEquals(listOf("고양이"), typed)
        assertTrue(events.completed)
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("고양이", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `검색 버튼 말은 입력칸 옆의 검색 버튼을 누른다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        var clicked = false
        val button = node(kyobo).apply {
            text = "search button"
            isClickable = true
            setBoundsInScreen(Rect(1053, 397, 1147, 490))
            shadowOf(this).setOnPerformActionListener { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_CLICK) clicked = true
                action == AccessibilityNodeInfo.ACTION_CLICK
            }
        }
        val root = node(kyobo).apply {
            shadowOf(this).addChild(searchField(kyobo, acceptSetText = true, typed = mutableListOf()))
            shadowOf(this).addChild(button)
        }

        val events = play(root, service, ActionPlan("교보문고", listOf(ActionStep(ActionType.TAP, "검색 버튼"))), idleSeconds = 2)

        assertTrue("검색 버튼을 눌러야 한다", clicked)
        assertTrue(events.completed)
    }
}

/** 검색 버튼이 없을 때 입력칸의 엔터(IME)로 검색한다. 이 기능은 API 30 이상에서만 쓴다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class SearchFieldImeTest {

    @Test
    fun `검색 버튼이 없으면 입력칸에 엔터를 보내 검색한다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        var entered = false
        val field = AccessibilityNodeInfo.obtain().apply {
            packageName = "mok.android"
            viewIdResourceName = "mok.android:id/searchMainKeyword"
            isEditable = true
            isEnabled = true
            isVisibleToUser = true
            setBoundsInScreen(Rect(142, 397, 1012, 490))
            shadowOf(this).setOnPerformActionListener { action, _ ->
                if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) entered = true
                entered
            }
        }
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = "mok.android"
            isEnabled = true
            isVisibleToUser = true
            shadowOf(this).addChild(field)
        }
        shadowOf(service).setRootInActiveWindow(root)
        var completed = false
        val listener = object : ActionExecutor.Listener {
            override fun onStepStarted(index: Int, step: ActionStep) {}
            override fun onCompleted(plan: ActionPlan, evidence: String?) { completed = true }
            override fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription) {}
        }

        ActionExecutor(service, listener).execute(ActionPlan("교보문고", listOf(ActionStep(ActionType.TAP, "검색 버튼"))))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertTrue("입력칸에 엔터를 보내야 한다", entered)
        assertTrue(completed)
    }
}
