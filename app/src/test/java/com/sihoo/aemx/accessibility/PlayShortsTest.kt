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
 * 재생에서 쇼츠를 고르는 규칙. 일반 영상이 보이면 일반 영상을 고르고, 쇼츠만 보이면 잠깐 기다린 뒤에야 쇼츠를 고른다.
 * 사용자가 쇼츠를 말한 명령은 바로 쇼츠를 고른다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PlayShortsTest {

    private val youtube = AppLauncher.YOUTUBE_PACKAGE

    private fun item(title: String, description: String, clicked: MutableList<String>) =
        AccessibilityNodeInfo.obtain().apply {
            text = title
            packageName = youtube
            isEnabled = true
            isVisibleToUser = true
            isClickable = true
            contentDescription = description
            shadowOf(this).setOnPerformActionListener { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_CLICK) clicked += title
                action == AccessibilityNodeInfo.ACTION_CLICK
            }
        }

    private fun page(vararg items: AccessibilityNodeInfo) = AccessibilityNodeInfo.obtain().apply {
        text = "결과"
        packageName = youtube
        isEnabled = true
        isVisibleToUser = true
        items.forEach { shadowOf(this).addChild(it) }
    }

    private fun play(target: String, root: AccessibilityNodeInfo, idleSeconds: Long, clicked: List<String>): List<String> {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        shadowOf(service).setRootInActiveWindow(root)
        ActionExecutor(service, object : ActionExecutor.Listener {
            override fun onStepStarted(index: Int, step: ActionStep) {}
            override fun onCompleted(plan: ActionPlan, evidence: String?) {}
            override fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription) {}
        }).execute(ActionPlan("유튜브", listOf(ActionStep(ActionType.PLAY_FIRST, target))))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(idleSeconds))
        return clicked
    }

    @Test
    fun `쇼츠만 보이면 바로 누르지 않고 잠깐 기다린 뒤에야 쇼츠를 고른다`() {
        val clicked = mutableListOf<String>()
        val root = page(item("타르코프", "타르코프 - Shorts 동영상 재생", clicked))
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        shadowOf(service).setRootInActiveWindow(root)
        ActionExecutor(service, object : ActionExecutor.Listener {
            override fun onStepStarted(index: Int, step: ActionStep) {}
            override fun onCompleted(plan: ActionPlan, evidence: String?) {}
            override fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription) {}
        }).execute(ActionPlan("유튜브", listOf(ActionStep(ActionType.PLAY_FIRST, "첫 영상"))))

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse("1초 안에는 쇼츠를 누르지 않는다", clicked.isNotEmpty())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(listOf("타르코프"), clicked)
    }

    @Test
    fun `일반 영상이 보이면 쇼츠 대신 일반 영상을 고른다`() {
        val clicked = mutableListOf<String>()
        val root = page(
            item("타르코프 쇼츠", "타르코프 - Shorts 동영상 재생", clicked),
            item("타르코프 영상", "타르코프 영상 - 3분 - 채널 - 동영상 재생", clicked),
        )

        play("첫 영상", root, idleSeconds = 1, clicked = clicked)

        assertEquals(listOf("타르코프 영상"), clicked)
    }

    @Test
    fun `쇼츠라고 말한 명령은 바로 쇼츠를 고른다`() {
        val clicked = mutableListOf<String>()
        val root = page(item("타르코프", "타르코프 - Shorts 동영상 재생", clicked))

        play("쇼츠 첫 영상", root, idleSeconds = 1, clicked = clicked)

        assertTrue("쇼츠를 말했으면 1초 안에 고른다", clicked == listOf("타르코프"))
        assertEquals(1, clicked.size)
        assertFalse(clicked.isEmpty())
    }
}
