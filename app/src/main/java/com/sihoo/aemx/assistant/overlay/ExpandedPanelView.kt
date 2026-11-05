package com.sihoo.aemx.assistant.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.sihoo.aemx.R
import com.sihoo.aemx.assistant.AssistantPhase
import com.sihoo.aemx.assistant.AssistantUiState

/**
 * 펼친 패널. 캡슐을 누르면 캡슐 위(아래 도킹이면)에 붙어 단계 목록, 지금 하는 일, 중단 버튼을 보여 준다.
 *
 * 직접 처리 대기(NeedsUser)일 때는 할 일 문장과 "계속", "중단" 두 버튼을 보여 준다. 재계획 중에는 테두리를
 * 파랑으로 바꿔 회복 중임을 보여 준다(오류 빨강을 쓰지 않는다). 색과 크기는 전부 res 의 aemx_* 에서 가져온다.
 * 버튼과 누르는 영역은 최소 [R.dimen.aemx_touch_min] 이다.
 */
internal class ExpandedPanelView(
    context: Context,
    private val onStop: () -> Unit,
    private val onContinue: () -> Unit,
    private val onCollapse: () -> Unit,
    /** 보내기 확인에서 "보내기"를 눌렀다. */
    private val onSend: () -> Unit = {},
    /** 보내기 확인에서 "취소"를 눌렀다. */
    private val onCancelSend: () -> Unit = {},
    /** 보내기 확인에서 받는 사람 후보 칩 하나를 눌렀다. 이름을 그대로 돌려준다. */
    private val onChoose: (String) -> Unit = {},
) : LinearLayout(context) {

    private val title: TextView
    private val counter: TextView
    private val stepList: LinearLayout
    private val prompt: TextView
    private val actions: LinearLayout
    private val stop: TextView
    private val cont: TextView
    private val confirmBox: LinearLayout
    private val confirmText: TextView
    private val chipRow: LinearLayout
    private val sendButton: TextView
    private val confirmActions: LinearLayout

    /** 지금 그린 후보 칩의 목록과 고른 이름. 같을 때는 다시 그리지 않는다. */
    private var chipKey: List<String> = emptyList()
    private var chipSelected: String? = null

    /** 지금 그린 단계 줄들. 다시 그릴 때 펄스를 먼저 멈추려고 기억한다. */
    private val rowViews = mutableListOf<TextView>()

    init {
        orientation = VERTICAL
        val space = px(R.dimen.aemx_space_12)
        setPadding(space, px(R.dimen.aemx_space_12), space, space)
        background = surface(R.color.aemx_stroke)
        elevation = px(R.dimen.aemx_space_8).toFloat()

        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = label(context, R.dimen.aemx_text_title, R.color.aemx_text, bold = true)
        counter = label(context, R.dimen.aemx_text_caption, R.color.aemx_text_secondary)
        val titles = LinearLayout(context).apply { orientation = VERTICAL }
        titles.addView(title, matchWrap())
        titles.addView(counter, matchWrap())
        header.addView(titles, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val fold = button(context, "접기")
        fold.setOnClickListener { onCollapse() }
        header.addView(fold, LayoutParams(LayoutParams.WRAP_CONTENT, px(R.dimen.aemx_touch_min)))
        addView(header, matchWrap())

        stepList = LinearLayout(context).apply { orientation = VERTICAL }
        addView(stepList, matchWrap().apply { topMargin = space })

        prompt = label(context, R.dimen.aemx_text_label, R.color.aemx_text)
        addView(prompt, matchWrap().apply { topMargin = space })

        // 보내기 확인: 받는 사람과 내용을 카드에 담고, 내용을 크게 보여 준다. 말로 답해도 된다는 안내를 둔다.
        confirmBox = LinearLayout(context).apply { orientation = VERTICAL; visibility = View.GONE }
        confirmText = label(context, R.dimen.aemx_text_label, R.color.aemx_text)
        confirmText.maxLines = 6
        confirmText.ellipsize = TextUtils.TruncateAt.END
        confirmBox.addView(confirmText, matchWrap())
        // 받는 사람 후보 칩. 후보가 있을 때만 보인다(카톡 받는 사람을 고를 때).
        chipRow = LinearLayout(context).apply { orientation = HORIZONTAL; visibility = View.GONE }
        confirmBox.addView(chipRow, matchWrap().apply { topMargin = space })
        val voiceHint = label(context, R.dimen.aemx_text_caption, R.color.aemx_text_secondary)
        voiceHint.setText(R.string.send_confirm_voice_hint)
        confirmBox.addView(voiceHint, matchWrap().apply { topMargin = space })
        confirmActions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.END }
        sendButton = button(context, context.getString(R.string.send_confirm_send), primary = true)
        sendButton.setOnClickListener { onSend() }
        val cancelSend = button(context, context.getString(R.string.send_confirm_cancel))
        cancelSend.setOnClickListener { onCancelSend() }
        confirmActions.addView(sendButton, LayoutParams(LayoutParams.WRAP_CONTENT, px(R.dimen.aemx_touch_min)))
        confirmActions.addView(cancelSend, LayoutParams(LayoutParams.WRAP_CONTENT, px(R.dimen.aemx_touch_min)).apply { marginStart = space })
        confirmBox.addView(confirmActions, matchWrap().apply { topMargin = space })
        addView(confirmBox, matchWrap().apply { topMargin = space })

        actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.END }
        cont = button(context, "계속", primary = true)
        cont.setOnClickListener { onContinue() }
        stop = button(context, "중단")
        stop.setOnClickListener { onStop() }
        actions.addView(cont, LayoutParams(LayoutParams.WRAP_CONTENT, px(R.dimen.aemx_touch_min)))
        actions.addView(stop, LayoutParams(LayoutParams.WRAP_CONTENT, px(R.dimen.aemx_touch_min)).apply { marginStart = space })
        addView(actions, matchWrap().apply { topMargin = space })
    }

    /** 상태와 캡슐 문구로 패널을 채운다. 단계 목록은 매번 새로 그린다(단계 수가 적다). */
    fun bind(state: AssistantUiState, text: CapsuleText) {
        if (title.text.toString() != text.title) {
            title.text = text.title
            OverlayMotion.swap(title)
        }
        counter.text = text.subtitle
        counter.visibility = if (text.subtitle.isBlank()) View.GONE else View.VISIBLE

        rowViews.forEach { OverlayMotion.stop(it) }
        rowViews.clear()
        stepList.removeAllViews()
        PanelRows.from(state).forEach { row ->
            val line = label(
                context,
                R.dimen.aemx_text_label,
                if (row.current) R.color.aemx_action else R.color.aemx_text_secondary,
                bold = row.current,
            )
            line.text = "${row.label} · ${row.statusWord}"
            line.maxLines = 2
            line.ellipsize = TextUtils.TruncateAt.END
            stepList.addView(line, matchWrap().apply { topMargin = px(R.dimen.aemx_space_8) })
            rowViews += line
            // 진행 중인 단계는 은은하게 펄스한다(중단하면 멈춘다).
            if (row.statusWord == PROGRESS_WORD) OverlayMotion.pulse(line, OverlayMotion.EXECUTING_PULSE_MS)
        }

        val needsUser = state.phase == AssistantPhase.NeedsUser
        prompt.visibility = if (needsUser) View.VISIBLE else View.GONE
        prompt.text = if (needsUser) state.userActionPrompt.orEmpty() else ""
        cont.visibility = if (needsUser) View.VISIBLE else View.GONE
        cont.isEnabled = !state.cancellationRequested
        stop.visibility = if (text.showStop) View.VISIBLE else View.GONE
        stop.isEnabled = text.stopEnabled
        stop.alpha = if (text.stopEnabled) 1f else 0.5f
        actions.visibility = if (needsUser || text.showStop) View.VISIBLE else View.GONE

        // 보내기 확인 중에는 받는 사람과 내용, 보내기·취소 버튼을 보인다. 중단 버튼도 그대로 둔다.
        val confirming = state.phase == AssistantPhase.ConfirmingAction
        confirmBox.visibility = if (confirming) View.VISIBLE else View.GONE
        if (confirming) {
            confirmText.text = "${context.getString(R.string.send_confirm_recipient)}  ${state.confirmSelected ?: state.confirmRecipient.orEmpty()}\n" +
                "${context.getString(R.string.send_confirm_content)}  ${state.confirmContent.orEmpty()}"
            bindChoices(state)
        }

        // 재계획 중에는 파랑 테두리로 회복 중임을 보여 준다. 다른 상태는 기본 테두리다.
        background = surface(if (state.phase == AssistantPhase.Replanning) R.color.aemx_action else R.color.aemx_stroke)
    }

    /**
     * 받는 사람 후보 칩을 그린다. 고른 이름은 주요 버튼 모양으로 보인다. 후보가 여럿이고 아무도 안 골랐으면 보내기를 막는다.
     * 같은 상태면 다시 그리지 않는다.
     */
    private fun bindChoices(state: AssistantUiState) {
        val choices = state.confirmChoices
        val selected = state.confirmSelected
        // 칩 목록이 바뀔 때만 뷰를 새로 만든다. 고르기는 같은 칩 뷰의 모양만 바꾼다(탭 도중에 뷰가 바뀌면 탭이 사라진다).
        if (choices != chipKey) {
            chipKey = choices
            chipRow.removeAllViews()
            choices.forEach { name ->
                val chip = button(context, name)
                chip.maxLines = 1
                chip.ellipsize = TextUtils.TruncateAt.END
                chip.setOnClickListener { onChoose(name) }
                chipRow.addView(chip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = px(R.dimen.aemx_space_8)
                })
            }
        }
        for (i in 0 until chipRow.childCount) {
            val chip = chipRow.getChildAt(i) as? TextView ?: continue
            styleButton(chip, primary = chip.text.toString() == selected)
        }
        chipSelected = selected
        chipRow.visibility = if (choices.isEmpty()) View.GONE else View.VISIBLE
        val canSend = choices.isEmpty() || selected != null
        sendButton.isEnabled = canSend
        sendButton.alpha = if (canSend) 1f else 0.5f
    }

    /** 패널을 연다. 패널이 떠오르고 단계 줄이 50ms 간격으로 차례로 나타난다. 높이는 바꾸지 않는다(창 위치 보정과 충돌). */
    fun open() {
        visibility = View.VISIBLE
        val distance = px(R.dimen.aemx_space_8).toFloat()
        OverlayMotion.panelIn(this, distance)
        rowViews.forEachIndexed { index, line -> OverlayMotion.rowIn(line, index, distance) }
    }

    /** 패널을 닫는다. 흐려진 뒤 숨기고 [end] 를 부른다. 그 사이 다시 열면 닫는 움직임을 끊는다. */
    fun close(end: () -> Unit) {
        OverlayMotion.panelOut(this, px(R.dimen.aemx_space_8).toFloat()) {
            visibility = View.GONE
            end()
        }
    }

    private fun surface(stroke: Int) = GradientDrawable().apply {
        setColor(context.getColor(R.color.aemx_surface))
        cornerRadius = px(R.dimen.aemx_capsule_radius).toFloat()
        setStroke(px(R.dimen.aemx_stroke_width), context.getColor(stroke))
    }

    private fun label(context: Context, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(size))
        setTextColor(context.getColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(context: Context, label: String, primary: Boolean = false) = TextView(context).apply {
        this.text = label
        contentDescription = label
        gravity = Gravity.CENTER
        minWidth = px(R.dimen.aemx_touch_min)
        minHeight = px(R.dimen.aemx_touch_min)
        setPadding(px(R.dimen.aemx_space_12), 0, px(R.dimen.aemx_space_12), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.aemx_text_label))
        setTypeface(typeface, Typeface.BOLD)
        styleButton(this, primary)
        isClickable = true
    }

    /** 버튼 색. 주요 버튼은 브랜드 파랑 바탕에 어두운 글자로 대비를 확보한다(디자인 원칙). 색 토큰은 그대로다. */
    private fun styleButton(view: TextView, primary: Boolean) {
        view.setTextColor(context.getColor(if (primary) R.color.aemx_surface else R.color.aemx_action))
        view.background = GradientDrawable().apply {
            setColor(context.getColor(if (primary) R.color.aemx_action else R.color.aemx_chip))
            cornerRadius = px(R.dimen.aemx_space_12).toFloat()
        }
    }

    private fun matchWrap() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    private fun px(dimen: Int) = resources.getDimensionPixelSize(dimen)

    private companion object {
        /** [PanelRows] 가 진행 중인 단계에 붙이는 말. */
        const val PROGRESS_WORD = "진행 중"
    }
}
