package com.sihoo.aemx.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.sihoo.aemx.R
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
 * 화면이 보이는 동안만 돌도록 [start] 와 [stop] 을 화면의 onResume, onPause 에서 부른다.
 * 개발용 화면이라 글자는 리소스로 빼지 않았다.
 */
@SuppressLint("SetTextI18n", "ViewConstructor")
class DebugPanelView(context: Context) : LinearLayout(context) {

    private val monitor = HardwareMonitor(context)
    private var job: Job? = null

    private val hardwareValues: List<TextView>
    private val vadLine: TextView
    private val levelBar: ProgressBar
    private val sentenceLine: TextView
    private val wordsLine: TextView
    private val finalsLine: TextView

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.onb_card))
            cornerRadius = dp(16).toFloat()
        }
        setPadding(dp(14), dp(12), dp(14), dp(14))

        addView(title("하드웨어 사용량 (1초마다)"), matchWrap())
        hardwareValues = HARDWARE_LABELS.map { label -> row(label) }

        addView(title("음성 인식 (실시간)"), matchWrap().apply { topMargin = dp(14) })
        row("언어").text = "한국어 (한국어 전용 모델이라 언어 감지는 없음)"
        vadLine = row("VAD")
        levelBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = LEVEL_BAR_MAX }
        addView(levelBar, matchWrap().apply { topMargin = dp(2) })
        sentenceLine = row("지금 문장")
        wordsLine = row("단어")
        finalsLine = row("끝난 문장 (새것이 위)")

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
        HardwareStats.lines(sample).forEachIndexed { index, (_, value) -> hardwareValues[index].text = value }
    }

    private fun renderSpeech(state: SpeechTraceState) {
        vadLine.text = "%s · 소리 %.4f / 문턱 %.4f".format(
            Locale.US, if (state.gateOpen) "문 열림(말소리)" else "문 닫힘(조용함)", state.rms, state.threshold,
        )
        levelBar.progress = (state.rms / LEVEL_BAR_FULL_RMS * LEVEL_BAR_MAX).toInt().coerceIn(0, LEVEL_BAR_MAX)
        sentenceLine.text = state.error ?: state.partial.ifEmpty { EMPTY }
        val words = state.words
        wordsLine.text = if (words.isEmpty()) EMPTY else "${words.size}개: " + words.joinToString(" | ")
        finalsLine.text = if (state.finals.isEmpty()) {
            EMPTY
        } else {
            state.finals.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n")
        }
    }

    /** 이름 한 줄과 값 한 줄. 값을 담는 글자칸을 돌려준다. */
    private fun row(label: String): TextView {
        addView(text(11.5f, R.color.onb_muted).apply { text = label }, matchWrap().apply { topMargin = dp(8) })
        val value = text(13.5f, R.color.onb_ink).apply {
            typeface = Typeface.MONOSPACE
            text = EMPTY
        }
        addView(value, matchWrap().apply { topMargin = dp(2) })
        return value
    }

    private fun title(label: String) = text(14f, R.color.onb_plum).apply {
        setTypeface(typeface, Typeface.BOLD)
        text = label
    }

    private fun text(size: Float, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(context.getColor(color))
    }

    private fun matchWrap() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val EMPTY = "-"

        /** 소리 크기 막대가 꽉 차는 값. 실제 명령이 0.07~0.30 으로 재졌다(SherpaSpeechSource 의 문턱 설명). */
        const val LEVEL_BAR_FULL_RMS = 0.3
        const val LEVEL_BAR_MAX = 100

        /** [HardwareStats.lines] 가 돌려주는 줄과 같은 순서다. */
        val HARDWARE_LABELS = listOf("앱 CPU", "코어 클럭", "앱 메모리", "기기 메모리", "배터리", "발열")
    }
}
