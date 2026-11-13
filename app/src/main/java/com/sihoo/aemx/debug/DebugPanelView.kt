package com.sihoo.aemx.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.sihoo.aemx.R
import com.sihoo.aemx.TtsStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 홈 화면 아래에 붙는 디버깅 패널. 하드웨어 사용량과 음성 인식의 지금 상태를 한 화면에 계속 보여 준다.
 *
 * 하드웨어는 1초마다 다시 재고([HardwareMonitor]), 음성은 [SpeechTrace] 가 바뀔 때마다 그린다.
 * 양으로 볼 수 있는 값(CPU, 코어별 클럭, 메모리, 소리 크기)은 막대로 그린다.
 * 화면이 보이는 동안만 돌도록 [start] 와 [stop] 을 화면의 onResume, onPause 에서 부른다.
 * 개발용 화면이라 글자는 리소스로 빼지 않았다.
 */
@SuppressLint("SetTextI18n", "ViewConstructor")
class DebugPanelView(context: Context) : LinearLayout(context) {

    private val monitor = HardwareMonitor(context)
    private var job: Job? = null

    private val cpuBar: MeterView
    private val cpuLine: TextView
    private val coreCells: List<CoreCell>
    private val appMemoryBar: MeterView
    private val appMemoryLine: TextView
    private val appMemoryDetail: TextView
    private val systemMemoryBar: MeterView
    private val systemMemoryLine: TextView
    private val batteryLine: TextView
    private val thermalLine: TextView
    private val ttsLine: TextView
    private val vadLine: TextView
    private val levelBar: MeterView
    private val sentenceLine: TextView
    private val wordsLine: TextView
    private val finalsLine: TextView

    /** 코어 한 칸: 이름, 막대, 현재 클럭. */
    private class CoreCell(val bar: MeterView, val value: TextView)

    init {
        orientation = VERTICAL
        background = rounded(R.color.onb_card, 16)
        setPadding(dp(14), dp(6), dp(14), dp(14))

        label("앱 CPU")
        cpuBar = meter()
        cpuLine = value()

        label("코어 클럭 (MHz)")
        coreCells = buildCoreGrid(monitor.cores)

        label("앱 메모리")
        appMemoryBar = meter()
        appMemoryLine = value()
        appMemoryDetail = text(11.5f, R.color.onb_hint).also { addView(it, matchWrap().apply { topMargin = dp(2) }) }

        label("기기 메모리")
        systemMemoryBar = meter()
        systemMemoryLine = value()

        label("배터리")
        batteryLine = value()
        label("발열")
        thermalLine = value()

        label("음성 안내 (TTS)")
        ttsLine = value()
        label("언어")
        value().text = "한국어 모델"
        label("VAD")
        levelBar = meter()
        vadLine = value()
        label("지금 문장")
        sentenceLine = value()
        label("단어")
        wordsLine = value()
        label("끝난 문장 (새것이 위)")
        finalsLine = value()

        renderSpeech(SpeechTrace.state.value)
    }

    /** 측정과 그리기를 시작한다. 이미 돌고 있으면 아무 일도 하지 않는다. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            launch {
                while (isActive) {
                    val sample = withContext(Dispatchers.IO) { monitor.sample() }
                    renderHardware(sample)
                    delay(SAMPLE_INTERVAL_MS)
                }
            }
            launch { SpeechTrace.state.collect { renderSpeech(it) } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun renderHardware(sample: HardwareSample) {
        cpuBar.set(HardwareStats.cpuBarPercent(sample))
        cpuLine.text = HardwareStats.cpuText(sample)

        coreCells.forEachIndexed { index, cell ->
            val now = sample.coreMhz.getOrNull(index)
            val max = sample.coreMaxMhz.getOrNull(index)
            cell.value.text = now?.toString() ?: EMPTY
            cell.bar.set(if (now != null && max != null) HardwareStats.percent(now.toLong(), max.toLong()) else 0)
        }

        val appPercent = HardwareStats.percent(HardwareStats.appMemoryMb(sample), sample.systemTotalMb)
        appMemoryBar.set(appPercent)
        appMemoryLine.text = HardwareStats.appMemoryText(sample)
        appMemoryDetail.text = HardwareStats.appMemoryDetail(sample)

        val systemPercent = HardwareStats.percent(HardwareStats.systemUsedMb(sample), sample.systemTotalMb)
        systemMemoryBar.set(systemPercent, warn = sample.lowMemory || systemPercent >= MEMORY_WARN_PERCENT)
        systemMemoryLine.text = HardwareStats.systemMemoryText(sample)

        batteryLine.text = HardwareStats.batteryText(sample)
        thermalLine.text = HardwareStats.thermalText(sample)
        // TTS 상태는 드물게 바뀌므로 하드웨어와 같이 1초마다 다시 읽는다.
        ttsLine.text = TtsStatus.label(TtsStatus.state, TtsStatus.engine)
    }

    private fun renderSpeech(state: SpeechTraceState) {
        vadLine.text = "%s · 소리 %.4f / 문턱 %.4f".format(
            Locale.US, if (state.gateOpen) "문 열림(말소리)" else "문 닫힘(조용함)", state.rms, state.threshold,
        )
        levelBar.set((state.rms / LEVEL_BAR_FULL_RMS * 100).toInt(), warn = state.gateOpen)
        sentenceLine.text = state.error ?: state.partial.ifEmpty { EMPTY }
        val words = state.words
        wordsLine.text = if (words.isEmpty()) EMPTY else "${words.size}개: " + words.joinToString(" | ")
        finalsLine.text = if (state.finals.isEmpty()) {
            EMPTY
        } else {
            state.finals.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n")
        }
    }

    /** 코어를 한 줄에 네 개씩 칸으로 나눠 그린다. 칸마다 이름, 최대 클럭 대비 막대, 현재 클럭이 있다. */
    private fun buildCoreGrid(cores: Int): List<CoreCell> {
        val cells = ArrayList<CoreCell>()
        (0 until cores).chunked(CORES_PER_ROW).forEach { indexes ->
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            indexes.forEachIndexed { column, index ->
                val cell = LinearLayout(context).apply {
                    orientation = VERTICAL
                    background = rounded(R.color.onb_bg, 10)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                }
                cell.addView(text(11f, R.color.onb_muted).apply { text = "코어 $index" }, matchWrap())
                val bar = MeterView(context)
                cell.addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(4) })
                val value = text(14f, R.color.onb_ink).apply {
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.END
                    text = EMPTY
                }
                cell.addView(value, matchWrap().apply { topMargin = dp(2) })
                cells += CoreCell(bar, value)
                line.addView(cell, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (column > 0) marginStart = dp(6) })
            }
            // 마지막 줄이 덜 차면 빈 칸으로 채워 칸 너비를 맞춘다.
            repeat(CORES_PER_ROW - indexes.size) {
                line.addView(View(context), LayoutParams(0, 1, 1f).apply { marginStart = dp(6) })
            }
            addView(line, matchWrap().apply { topMargin = dp(4) })
        }
        return cells
    }

    private fun label(name: String) {
        addView(text(11.5f, R.color.onb_muted).apply { text = name }, matchWrap().apply { topMargin = dp(10) })
    }

    private fun value(): TextView {
        val view = text(13.5f, R.color.onb_ink).apply {
            typeface = Typeface.MONOSPACE
            text = EMPTY
        }
        addView(view, matchWrap().apply { topMargin = dp(3) })
        return view
    }

    private fun meter(): MeterView {
        val view = MeterView(context)
        addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(4) })
        return view
    }

    private fun text(size: Float, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(context.getColor(color))
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(context.getColor(color))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun matchWrap() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val EMPTY = "-"

        /** 소리 크기 막대가 꽉 차는 값. 실제 명령이 0.07~0.30 으로 재졌다(SherpaSpeechSource 의 문턱 설명). */
        const val LEVEL_BAR_FULL_RMS = 0.3

        /** 기기 메모리 막대의 색을 바꾸는 사용 비율. */
        const val MEMORY_WARN_PERCENT = 85

        const val CORES_PER_ROW = 4
    }
}

/** 0~100 을 채워 그리는 둥근 막대. 강조할 때는 주황으로 바뀐다. 빠른 메뉴의 소리 크기 막대도 이것을 쓴다. */
@SuppressLint("ViewConstructor")
internal class MeterView(context: Context) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.onb_track) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val normalColor = context.getColor(R.color.onb_green)
    private val warnColor = context.getColor(R.color.onb_warn)
    private var percent = 0
    private var warn = false

    fun set(percent: Int, warn: Boolean = false) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == this.percent && warn == this.warn) return
        this.percent = clamped
        this.warn = warn
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, track)
        if (percent == 0) return
        fill.color = if (warn) warnColor else normalColor
        // 값이 아주 작아도 둥근 끝이 찌그러지지 않게 높이만큼은 그린다.
        val right = maxOf(width * percent / 100f, height.toFloat())
        canvas.drawRoundRect(0f, 0f, right, height.toFloat(), radius, radius, fill)
    }
}
