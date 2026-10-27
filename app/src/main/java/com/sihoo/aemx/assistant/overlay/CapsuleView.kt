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

/**
 * 접힌 캡슐 하나. 왼쪽 로고, 가운데 상태 문장과 보조 줄, 오른쪽 중단 버튼.
 *
 * 1단계는 최소 모양만 만든다. 색과 크기는 전부 res 의 aemx_* 에서 가져오므로 다크 테마는 values-night 가
 * 알아서 바꾼다. 글자를 고정 높이로 자르지 않는다(글자가 길면 두 줄까지 늘어난다).
 */
internal class CapsuleView(
    context: Context,
    private val onStop: () -> Unit,
    private val onDismiss: () -> Unit
) : LinearLayout(context) {

    private val title: TextView
    private val subtitle: TextView
    private val stop: TextView
    private var dismissible = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(R.dimen.aemx_capsule_min_height)
        val space = px(R.dimen.aemx_space_12)
        setPadding(space, px(R.dimen.aemx_space_8), px(R.dimen.aemx_space_8), px(R.dimen.aemx_space_8))
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.aemx_surface))
            cornerRadius = px(R.dimen.aemx_capsule_radius).toFloat()
            setStroke(px(R.dimen.aemx_stroke_width), context.getColor(R.color.aemx_stroke))
        }
        elevation = px(R.dimen.aemx_space_8).toFloat()

        // 로고는 장식이라 TalkBack 이 읽지 않는다(AemxLogoView 가 스스로 제외한다).
        val logo = AemxLogoView(context)
        val logoSize = px(R.dimen.aemx_logo_size)
        addView(logo, LayoutParams(logoSize, logoSize))

        title = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.aemx_text_title))
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColor(R.color.aemx_text))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            // 상태가 바뀌면 TalkBack 이 정중하게(진행 중인 읽기를 끊지 않고) 읽어 준다.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        subtitle = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.aemx_text_caption))
            setTextColor(context.getColor(R.color.aemx_text_secondary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(subtitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = space })

        val touch = px(R.dimen.aemx_touch_min)
        stop = TextView(context).apply {
            text = "중단"
            contentDescription = "중단"
            gravity = Gravity.CENTER
            minWidth = touch
            minHeight = touch
            setPadding(space, 0, space, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.aemx_text_label))
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColor(R.color.aemx_action))
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.aemx_chip))
                cornerRadius = px(R.dimen.aemx_space_12).toFloat()
            }
            isClickable = true
            setOnClickListener { onStop() }
        }
        addView(stop, LayoutParams(LayoutParams.WRAP_CONTENT, touch).apply { marginStart = space })

        setOnClickListener { if (dismissible) onDismiss() }
        isClickable = false
    }

    /**
     * 문구를 채운다.
     * @param dismissible 끝난 상태라 캡슐을 눌러 닫을 수 있는지. 끝나기 전에는 눌러도 아무 일이 없다.
     */
    fun bind(text: CapsuleText, dismissible: Boolean) {
        this.dismissible = dismissible
        isClickable = dismissible
        if (title.text.toString() != text.title) title.text = text.title
        subtitle.text = text.subtitle
        subtitle.visibility = if (text.subtitle.isBlank()) View.GONE else View.VISIBLE
        stop.visibility = if (text.showStop) View.VISIBLE else View.GONE
        stop.isEnabled = text.stopEnabled
        stop.alpha = if (text.stopEnabled) 1f else 0.5f
    }

    private fun px(dimen: Int) = resources.getDimensionPixelSize(dimen)
}
