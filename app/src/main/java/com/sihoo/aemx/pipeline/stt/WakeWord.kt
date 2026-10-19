package com.sihoo.aemx.pipeline.stt

import com.sihoo.aemx.pipeline.port.SpeechResult
import com.sihoo.aemx.pipeline.port.SpeechSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log

private const val TAG = "WakeWord"

/**
 * 인식된 글에서 호출어("하이 에이맥스")를 찾는다.
 *
 * 한국어 키워드 검출 모델이 없어서 STT 결과를 글자로 맞춰 본다. 실기기에서 같은 말을
 * 여러 번 시켜 보니 STT 는 이렇게 적었다.
 *
 *     하이 ax? / 아 예이맥스. / 아 이 a맥스? / 하이 a믹스? / 아 에이맥스? / 하이 에이맥스
 *
 * 그래서 두 가지로 받는다.
 * - "하이" 는 자주 "아" 로 들리거나 사라지므로, 이름 앞의 짧은 글자는 무엇이든 건너뛴다.
 * - "에이맥스" 는 영문(ax, a)이나 비슷한 발음(예이, 믹스)으로 갈리므로, 영문은 한글 발음으로
 *   바꾸고 흔한 변형은 하나로 합친 뒤 비교한다.
 *
 * 이름이 문장 앞쪽이 아니면 호출로 보지 않는다. 대화 속에 이름이 나온 것까지 받으면
 * 아무 때나 깨기 때문이다.
 */
object WakeWordMatcher {

    /** 호출어를 뗀 나머지. 호출어만 말했으면 빈 문자열이다. */
    fun match(text: String): String? {
        val units = toUnits(text)
        for (skip in 0..MAX_GREETING_UNITS) {
            if (skip >= units.size) break
            for (name in NAMES) {
                if (startsWith(units, skip, name)) {
                    val end = units[skip + name.length - 1].end
                    return text.substring(end).trim { it.isWhitespace() || it in SEPARATORS }
                }
            }
        }
        return null
    }

    /** 비교용 글자 하나. [end] 는 원문에서 이 글자가 끝나는 위치다. */
    private class Unit(val char: Char, val end: Int)

    private fun toUnits(text: String): List<Unit> {
        val units = ArrayList<Unit>()
        for ((index, raw) in text.withIndex()) {
            if (raw.isWhitespace() || raw in SEPARATORS) continue
            val spoken = LATIN_SOUNDS[raw.lowercaseChar()] ?: raw.toString()
            for (char in spoken) units += Unit(SIMILAR[char] ?: char, index + 1)
        }
        return units
    }

    private fun startsWith(units: List<Unit>, from: Int, name: String): Boolean {
        if (from + name.length > units.size) return false
        return name.indices.all { units[from + it].char == name[it] }
    }

    private const val SEPARATORS = ",.!?~·…"

    /** 이름 앞에서 건너뛸 수 있는 글자 수. "하이야" 까지 들어간다. */
    private const val MAX_GREETING_UNITS = 3

    /** 영문으로 적힌 것을 한글 발음으로 바꾼다. */
    private val LATIN_SOUNDS = mapOf('a' to "에이", 'x' to "엑스", 'm' to "엠")

    /** 발음이 비슷해 STT 가 오가는 글자를 하나로 합친다. */
    private val SIMILAR = mapOf(
        '예' to '에', '애' to '에', '얘' to '에', '헤' to '에', '해' to '에',
        '믹' to '맥', '멕' to '맥', '막' to '맥', '먹' to '맥',
        '쓰' to '스', '수' to '스', '슨' to '스',
    )

    /** 합친 뒤의 모양. 긴 것부터 맞춰야 짧은 것이 먼저 걸려 나머지가 어긋나지 않는다. */
    private val NAMES = listOf("에이엠엑스", "에이엠맥스", "에이맥스", "에이엑스", "이맥스")
}

/**
 * 말소리가 있는 구간만 골라내는 단순한 소리 크기 판정.
 *
 * 음성 모델을 올리지 않으므로 CPU 부담이 거의 없다. 조용한 곳을 가정한 값이라
 * 시끄러운 곳에서는 [threshold] 를 올려야 한다.
 */
class EnergyVad(
    private val threshold: Double = 0.015,
    /**
     * 소리가 멎은 뒤에도 이만큼은 열어 둔다. 프레임이 100ms 라 기본 2.5초다.
     * STT 의 문장 끝 판정이 2초 무음을 요구하므로 그보다 길어야 한다. 짧으면 STT 가
     * 무음을 못 받아 문장이 끝나지 않는다.
     */
    private val hangoverFrames: Int = 25,
) {
    private var remaining = 0

    /** 마지막으로 잰 소리 크기. 문턱값을 정할 때 로그로 확인한다. */
    var lastRms: Double = 0.0
        private set

    /** 한 프레임(16비트 PCM)을 넣고, 지금 말하는 중이면 true. */
    fun accept(frame: ShortArray, length: Int): Boolean {
        if (length <= 0) return remaining > 0
        var sum = 0.0
        for (i in 0 until length) {
            val sample = frame[i] / 32768.0
            sum += sample * sample
        }
        val rms = Math.sqrt(sum / length)
        lastRms = rms
        if (rms >= threshold) remaining = hangoverFrames else if (remaining > 0) remaining--
        return remaining > 0
    }

    fun reset() {
        remaining = 0
    }

    companion object {
        /** 한 프레임의 소리 크기(RMS). */
        fun rms(frame: ShortArray, length: Int): Double {
            if (length <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until length) {
                val sample = frame[i] / 32768.0
                sum += sample * sample
            }
            return Math.sqrt(sum / length)
        }
    }
}

/**
 * [SpeechSource] 앞에 호출어 문을 단다.
 *
 * - 호출어와 명령을 한 번에 말하면("하이 에이맥스, 유튜브 틀어줘") 명령만 내보낸다.
 * - 호출어만 말하면 [awake] 가 켜지고, [awakeWindowMs] 안에 말한 다음 한 문장이 명령이 된다.
 * - 호출어가 없는 말은 버린다. 옆에서 나는 대화가 명령으로 들어가지 않게 하려는 것이다.
 */
class WakeWordSpeechSource(
    private val delegate: SpeechSource,
    scope: CoroutineScope,
    private val awakeWindowMs: Long = 8_000,
    private val now: () -> Long = System::currentTimeMillis,
) : SpeechSource by delegate {

    private val _results = MutableSharedFlow<SpeechResult>(extraBufferCapacity = 32)
    override val results: Flow<SpeechResult> = _results.asSharedFlow()

    private val _awake = MutableStateFlow(false)
    val awake: StateFlow<Boolean> = _awake.asStateFlow()

    /** 끄면 호출어 없이 모든 말을 그대로 통과시킨다. 개발 화면에서 쓴다. */
    @Volatile
    var required: Boolean = true

    /** 호출어가 없어 버린 말. 화면에서 "들리긴 했는데 버려졌다"를 구분하려는 것이다. */
    private val _ignored = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val ignored: Flow<String> = _ignored.asSharedFlow()

    private var awakeUntil = 0L

    init {
        scope.launch { delegate.results.collect(::onResult) }
    }

    private suspend fun onResult(result: SpeechResult) {
        if (!required) {
            _results.emit(result)
            return
        }
        when (result) {
            is SpeechResult.Error -> _results.emit(result)
            is SpeechResult.Partial -> onPartial(result)
            is SpeechResult.Final -> onFinal(result.text)
        }
    }

    /**
     * 말하는 도중에 호출어가 들리면 바로 깬다.
     *
     * 문장 끝 판정(무음 2초)을 기다리면 호출어만 말했을 때 응답이 2초 넘게 늦는다.
     * 중간 결과는 뒤에서 바뀔 수 있지만, 호출어로 시작했다는 판단은 거의 뒤집히지 않는다.
     */
    private suspend fun onPartial(result: SpeechResult.Partial) {
        if (!isAwake()) {
            if (WakeWordMatcher.match(result.text) == null) return
            awakeUntil = now() + awakeWindowMs
            _awake.value = true
            Log.d(TAG, "중간 결과에서 호출어 감지: ${result.text}")
        }
        // 호출어 부분은 빼고 명령만 보여 준다.
        val rest = WakeWordMatcher.match(result.text) ?: result.text
        if (rest.isNotBlank()) _results.emit(SpeechResult.Partial(rest))
    }

    private suspend fun onFinal(text: String) {
        Log.d(TAG, "최종 결과: $text")
        val command = WakeWordMatcher.match(text)
        when {
            // 호출어와 명령을 한 번에 말했다.
            command != null && command.isNotBlank() -> {
                sleep()
                _results.emit(SpeechResult.Final(command))
            }
            // 호출어만 말했다. 다음 한 문장을 기다린다.
            command != null -> {
                awakeUntil = now() + awakeWindowMs
                _awake.value = true
            }
            // 호출어 뒤에 이어서 말한 명령.
            isAwake() -> {
                sleep()
                _results.emit(SpeechResult.Final(text))
            }
            // 호출어가 없는 말은 버린다.
            else -> _ignored.emit(text)
        }
    }

    private fun isAwake(): Boolean {
        if (_awake.value && now() > awakeUntil) _awake.value = false
        return _awake.value
    }

    private fun sleep() {
        awakeUntil = 0L
        _awake.value = false
    }
}
