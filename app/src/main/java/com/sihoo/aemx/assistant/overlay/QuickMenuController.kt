package com.sihoo.aemx.assistant.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.sihoo.aemx.Glyph
import com.sihoo.aemx.GlyphView
import com.sihoo.aemx.R
import com.sihoo.aemx.debug.MeterView

/**
 * 화면에 떠 있는 접근성 버튼을 누르면 뜨는 빠른 메뉴. 버튼은 셋이다: 앱 열기, 마이크 켜고 끄기, 완전히 종료.
 *
 * 창은 캡슐과 같은 TYPE_ACCESSIBILITY_OVERLAY 라서 따로 허용받을 것이 없다. 화면 전체를 덮는 어두운 바탕 위에
 * 카드를 가운데 두고, 바탕을 누르면 닫는다. 떠 있는 동안 마이크 상태와 소리 크기를 계속 다시 그린다.
 * 모든 메서드는 메인 스레드에서 부른다. 개발자와 시연자가 쓰는 메뉴라 글자는 리소스로 빼지 않았다.
 *
 * @param micState 지금 마이크 상태.
 * @param micLevel 지금 소리 크기(RMS).
 * @param micNotice 마이크 칸의 안내 줄에 대신 보여 줄 문장(켜지 못하는 이유 등). 없으면 null.
 */
@SuppressLint("SetTextI18n")
internal class QuickMenuController(
    private val service: AccessibilityService,
    private val micState: () -> MicState,
    private val micLevel: () -> Double,
    private val onOpenApp: () -> Unit,
    private val micNotice: () -> String?,
    private val onToggleMic: () -> Unit,
    private val onQuit: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: FrameLayout? = null
    private var micDot: View? = null
    private var micLabel: TextView? = null
    private var micHint: TextView? = null
    private var micMeter: MeterView? = null
    private var quitLabel: TextView? = null
    private var quitHint: TextView? = null

    /** 완전히 종료를 한 번 눌러 확인을 기다리는 중인지. */
    private var quitArmed = false

    val isShowing: Boolean get() = root != null

    fun toggle() {
        if (isShowing) hide() else show()
    }

    fun show() {
        if (isShowing) return
        val content = buildContent()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { title = "AEMX 빠른 메뉴" }
        try {
            windowManager.addView(content, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "빠른 메뉴 창을 붙이지 못했다", e)
            return
        }
        root = content
        quitArmed = false
        renderMic()
        renderQuit()
        handler.postDelayed(refresh, REFRESH_MS)
        handler.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    fun hide() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(autoClose)
        handler.removeCallbacks(disarmQuit)
        val content = root ?: return
        root = null
        try {
            windowManager.removeView(content)
        } catch (e: RuntimeException) {
            Log.w(TAG, "빠른 메뉴 창을 떼지 못했다", e)
        }
    }

    /** 다크 모드나 글자 크기가 바뀌었다. 떠 있으면 새로 만든다. */
    fun onConfigurationChanged() {
        if (!isShowing) return
        hide()
        show()
    }

    private val refresh = object : Runnable {
        override fun run() {
            renderMic()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    /** 열어 둔 채 잊으면 화면 전체를 덮은 창이 남아 아래 앱을 못 누른다. 한동안 손대지 않으면 닫는다. */
    private val autoClose = Runnable { hide() }

    private val disarmQuit = Runnable {
        quitArmed = false
        renderQuit()
    }

    private fun touched() {
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    private fun renderMic() {
        val state = micState()
        val color = when (state) {
            MicState.LISTENING -> R.color.onb_green
            MicState.SILENCED -> R.color.onb_warn
            MicState.STARTING -> R.color.onb_sky
            MicState.OFF -> R.color.onb_track
        }
        micDot?.background = oval(color)
        micLabel?.text = QuickMenuRules.micLabel(state)
        micHint?.text = micNotice() ?: QuickMenuRules.micHint(state)
        val listening = state == MicState.LISTENING
        micMeter?.set(if (listening) QuickMenuRules.levelPercent(micLevel()) else 0)
    }

    private fun renderQuit() {
        quitLabel?.text = if (quitArmed) "정말 종료할까요?" else "완전히 종료"
        quitHint?.text = if (quitArmed) "한 번 더 누르면 종료합니다" else "백그라운드까지 모두 끕니다"
    }

    private fun buildContent(): FrameLayout {
        val scrim = FrameLayout(service).apply {
            setBackgroundColor(SCRIM_COLOR)
            setOnClickListener { hide() }
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(R.color.onb_bg, 24)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            // 카드 안의 빈 곳을 눌러도 닫히지 않게 누름을 먹는다.
            isClickable = true
        }

        val header = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(service).apply { setImageResource(R.drawable.ic_aemx_logo) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        header.addView(text(18f, R.color.onb_ink, bold = true).apply { text = "AEMX" },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        header.addView(text(14f, R.color.onb_muted).apply {
            text = "닫기"
            gravity = Gravity.CENTER
            minWidth = dp(56)
            minHeight = dp(48)
            setOnClickListener { hide() }
        })
        card.addView(header, matchWrap())

        val tiles = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.addView(buildOpenTile(), tileParams(first = true))
        tiles.addView(buildMicTile(), tileParams())
        tiles.addView(buildQuitTile(), tileParams())
        card.addView(tiles, matchWrap().apply { topMargin = dp(8) })

        val width = minOf(dp(CARD_WIDTH_DP), service.resources.displayMetrics.widthPixels - dp(32))
        scrim.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return scrim
    }

    private fun buildOpenTile(): LinearLayout {
        val tile = tile { onOpenApp(); hide() }
        tile.addView(iconChip(GlyphView(service, null, Glyph.PLAY, service.getColor(R.color.onb_plum))), chipParams())
        tile.addView(text(15f, R.color.onb_ink, bold = true).apply { text = "앱 열기"; gravity = Gravity.CENTER }, matchWrap().apply { topMargin = dp(8) })
        tile.addView(text(12f, R.color.onb_muted).apply { text = "AEMX 화면을 엽니다"; gravity = Gravity.CENTER }, matchWrap().apply { topMargin = dp(2) })
        return tile
    }

    private fun buildMicTile(): LinearLayout {
        val tile = tile {
            touched()
            onToggleMic()
            renderMic()
        }
        val chip = iconChip(GlyphView(service, null, Glyph.MIC, service.getColor(R.color.onb_plum)))
        // 상태 점을 아이콘 오른쪽 위에 겹쳐서 색으로도 켜짐과 꺼짐을 알 수 있게 한다.
        micDot = View(service).also { chip.addView(it, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.END)) }
        tile.addView(chip, chipParams())
        micLabel = text(15f, R.color.onb_ink, bold = true).apply { gravity = Gravity.CENTER }
        tile.addView(micLabel, matchWrap().apply { topMargin = dp(8) })
        micMeter = MeterView(service)
        tile.addView(micMeter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6) })
        micHint = text(12f, R.color.onb_muted).apply { gravity = Gravity.CENTER }
        tile.addView(micHint, matchWrap().apply { topMargin = dp(4) })
        return tile
    }

    private fun buildQuitTile(): LinearLayout {
        val tile = tile {
            touched()
            if (quitArmed) {
                hide()
                onQuit()
            } else {
                // 실수로 눌러 시연 중에 꺼지지 않게 한 번 더 누르게 한다.
                quitArmed = true
                renderQuit()
                handler.removeCallbacks(disarmQuit)
                handler.postDelayed(disarmQuit, QUIT_CONFIRM_MS)
            }
        }
        val cross = text(22f, R.color.onb_warn, bold = true).apply { text = "✕"; gravity = Gravity.CENTER }
        tile.addView(iconChip(cross), chipParams())
        quitLabel = text(15f, R.color.onb_ink, bold = true).apply { gravity = Gravity.CENTER }
        tile.addView(quitLabel, matchWrap().apply { topMargin = dp(8) })
        quitHint = text(12f, R.color.onb_muted).apply { gravity = Gravity.CENTER }
        tile.addView(quitHint, matchWrap().apply { topMargin = dp(2) })
        return tile
    }

    private fun tile(onClick: () -> Unit) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = rounded(R.color.onb_card, 16)
        setPadding(dp(10), dp(14), dp(10), dp(14))
        minimumHeight = dp(TILE_MIN_HEIGHT_DP)
        setOnClickListener { onClick() }
    }

    private fun iconChip(icon: View) = FrameLayout(service).apply {
        addView(
            FrameLayout(service).apply {
                background = oval(R.color.onb_chip)
                addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            },
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER),
        )
    }

    private fun chipParams() = LinearLayout.LayoutParams(dp(52), dp(52))

    private fun tileParams(first: Boolean = false) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (!first) marginStart = dp(8) }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(service.getColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(service.getColor(color))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(service.getColor(color))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "AemxQuickMenu"

        /** 카드 뒤 바탕. 아래 화면이 비쳐 보이는 검정 45%. */
        const val SCRIM_COLOR = 0x73000000

        const val CARD_WIDTH_DP = 460
        const val TILE_MIN_HEIGHT_DP = 150

        /** 마이크 상태와 소리 크기를 다시 그리는 간격. */
        const val REFRESH_MS = 200L

        /** 손대지 않으면 메뉴를 닫는 시간. */
        const val AUTO_CLOSE_MS = 20_000L

        /** 완전히 종료의 확인을 기다리는 시간. 지나면 처음으로 돌아간다. */
        const val QUIT_CONFIRM_MS = 4_000L
    }
}
