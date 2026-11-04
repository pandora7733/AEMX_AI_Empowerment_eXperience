package com.sihoo.aemx.assistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sihoo.aemx.R

/**
 * 캡슐 하나. 왼쪽 로고, 가운데 상태 문장과 보조 줄(또는 듣는 중에는 파형), 오른쪽 중단 버튼.
 *
 * 듣는 중([VoiceState])에는 캡슐이 높이 88 이 되고 테두리가 파랑→보라→청록 그라데이션이 되며, 캡슐 뒤에
 * 바깥 광원([CapsuleGlowView])이 깔린다. 광원이 그려질 여백은 위아래로만 준다(창 폭은 컨트롤러가 정하므로
 * 좌우 광원은 잘린다). 색과 크기는 전부 res 의 aemx_* 에서 가져오므로 다크 테마는 values-night 가 바꾼다.
 * 글자를 고정 높이로 자르지 않는다(글자가 길면 두 줄까지 늘어난다).
 */
internal class CapsuleView(
    context: Context,
    private val onStop: () -> Unit,
    private val onDismiss: () -> Unit,
    /** 끝나기 전에 캡슐을 누르면 펼친 패널을 여닫는다. */
    private val onToggle: () -> Unit,
) : FrameLayout(context) {

    private val glow = CapsuleGlowView(context)
    private val body = CapsuleBody(context)
    private val title: TextView
    private val subtitle: TextView
    private val wave: VoiceWaveView
    private val stop: TextView
    private val logo: AemxLogoView
    private var dismissible = false
    private var voice = VoiceState.None
    private var titleTarget: String? = null
    private var pulsing = false

    init {
        addView(glow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        body.orientation = LinearLayout.HORIZONTAL
        body.gravity = Gravity.CENTER_VERTICAL
        val space = px(R.dimen.aemx_space_12)
        body.setPadding(space, px(R.dimen.aemx_space_8), px(R.dimen.aemx_space_8), px(R.dimen.aemx_space_8))
        body.elevation = px(R.dimen.aemx_space_8).toFloat()
        body.minimumHeight = px(R.dimen.aemx_capsule_min_height)
        applyFrame(listening = false)
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 로고는 장식이라 TalkBack 이 읽지 않는다(AemxLogoView 가 스스로 제외한다).
        logo = AemxLogoView(context)
        val logoSize = px(R.dimen.aemx_logo_size)
        body.addView(logo, LinearLayout.LayoutParams(logoSize, logoSize))

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
        wave = VoiceWaveView(context).apply { visibility = GONE }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(wave, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(R.dimen.aemx_space_4) })
        }
        body.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = space })

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
            setOnClickListener {
                // 중단을 누르면 펄스와 파형을 0ms 로 바로 멈춘다(글자가 바뀌기를 기다리지 않는다).
                OverlayMotion.stop(title)
                OverlayMotion.stop(logo)
                wave.setListening(false)
                onStop()
            }
        }
        body.addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, touch).apply { marginStart = space })

        setOnClickListener { if (dismissible) onDismiss() else onToggle() }
        isClickable = true
    }

    /**
     * 문구와 듣는 중 모양을 채운다.
     * @param dismissible 끝난 상태라 캡슐을 눌러 닫을 수 있는지. 끝나기 전에는 눌러도 아무 일이 없다.
     */
    fun bind(text: CapsuleText, dismissible: Boolean) {
        this.dismissible = dismissible
        isClickable = dismissible
        if (text.voice != voice) applyVoice(text.voice)
        // 같은 글자로 다시 그릴 때 인식 문장을 처음부터 다시 나타내지 않도록, 목표 글자와 비교한다.
        if (titleTarget != text.title) {
            titleTarget = text.title
            if (text.voice != VoiceState.None) {
                // 듣는 중 글자는 깜빡이지 않게 바로 바꾼다(흐려졌다 선명해지는 효과를 쓰지 않는다).
                OverlayMotion.finishTypewrite(title, text.title)
            } else {
                OverlayMotion.finishTypewrite(title, text.title)
                OverlayMotion.swap(title)
            }
        }
        if (text.pulse != pulsing) {
            pulsing = text.pulse
            if (text.pulse) {
                OverlayMotion.pulse(title, CapsuleMotion.UNDERSTAND_PULSE_MS)
                OverlayMotion.pulse(logo, CapsuleMotion.UNDERSTAND_PULSE_MS)
            } else {
                OverlayMotion.stop(title)
                OverlayMotion.stop(logo)
            }
        }
        subtitle.text = text.subtitle
        subtitle.visibility = if (text.subtitle.isBlank() || voice != VoiceState.None) View.GONE else View.VISIBLE
        stop.visibility = if (text.showStop) View.VISIBLE else View.GONE
        stop.isEnabled = text.stopEnabled
        stop.alpha = if (text.stopEnabled) 1f else 0.5f
    }

    /** 캡슐이 새로 나타난다(창을 붙인 뒤). */
    fun animateIn() = OverlayMotion.enter(body, px(R.dimen.aemx_space_8).toFloat())

    /** 캡슐이 사라진다. [end] 는 끝난 뒤 부른다(그 뒤에 창을 뗀다). */
    fun animateOut(end: () -> Unit) = OverlayMotion.exit(body) { end() }

    /** 사라지는 중에 다시 나타나면 되돌린다. */
    fun cancelExit() = OverlayMotion.cancelExit(body)

    /** 성공했다(체크가 나타나며 캡슐이 0.96 에서 1 로 커진다). */
    fun popSuccess() = OverlayMotion.successPop(body)

    private fun applyVoice(next: VoiceState) {
        voice = next
        val listening = next != VoiceState.None
        applyFrame(listening)
        // 듣는 중 글자는 한 줄로 고정하고, 바뀔 때마다 읽어 주지 않는다(중간 결과가 자주 바뀐다).
        title.maxLines = if (listening) 1 else 2
        title.accessibilityLiveRegion = if (listening) View.ACCESSIBILITY_LIVE_REGION_NONE else View.ACCESSIBILITY_LIVE_REGION_POLITE
        val pad = if (listening) px(R.dimen.aemx_glow_pad) else 0
        (body.layoutParams as LayoutParams).setMargins(0, pad, 0, pad)
        body.minimumHeight = px(if (listening) R.dimen.aemx_listening_min_height else R.dimen.aemx_capsule_min_height)
        glow.visibility = if (listening) View.VISIBLE else View.GONE
        glow.setGlow(on = listening, dimmed = next == VoiceState.Recognizing)
        wave.visibility = if (listening) View.VISIBLE else View.GONE
        wave.setListening(listening)
        wave.setResting(next == VoiceState.Recognizing)
        body.setRim(listening)
        body.requestLayout()
    }

    /** 평소 테두리는 얇은 회색 선, 듣는 중에는 그라데이션 테두리([CapsuleBody] 가 그린다). */
    private fun applyFrame(listening: Boolean) {
        body.background = GradientDrawable().apply {
            setColor(context.getColor(R.color.aemx_surface))
            cornerRadius = px(R.dimen.aemx_capsule_radius).toFloat()
            if (!listening) setStroke(px(R.dimen.aemx_stroke_width), context.getColor(R.color.aemx_stroke))
        }
    }

    /**
     * 크기를 직접 잰다. 광원은 본체와 같은 크기여야 한다(화면 전체로 커지지 않게). 본체 위아래 여백(광원 여백)만큼
     * 키를 늘리고, 광원 뷰는 이 전체 크기로 맞춘다.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val lp = body.layoutParams as MarginLayoutParams
        body.measure(
            MeasureSpec.makeMeasureSpec(width - lp.leftMargin - lp.rightMargin, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val height = body.measuredHeight + lp.topMargin + lp.bottomMargin
        glow.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val lp = body.layoutParams as MarginLayoutParams
        glow.layout(0, 0, glow.measuredWidth, glow.measuredHeight)
        body.layout(
            lp.leftMargin,
            lp.topMargin,
            lp.leftMargin + body.measuredWidth,
            lp.topMargin + body.measuredHeight,
        )
        updateStopTouchArea()
    }

    /**
     * 중단 버튼의 터치 영역만 넓힌다. 버튼은 글자 폭(약 56dp)만큼이라 손가락이 빗나가기 쉽다. 보이는 모양은 그대로 두고,
     * 본체(몸통)에 [TouchDelegate] 를 달아 가로 [STOP_TOUCH_MIN_DP] 까지 왼쪽으로 늘린다(본체 안에서만 늘어난다).
     */
    private fun updateStopTouchArea() {
        val hit = Rect()
        // 버튼의 위치는 부모(본체) 기준이다. 본체가 받은 터치를 TouchDelegate 가 버튼에 넘긴다.
        stop.getHitRect(hit)
        val want = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, STOP_TOUCH_MIN_DP, resources.displayMetrics
        ).toInt()
        if (hit.width() < want) hit.left = hit.right - want
        body.touchDelegate = TouchDelegate(hit, stop)
    }

    private fun px(dimen: Int) = resources.getDimensionPixelSize(dimen)

    private companion object {
        /** 중단 버튼이 받는 최소 가로 터치 폭(dp). 보이는 크기와는 따로 잡는다. */
        const val STOP_TOUCH_MIN_DP = 72f
    }
}

/**
 * 캡슐 본체. 듣는 중에는 테두리를 파랑→보라→청록 그라데이션 선(2.5dp)으로 그린다.
 * 그라데이션 선은 배경 위, 자식 글자 아래에서 그린다.
 */
internal class CapsuleBody(context: Context) : LinearLayout(context) {

    private var rimOn = false
    private val rectF = RectF()
    private val rimWidth = resources.getDimension(R.dimen.aemx_rim_width)
    private val radius = resources.getDimension(R.dimen.aemx_capsule_radius)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rimWidth
    }

    fun setRim(on: Boolean) {
        if (rimOn == on) return
        rimOn = on
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rimPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                context.getColor(R.color.aemx_logo),
                context.getColor(R.color.aemx_logo_end),
                context.getColor(R.color.aemx_brand_cyan)
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!rimOn) return
        // 선 가운데가 가장자리에 오도록 반 굵기만큼 안쪽으로 그린다.
        val half = rimWidth / 2f
        rectF.set(half, half, width - half, height - half)
        canvas.drawRoundRect(rectF, radius - half, radius - half, rimPaint)
    }
}
