package com.sihoo.aemx.assistant.overlay

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.sihoo.aemx.R
import com.sihoo.aemx.assistant.AssistantPhase
import com.sihoo.aemx.assistant.AssistantStore
import com.sihoo.aemx.assistant.AssistantUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 보내기 확인 패널의 후보 칩을 실제 뷰로 눌러 본다(Robolectric). 칩 탭이 선택 콜백까지 가는지, 고른 뒤 보내기가 켜지는지 본다.
 * 창 위치와 터치 통과는 여기서 재현하지 못한다(창 매니저가 없다).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConfirmChipTapTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun confirmState(choices: List<String>, selected: String?) = AssistantUiState(
        runId = "r1",
        sequence = 2,
        phase = AssistantPhase.ConfirmingAction,
        confirmRecipient = selected ?: "",
        confirmContent = "테스트",
        confirmChoices = choices,
        confirmSelected = selected,
        expanded = true,
    )

    private fun ExpandedPanelView.show(state: AssistantUiState) {
        bind(state, CapsuleTextFactory.from(state, ""))
    }

    /** 뷰 트리에서 글자가 [text] 인 TextView 를 찾는다. */
    private fun find(root: View, text: String): TextView? {
        if (root is TextView && root.text.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                find(root.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    @Test fun `칩을 누르면 그 이름이 선택 콜백으로 간다`() {
        var chosen: String? = null
        val panel = ExpandedPanelView(
            context,
            onStop = {},
            onContinue = {},
            onCollapse = {},
            onChoose = { chosen = it },
        )
        panel.show(confirmState(listOf("김정우", "이정우", "정우"), selected = null))

        val chip = find(panel, "이정우")
        assertNotNull("칩이 보여야 한다", chip)
        assertTrue("칩은 눌릴 수 있어야 한다", chip!!.isClickable)
        assertTrue("칩은 화면에 보여야 한다", chip.isShown || chip.visibility == View.VISIBLE)
        chip.performClick()
        assertEquals("이정우", chosen)
    }

    @Test fun `후보가 여럿이면 고르기 전 보내기는 막혀 있다`() {
        val panel = ExpandedPanelView(context, onStop = {}, onContinue = {}, onCollapse = {})
        panel.show(confirmState(listOf("김정우", "이정우", "정우"), selected = null))
        val send = find(panel, context.getString(R.string.send_confirm_send))
        assertNotNull(send)
        assertFalse("고르기 전에는 보내기가 꺼져 있어야 한다", send!!.isEnabled)
    }

    @Test fun `고른 칩이 생기면 보내기가 켜진다`() {
        val panel = ExpandedPanelView(context, onStop = {}, onContinue = {}, onCollapse = {})
        panel.show(confirmState(listOf("김정우", "이정우", "정우"), selected = "이정우"))
        val send = find(panel, context.getString(R.string.send_confirm_send))
        assertNotNull(send)
        assertTrue("고른 뒤에는 보내기가 켜져야 한다", send!!.isEnabled)
    }

    @Test fun `후보가 하나면 미리 고른 상태로 보내기가 켜져 있다`() {
        val panel = ExpandedPanelView(context, onStop = {}, onContinue = {}, onCollapse = {})
        panel.show(confirmState(listOf("김정우", "정우"), selected = "김정우"))
        val send = find(panel, context.getString(R.string.send_confirm_send))
        assertTrue(send!!.isEnabled)
    }

    @Test fun `고르면 같은 칩 뷰가 남아서 탭이 도중에 사라지지 않는다`() {
        val panel = ExpandedPanelView(context, onStop = {}, onContinue = {}, onCollapse = {})
        panel.show(confirmState(listOf("김정우", "이정우", "정우"), selected = null))
        val before = find(panel, "이정우")
        panel.show(confirmState(listOf("김정우", "이정우", "정우"), selected = "이정우"))
        val after = find(panel, "이정우")
        assertTrue("고른 뒤에도 같은 칩 뷰여야 한다", before === after)
    }

    @Test fun `스토어 상태 흐름으로 칩을 고르면 보내기가 켜진다`() {
        val store = AssistantStore(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        store.post(com.sihoo.aemx.pipeline.PipelineEvent.RunStarted("r1", 1, "명령"))
        store.post(
            com.sihoo.aemx.pipeline.PipelineEvent.ConfirmSend(
                "r1", 2, "p1-s0", "", "테스트", listOf("김정우", "이정우", "정우"),
            ),
        )
        Thread.sleep(50)
        var chosen: String? = null
        val panel = ExpandedPanelView(
            context,
            onStop = {},
            onContinue = {},
            onCollapse = {},
            onChoose = { store.selectRecipient(it); chosen = it },
        )
        panel.show(store.state.value)
        assertFalse("고르기 전에는 보내기가 꺼져 있다", find(panel, context.getString(R.string.send_confirm_send))!!.isEnabled)

        find(panel, "이정우")!!.performClick()
        Thread.sleep(50)
        assertEquals("이정우", chosen)
        assertEquals("이정우", store.state.value.confirmSelected)

        panel.show(store.state.value)
        assertTrue("고른 뒤에는 보내기가 켜진다", find(panel, context.getString(R.string.send_confirm_send))!!.isEnabled)
    }

    @Test fun `후보가 없으면 칩 줄이 보이지 않고 보내기는 켜져 있다`() {
        val panel = ExpandedPanelView(context, onStop = {}, onContinue = {}, onCollapse = {})
        panel.show(confirmState(emptyList(), selected = "배재준"))
        val send = find(panel, context.getString(R.string.send_confirm_send))
        assertTrue(send!!.isEnabled)
        assertNull(find(panel, "정우"))
    }
}
