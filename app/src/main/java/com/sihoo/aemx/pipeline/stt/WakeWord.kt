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

/** 호출어 판정의 진단 로그 태그. 인식된 글자는 짧은 앞부분만 남긴다. */
private const val WAKE_TAG = "AemxWake"

/**
 * 인식된 글에서 호출어("하이 에이맥스")를 찾는다.
 *
 * 한국어 키워드 검출 모델이 없어서 STT 결과를 글자로 맞춰 본다. 실기기에서 같은 말을
 * 여러 번 시켜 보니 STT 는 이렇게 적었다.
 *
 *     하이 ax? / 아 예이맥스. / 아 이 a맥스? / 하이 a믹스? / 아 에이맥스? / 하이 에이맥스
 *
 * 그래서 두 가지로 받는다.
 * - "하이" 는 자주 "아" 로 들리거나 사라지므로, 이름 앞에는 인사말 목록에 있는 짧은 글자만 건너뛴다.
 *   아무 글자나 건너뛰면 "어제하이넥스트" 처럼 앞말이 잘린 대화가 호출로 잡힌다.
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
            // 이름 앞은 인사말 목록에 있을 때만 본다. "어제 하이 …" 처럼 앞말이 붙은 글은 호출이 아니다.
            val greeting = skippedText(units, skip)
            if (greeting !in GREETINGS) continue
            for (name in NAMES) {
                if (startsWith(units, skip, name)) {
                    val end = units[skip + name.length - 1].end
                    return text.substring(end).trim { it.isWhitespace() || it in SEPARATORS }
                }
            }
            // 정확히 맞지 않으면 글자 하나 차이까지 받는다. 긴 이름만, 그리고 앞이 비었거나 "하이"/"아"일 때만 한다.
            if (greeting !in FUZZY_PREFIXES) continue
            for (name in NAMES) {
                val end = fuzzyEnd(units, skip, name) ?: continue
                return text.substring(units[end - 1].end).trim { it.isWhitespace() || it in SEPARATORS }
            }
        }
        return null
    }

    /**
     * 호출어로 판정하지 못한 글의 앞부분. 로그에 남기는 용도다. 개인정보라 호출어 후보 길이(최대 6글자)만 보인다.
     */
    fun debugHead(text: String): String =
        toUnits(text).take(DEBUG_HEAD_UNITS).joinToString("") { it.char.toString() }

    /**
     * [units] 의 [from] 에서 시작하는 글을 [name] 과 비교한다. 길이가 [name] 과 한 글자 차이까지 맞으면
     * 그 끝 위치(배타)를 돌려준다. [FUZZY_MIN_LENGTH] 보다 짧은 이름에는 쓰지 않는다.
     */
    private fun fuzzyEnd(units: List<Unit>, from: Int, name: String): Int? {
        if (name.length < FUZZY_MIN_LENGTH) return null
        for (length in name.length - 1..name.length + 1) {
            if (length <= 0 || from + length > units.size) continue
            val window = String(CharArray(length) { units[from + it].char })
            if (editDistance(window, name) <= 1) return from + length
        }
        return null
    }

    /** 글자 단위 편집 거리(삽입, 삭제, 바꿈). 이름과 창이 짧아서 단순한 표로 충분하다. */
    internal fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun skippedText(units: List<Unit>, skip: Int): String =
        String(CharArray(skip) { units[it].char })

    /** 한 글자 차이를 받아 주는 앞말. 빈 것, 인사 "하이", 짧은 "아" 뿐이다. */
    private val FUZZY_PREFIXES = setOf("", "하이", "아")

    private const val FUZZY_MIN_LENGTH = 4
    private const val DEBUG_HEAD_UNITS = 6

    /** 비교용 글자 하나. [end] 는 원문에서 이 글자가 끝나는 위치다. */
    private class Unit(val char: Char, val end: Int)

    private fun toUnits(text: String): List<Unit> {
        val units = ArrayList<Unit>()
        var index = 0
        while (index < text.length) {
            val raw = text[index]
            if (raw.isWhitespace() || raw in SEPARATORS) {
                index++
                continue
            }
            if (raw in 'a'..'z' || raw in 'A'..'Z') {
                // 영문은 단어째로 먼저 본다. "max" 를 m·a·x 로 쪼개면 "엠에이엑스" 가 되어 놓친다.
                var wordEnd = index
                while (wordEnd < text.length && (text[wordEnd] in 'a'..'z' || text[wordEnd] in 'A'..'Z')) wordEnd++
                val whole = WORD_SOUNDS[text.substring(index, wordEnd).lowercase()]
                for (i in index until wordEnd) {
                    // 단어째로 바꿨으면 글자마다 끝 위치를 단어 끝에 둔다. 명령이 중간에서 잘리지 않게 하려는 것이다.
                    val spoken = whole ?: LATIN_SOUNDS[text[i].lowercaseChar()] ?: text[i].toString()
                    if (whole != null && i > index) continue
                    for (char in spoken) units += Unit(SIMILAR[char] ?: char, if (whole != null) wordEnd else i + 1)
                }
                index = wordEnd
                continue
            }
            units += Unit(SIMILAR[raw] ?: raw, index + 1)
            index++
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

    /**
     * 이름 앞에 올 수 있는 인사말 글자. 빈 것은 이름으로 바로 시작하는 경우다.
     * 비교는 [SIMILAR] 로 바꾼 뒤라서 "헤이" 는 "에이" 로 들어온다.
     * STT 가 "하이" 를 "할", "하", "아이", "하긴" 으로 적는 것까지 넣었다("하긴 A맥스" 가 실기기에서 나왔다).
     */
    private val GREETINGS = setOf("", "하이", "하", "아", "아이", "할", "하긴", "에이", "야", "하이야", "아이야")

    /** STT 가 "맥스" 를 영단어로 적은 것. 단어째로 바꾸고, 여기 없는 영문만 글자별로 바꾼다. */
    private val WORD_SOUNDS = mapOf(
        "max" to "맥스", "macs" to "맥스", "mex" to "맥스", "maks" to "맥스", "amax" to "에이맥스", "imax" to "아이맥스",
        "aimax" to "에이맥스", "eimax" to "에이맥스", "ai" to "에이", "i" to "아이",
    )

    /** 영문으로 적힌 것을 한글 발음으로 바꾼다. */
    private val LATIN_SOUNDS = mapOf('a' to "에이", 'x' to "엑스", 'm' to "엠")

    /** 발음이 비슷해 STT 가 오가는 글자를 하나로 합친다. */
    private val SIMILAR = mapOf(
        '예' to '에', '애' to '에', '얘' to '에', '헤' to '에', '해' to '에',
        '믹' to '맥', '멕' to '맥', '막' to '맥', '먹' to '맥',
        '쓰' to '스', '수' to '스', '슨' to '스',
    )

    /** 합친 뒤의 모양. 긴 것부터 맞춰야 짧은 것이 먼저 걸려 나머지가 어긋나지 않는다. */
    private val NAMES = listOf(
        "에이엠엑스", "에이엠맥스", "에이맥스", "에이엑스", "이맥스", "에이맥", "이맥",
        // 실기기 로그에서 본 변형. 발음 규칙으로는 못 잡는 글자라 그대로 넣는다. 세 글자짜리는 흐림 비교를 하지 않는다.
        "엠엑스", "엠맥스", "이넥스", "이미트", "컴스트", "맥을라",
    )
}

/**
 * 말소리가 있는 구간만 골라내는 단순한 소리 크기 판정.
 *
 * 음성 모델을 올리지 않으므로 CPU 부담이 거의 없다. 조용한 곳을 가정한 값이라
 * 시끄러운 곳에서는 [threshold] 를 올려야 한다.
 *
 * [noiseMultiplier] 가 0 보다 크면 문턱을 주변 소음에 맞춘다. 최근 [NOISE_WINDOW_FRAMES] 프레임
 * 소리 크기의 하위 [NOISE_PERCENTILE]% 지점(바닥 소음)에 배수를 곱해 [threshold]..[maxThreshold]
 * 사이로 자른다. [threshold] 가 최소값이고, [maxThreshold] 는 조용히 말하는 사람이 안 잡히지 않게 하는 상한이다.
 * 바닥은 문이 열려 있을 때도 갱신한다. 닫힌 때만 갱신하면 소음이 갑자기 커졌을 때 문이 영영 안 닫힌다.
 * 대신 말이 5초 넘게 쉼 없이 이어지면 문턱이 상한까지 오를 수 있다.
 */
class EnergyVad(
    private val threshold: Double = 0.015,
    /**
     * 소리가 멎은 뒤에도 이만큼은 열어 둔다. 프레임이 100ms 라 기본 2.5초다.
     * STT 의 문장 끝 판정이 2초 무음을 요구하므로 그보다 길어야 한다. 짧으면 STT 가
     * 무음을 못 받아 문장이 끝나지 않는다.
     */
    private val hangoverFrames: Int = 25,
    private val noiseMultiplier: Double = 0.0,
    private val maxThreshold: Double = threshold,
) {
    private var remaining = 0
    private val recent = ArrayDeque<Double>()

    /** 지금 쓰는 문턱. 적응형이 아니면 [threshold] 와 같다. */
    val currentThreshold: Double
        get() {
            if (noiseMultiplier <= 0.0 || recent.size < NOISE_MIN_FRAMES) return threshold
            return (noiseFloor() * noiseMultiplier).coerceIn(threshold, maxOf(threshold, maxThreshold))
        }

    private fun noiseFloor(): Double {
        val sorted = recent.sorted()
        return sorted[(sorted.size * NOISE_PERCENTILE / 100).coerceAtMost(sorted.size - 1)]
    }

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
        if (rms >= currentThreshold) remaining = hangoverFrames else if (remaining > 0) remaining--
        if (noiseMultiplier > 0.0) {
            recent.addLast(rms)
            if (recent.size > NOISE_WINDOW_FRAMES) recent.removeFirst()
        }
        return remaining > 0
    }

    fun reset() {
        remaining = 0
    }

    companion object {
        /** 바닥 소음을 재는 최근 프레임 수(100ms 단위, 5초). */
        const val NOISE_WINDOW_FRAMES = 50
        const val NOISE_MIN_FRAMES = 10
        const val NOISE_PERCENTILE = 10

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
            // 호출어가 없는 말은 버린다. 다음에 무엇이 들렸는지 보려고 앞부분 몇 글자만 로그에 남긴다(개인정보라 짧게).
            else -> {
                Log.i(WAKE_TAG, "호출어 아님: 앞 글자 [${WakeWordMatcher.debugHead(text)}] 전체 ${text.length}글자")
                _ignored.emit(text)
            }
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

    /**
     * 호출어 창만 닫는다(듣는 중 캡슐의 중단). 마이크와 인식기는 그대로 둔다. 창이 열려 있지 않으면 아무 일도 없다.
     */
    fun cancelAwake() {
        sleep()
    }
}
