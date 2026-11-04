package com.sihoo.aemx.pipeline.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getModelConfig
import com.sihoo.aemx.TtsSilence
import com.sihoo.aemx.pipeline.PipelineRedaction
import com.sihoo.aemx.pipeline.port.SpeechResult
import com.sihoo.aemx.pipeline.port.SpeechSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * sherpa-onnx 를 [SpeechSource] 뒤에 감춘다.
 *
 * 인식 설정값은 `STT/SherpaOnnx` 검증 앱에서 귀로 듣고 맞춘 것을 그대로 가져왔다.
 * 그쪽을 고치면 여기도 같이 고쳐야 한다. 값 하나하나의 이유는 아래에 적어 두었다.
 */
class SherpaSpeechSource(
    private val context: Context,
    private val scope: CoroutineScope,
) : SpeechSource {

    private val _results = MutableSharedFlow<SpeechResult>(extraBufferCapacity = 32)
    override val results: Flow<SpeechResult> = _results.asSharedFlow()

    /** 인식된 글과 화자 점수는 사용자 말이라 디버그 빌드에서만 로그에 남긴다(#16). */
    private val debuggable = PipelineRedaction.isDebuggable(context)

    private var recognizer: OnlineRecognizer? = null

    /** [prepare] 때 만든 핫워드 전체. 듣기를 시작할 때마다 스트림에 넘긴다. */
    @Volatile
    private var hotwords: String = HOTWORDS_FIXED.joinToString("\n")
    private var audioRecord: AudioRecord? = null
    private val audioEffects = mutableListOf<AudioEffect>()
    private var listenJob: Job? = null
    /** 마이크가 무음으로 막혔는지 보는 판정. 듣기를 시작할 때마다 다시 센다. */
    private val micHealth = MicHealth()

    private val vad = EnergyVad(
        threshold = VAD_MIN_THRESHOLD,
        hangoverFrames = VAD_HANGOVER_FRAMES,
        noiseMultiplier = VAD_NOISE_MULTIPLIER,
        maxThreshold = VAD_MAX_THRESHOLD,
    )

    /** 켜면 말소리가 있는 구간만 인식기에 넣는다. 조용할 때 CPU 를 쓰지 않는다. */
    @Volatile
    var useVad: Boolean = false

    @Volatile
    private var listening = false

    /** 등록된 목소리와 대조할 때 쓴다. null 이면 화자 확인을 하지 않는다. */
    var speakerVerifier: SpeakerVerifier? = null

    /** 켜면 등록된 목소리가 아닌 말은 결과로 내보내지 않는다. */
    @Volatile
    var requireOwnerVoice: Boolean = false

    /**
     * 설정되어 있으면 한 문장이 끝날 때마다 그 소리를 여기로 넘기고 결과는 내보내지 않는다.
     * 목소리를 등록할 때 쓴다.
     */
    @Volatile
    var enrollmentSink: ((FloatArray) -> Unit)? = null

    private val _rejections = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 다른 사람 목소리로 판단해 버린 말. 화면에서 "들리긴 했다"를 보여 주기 위한 것이다. */
    val rejections: Flow<String> = _rejections.asSharedFlow()

    override fun isReady(): Boolean = recognizer != null && hasMicPermission()

    /**
     * 모델을 올린다. 120MB 를 읽어 들이므로 처음 한 번은 몇 초가 걸린다.
     * 화면이 멈추지 않도록 부르는 쪽에서 백그라운드로 부른다.
     */
    fun prepare() {
        if (recognizer != null) return
        hotwords = buildHotwords()
        recognizer = OnlineRecognizer(assetManager = context.assets, config = buildConfig())
        Log.i(TAG, "인식기 준비 완료")
    }

    /**
     * 고정 핫워드에 설치된 앱 라벨과 연락처 이름을 더한다. 앱을 시작할 때(prepare) 한 번 만든다.
     * 연락처는 READ_CONTACTS 가 있을 때만 들어간다. 권한이 prepare 뒤에 허락되면 다음 prepare 부터 반영된다.
     */
    private fun buildHotwords(): String {
        val known = HotwordBuilder.knownChars(context, TOKENS_ASSET)
        val text = HotwordBuilder.build(
            fixed = HOTWORDS_FIXED,
            appLabels = HotwordBuilder.launcherLabels(context),
            contactNames = HotwordBuilder.contactNames(context),
            isKnownChar = known::contains,
        )
        Log.i(TAG, "핫워드 ${text.lines().size}개 (고정 ${HOTWORDS_FIXED.size})")
        return text
    }

    override fun start() {
        if (listening) return
        if (!hasMicPermission()) {
            emit(SpeechResult.Error("마이크 권한이 없습니다."))
            return
        }
        val engine = recognizer ?: run {
            emit(SpeechResult.Error("인식기가 아직 준비되지 않았습니다."))
            return
        }
        if (!openMicrophone()) {
            emit(SpeechResult.Error("마이크를 열지 못했습니다."))
            return
        }

        listening = true
        micHealth.reset()
        audioRecord?.startRecording()
        // 녹음이 실제로 시작됐는지 로그로 남긴다. 백그라운드에서 막히면 여기서 멈춰 있거나 무음이 온다.
        if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "마이크 녹음이 시작되지 않았다(상태 ${audioRecord?.recordingState})")
        }
        listenJob = scope.launch(Dispatchers.IO) { listen(engine) }
    }

    override fun stop() {
        if (!listening) return
        listening = false
        MicStatus.silenced = false
        listenJob?.cancel()
        listenJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        releaseAudioEffects()
    }

    /** 모델을 내린다. 화면이 사라질 때 부른다. */
    fun release() {
        stop()
        recognizer = null
    }

    /** 화자 확인을 건너뛴 이유 중 이미 경고로 남긴 것. 듣기 한 번에 한 번만 남기려고 둔다. */
    private val warnedSkipReasons = mutableSetOf<String>()

    private fun listen(engine: OnlineRecognizer) {
        warnedSkipReasons.clear()
        // 핫워드는 스트림을 만들 때 넘긴다. 파일이 아니라 문자열로 준다.
        val stream = engine.createStream(hotwords)
        val buffer = ShortArray((READ_INTERVAL_SECONDS * SAMPLE_RATE).toInt())
        // 말소리가 시작된 프레임은 이미 소리가 올라온 뒤라 첫 음절이 잘린다. 직전 프레임들을
        // 들고 있다가 문이 열릴 때 같이 넣는다.
        val preRoll = ArrayDeque<FloatArray>()
        var wasOpen = false
        vad.reset()
        var gateLogged: Boolean? = null
        var peakRms = 0.0
        // 지금 말하고 있는 한 문장의 소리. 끝났을 때 누구 목소리인지 보려고 모아 둔다.
        val utterance = ArrayList<FloatArray>()
        val loud = ArrayList<Boolean>()
        // 직전에 로그로 남긴 중간 결과와 마이크 차단 여부. 같은 값을 프레임마다 반복해 찍지 않으려는 것이다.
        var lastLoggedText = ""
        var wasMuted = false
        // 지금 문장의 첫 말소리 시각(측정 로그용). 0 이면 아직 말소리가 없다.
        var utteranceStartMs = 0L

        // 한 발화가 끝났다. 이 값이 파이프라인을 돌리는 방아쇠다. 인식기 endpoint 와 VAD 문이 닫힌
        // 경우 둘 다 같은 흐름으로 끝내야 해서 한 곳에 둔다.
        fun finishUtterance(text: String, viaGate: Boolean) {
            logUtteranceEnd(utteranceStartMs, viaGate, trailingQuietFrames(loud), lastLoggedText, text)
            utteranceStartMs = 0L
            engine.reset(stream)
            lastLoggedText = ""
            val voice = speechOnly(utterance, loud)
            utterance.clear()
            loud.clear()
            val sink = enrollmentSink
            if (sink != null) {
                sink(voice)
            } else if (!isDiscardableUtterance(text) && isOwner(voice, text)) {
                emit(SpeechResult.Final(text))
            }
        }

        try {
            while (listening) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue
                // 소리가 3초 넘게 정확히 0 이면 마이크가 막힌 것이다(백그라운드 제한). 막힘이 시작되고 풀릴 때 한 번씩 남긴다.
                val wasSilenced = MicStatus.silenced
                if (micHealth.onFrame(buffer, read)) {
                    Log.w(TAG, "마이크가 3초 넘게 무음이다(백그라운드 마이크 제한 의심). 앱을 열면 다시 시도한다")
                }
                MicStatus.silenced = micHealth.silenced
                if (wasSilenced && !micHealth.silenced) Log.i(TAG, "마이크 소리가 다시 들어온다")

                // 앱이 말하는 소리가 마이크로 되돌아 들어온 것이다. 인식기에 넣지 않고, 말끝이
                // 이어 붙지 않게 지금까지 쌓인 것도 비운다.
                if (TtsSilence.isMuted()) {
                    if (!wasMuted) {
                        wasMuted = true
                        Log.d(TAG, "마이크 차단으로 인식 초기화")
                    }
                    lastLoggedText = ""
                    engine.reset(stream)
                    vad.reset()
                    preRoll.clear()
                    wasOpen = false
                    utterance.clear()
                    loud.clear()
                    utteranceStartMs = 0L
                    continue
                }
                wasMuted = false

                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                if (useVad) {
                    val open = vad.accept(buffer, read)
                    peakRms = maxOf(peakRms, vad.lastRms)
                    // 문이 열리거나 닫힐 때만 그 사이 가장 컸던 소리를 남긴다. 문턱값이 맞는지 보는 용도다.
                    // 1초마다 찍으면 logcat 버퍼가 금방 넘쳐 앞부분 로그가 지워진다.
                    if (open != gateLogged) {
                        Log.d(TAG, "소리 크기 최대 %.4f (문턱 %.4f) 문=%s".format(peakRms, vad.currentThreshold, open))
                        peakRms = 0.0
                        gateLogged = open
                    }
                    if (!open) {
                        // 문이 닫히는 순간 끝나지 않은 문장이 남아 있으면 여기서 끝낸다. 문이 닫히면 인식기에
                        // 무음이 더 들어가지 않아 endpoint 의 무음 길이(1.8초)가 채워지지 않고, 문장이 다음
                        // 말소리까지 매달려 앞뒤 문장이 이어 붙는다. 문이 열려 있던 hangover(1.8초) 동안의
                        // 무음은 인식기 지연만큼 덜 세어져서 이 경우가 생긴다.
                        if (shouldFlushOnGateClose(wasOpen, lastLoggedText)) {
                            stream.acceptWaveform(FloatArray(FLUSH_SILENCE_SAMPLES), sampleRate = SAMPLE_RATE)
                            while (engine.isReady(stream)) {
                                engine.decode(stream)
                            }
                            val flushed = engine.getResult(stream).textWithSpacing()
                            if (debuggable) Log.d(TAG, "문이 닫혀 문장을 끝낸다: $flushed")
                            finishUtterance(flushed.ifBlank { lastLoggedText }, viaGate = true)
                        }
                        wasOpen = false
                        preRoll.addLast(samples)
                        if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                        continue
                    }
                    if (!wasOpen) {
                        preRoll.forEach { stream.acceptWaveform(it, sampleRate = SAMPLE_RATE) }
                        preRoll.clear()
                        wasOpen = true
                    }
                }
                stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
                utterance += samples
                val isLoud = EnergyVad.rms(buffer, read) >= vad.currentThreshold
                loud += isLoud
                if (isLoud && utteranceStartMs == 0L) utteranceStartMs = System.currentTimeMillis()
                // 끝나지 않는 소음에 메모리가 무한히 늘지 않게 한다. 30초를 넘으면 앞을 버린다.
                if (utterance.size > MAX_UTTERANCE_FRAMES) {
                    utterance.removeAt(0)
                    loud.removeAt(0)
                }
                while (engine.isReady(stream)) {
                    engine.decode(stream)
                }

                val text = engine.getResult(stream).textWithSpacing()
                if (text != lastLoggedText) {
                    lastLoggedText = text
                    if (debuggable && text.isNotBlank()) Log.d(TAG, "중간: $text")
                }
                if (engine.isEndpoint(stream)) {
                    finishUtterance(text, viaGate = false)
                } else if (text.isNotBlank()) {
                    emit(SpeechResult.Partial(text))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "인식 중 오류", e)
            emit(SpeechResult.Error(e.message ?: "인식에 실패했습니다."))
        } finally {
            stream.release()
        }
    }

    /**
     * 한 문장이 끝난 이유와 시각을 한 줄로 남긴다(측정용). 말소리가 한 번도 없던 문장은 남기지 않는다.
     * 글자는 디버그 빌드에서만 넣는다.
     */
    private fun logUtteranceEnd(startMs: Long, viaGate: Boolean, quietFrames: Int, lastPartial: String, finalText: String) {
        if (startMs == 0L) return
        val endMs = System.currentTimeMillis()
        val reason = classifyUtteranceEnd(viaGate, endMs - startMs, ENDPOINT_MAX_UTTERANCE_MS)
        Log.i(
            TAG,
            formatUtteranceEndLine(
                startMs, endMs, reason, quietFrames * FRAME_MS, lastPartial, finalText, showText = debuggable,
            ),
        )
    }

    /**
     * 모아 둔 소리에서 말소리 구간만 이어 붙인다.
     *
     * 문장이 끝났다고 판정되려면 뒤에 2초쯤 무음이 붙는데, 그것까지 넣으면 목소리 특징이
     * 흐려져 같은 사람인데도 점수가 낮게 나온다. 앞뒤의 조용한 프레임은 잘라 낸다.
     */
    private fun speechOnly(frames: List<FloatArray>, loud: List<Boolean>): FloatArray {
        val first = loud.indexOfFirst { it }
        val last = loud.indexOfLast { it }
        if (first < 0) return FloatArray(0)
        val from = maxOf(0, first - 1)
        val to = minOf(frames.size - 1, last + 1)
        val out = FloatArray((from..to).sumOf { frames[it].size })
        var offset = 0
        for (i in from..to) {
            frames[i].copyInto(out, offset)
            offset += frames[i].size
        }
        return out
    }

    /** 등록된 목소리인지. 확인이 꺼져 있거나 등록이 없으면 항상 통과시킨다. */
    private fun isOwner(voice: FloatArray, text: String): Boolean {
        val verifier = speakerVerifier
        if (!requireOwnerVoice) return true
        if (verifier == null || !verifier.isEnrolled) {
            // 켰는데도 확인이 안 되는 경우다. 말이 그대로 통과해서 겉으로는 티가 나지 않는다.
            val reason = if (verifier == null) "화자 확인기가 없다" else "등록된 목소리가 없다"
            if (warnedSkipReasons.add(reason)) {
                Log.w(TAG, "내 목소리만 듣기가 켜져 있지만 화자 확인을 건너뛴다: $reason")
            }
            return true
        }

        val score = verifier.score(voice)
        val pass = score != null && score >= verifier.threshold
        if (debuggable) {
            Log.d(TAG, "화자 점수 %s (기준 %.2f) %s: %s".format(
                score?.let { "%.3f".format(it) } ?: "측정불가", verifier.threshold,
                if (pass) "통과" else "거절", text,
            ))
        }
        if (!pass) {
            val shown = score?.let { "%.2f".format(it) } ?: "너무 짧음"
            scope.launch { _rejections.emit("$text ($shown)") }
        }
        return pass
    }

    private fun emit(result: SpeechResult) {
        scope.launch { _results.emit(result) }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openMicrophone(): Boolean {
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBytes <= 0) return false

        val record = AudioRecord(
            // VOICE_RECOGNITION 은 기기가 음성인식용 전처리를 붙여 주는 입력이다.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            // 16비트라 한 샘플이 2바이트다. 최소치의 두 배를 잡아 끊김을 줄인다.
            minBytes * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        audioRecord = record
        enableAudioEffects(record.audioSessionId)
        return true
    }

    /**
     * 기기가 제공하는 오디오 전처리를 켠다. 지원 여부가 기기마다 달라 있는 것만 켠다.
     *
     * 에코 제거가 필요한 이유는 TTS 가 말하는 소리가 마이크로 다시 들어오기 때문이다.
     * 듣기와 말하기를 같이 하는 구조라, 없으면 자기 목소리를 명령으로 인식한다.
     */
    private fun enableAudioEffects(sessionId: Int) {
        if (NoiseSuppressor.isAvailable()) {
            audioEffects += NoiseSuppressor.create(sessionId).apply { enabled = true }
        }
        if (AutomaticGainControl.isAvailable()) {
            audioEffects += AutomaticGainControl.create(sessionId).apply { enabled = true }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            audioEffects += AcousticEchoCanceler.create(sessionId).apply { enabled = true }
        }
        Log.i(TAG, "오디오 전처리 ${audioEffects.size}개 켜짐")
    }

    private fun releaseAudioEffects() {
        audioEffects.forEach { it.release() }
        audioEffects.clear()
    }

    private fun buildConfig(): OnlineRecognizerConfig {
        val config = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = getModelConfig(type = KOREAN_STREAMING_ZIPFORMER)!!,
            // 말이 끝났다고 보는 무음 길이. 기본 1.4초를 2.0초로 늘렸더니 폰에서 말이 끝나고 실행까지
            // 2초 넘게 걸려 1.2초로 줄였다가, 생각하느라 멈추는 것이 문장을 끊어서 1.8초로 늘렸다.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, ENDPOINT_RULE1_SECONDS, 0.0f),
                rule2 = EndpointRule(true, ENDPOINT_RULE2_SECONDS, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = true,
            // 핫워드를 쓰려면 modified_beam_search 여야 한다. greedy_search 에서 바꿨다.
            decodingMethod = "modified_beam_search",
            // 유지할 후보 경로 수. 기본 4에서 늘렸다. 더 키우면 느려지기만 한다.
            maxActivePaths = 8,
            // 핫워드 가중치. 기본 1.5는 체감이 약했다. 너무 키우면 비슷한 발음을 전부
            // 핫워드로 끌어당겨 오히려 틀린다.
            hotwordsScore = HotwordList.GLOBAL_SCORE,
        )
        // 예제는 1스레드 고정인데, 디코딩이 실시간을 못 따라가면 인식이 밀린다.
        config.modelConfig.numThreads = 4
        // 핫워드를 글자 단위로 처리한다. 이 값이 비어 있으면 핫워드를 넘기는 순간
        // 라이브러리가 로그도 예외도 없이 프로세스를 끝낸다.
        config.modelConfig.modelingUnit = "cjkchar"
        return config
    }

    private companion object {
        const val TAG = "SherpaSpeechSource"

        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // ---- 소리 문턱 ----
        // 사람 많은 실내에서 말하지 않을 때도 0.015~0.035 가 나왔고 실제 명령은 0.07~0.30 이었다.
        // 최소 문턱을 0.025 로 올리고, 주변 바닥 소음의 1.8배로 따라 올리되 0.05 를 넘기지 않는다
        // (조용히 말하는 사람이 안 잡히면 안 된다). 작은 목소리가 안 잡히면 최소값을 먼저 내린다.
        const val VAD_MIN_THRESHOLD = 0.025
        const val VAD_NOISE_MULTIPLIER = 1.8
        const val VAD_MAX_THRESHOLD = 0.05

        // ---- 문장 끝을 정하는 값들 ----
        // 소리가 멎은 뒤 VAD 문을 더 열어 두는 프레임 수(100ms 단위, 1.8초). 이 시간 동안 소리가 없으면 문이 닫히고
        // 끝나지 않은 문장을 끝낸다. 인식기 끝점 규칙 rule2(글자 뒤 무음 1.8초)와 같다. 인식기 지연 때문에
        // rule2 가 먼저 채워지지 않아도 문이 닫힐 때 무음을 밀어 넣어(FLUSH_SILENCE_SAMPLES) 끝낸다.
        // 말이 중간에서 잘리면 이 값과 rule2/rule1 을 같이 본다.
        const val VAD_HANGOVER_FRAMES = 18

        /** 끝점 규칙 1(글자 없이 무음)과 규칙 2(글자 뒤 무음)의 길이(초). */
        // 생각하느라 잠깐 멈춘 것을 문장 끝으로 보지 않도록 1.2→1.8초, 1.5→2.2초로 늘렸다(늘어난 만큼 응답이 늦는다).
        const val ENDPOINT_RULE1_SECONDS = 2.2f
        const val ENDPOINT_RULE2_SECONDS = 1.8f

        /** 한 프레임의 길이(ms). READ_INTERVAL_SECONDS 와 같다. */
        const val FRAME_MS = 100L

        /** 끝점 규칙 3 의 문장 최대 길이(20초). buildConfig 의 rule3 와 같아야 로그가 맞다. */
        const val ENDPOINT_MAX_UTTERANCE_MS = 20_000L

        /** 한 문장으로 모아 둘 수 있는 최대 길이. 100ms 프레임 300개, 30초. */
        const val MAX_UTTERANCE_FRAMES = 300

        /** 문이 닫힐 때 인식기에 밀어 넣는 무음 길이. endpoint 무음 규칙 2(1.8초)를 넘기는 2.4초다. */
        const val FLUSH_SILENCE_SAMPLES = (2.4 * SAMPLE_RATE).toInt()

        /** 문이 열리기 전 몇 프레임을 들고 있을지. 100ms 단위라 0.3초다. */
        const val PRE_ROLL_FRAMES = 3

        /** 한 번에 읽어 들이는 길이. 100ms 마다 인식기에 넣는다. */
        const val READ_INTERVAL_SECONDS = 0.1

        /** `getModelConfig` 의 한국어 streaming zipformer(2024-06-16). */
        const val KOREAN_STREAMING_ZIPFORMER = 14

        /**
         * 인식 가중치를 높일 단어. 한글을 그대로 적으면 된다(`modelingUnit = "cjkchar"`).
         *
         * 앱 이름과 명령어처럼 항상 똑같이 나오는 단어만 넣는다. 책 제목은 넣지 않는다 —
         * 넣을 수 있는 책은 몇 권뿐인데 그 책들로 인식이 쏠려 나머지가 더 틀리게 된다.
         */
        const val TOKENS_ASSET = "sherpa-onnx-streaming-zipformer-korean-2024-06-16/tokens.txt"

        val HOTWORDS_FIXED = HotwordList.FIXED
    }
}

/**
 * VAD 문이 열림에서 닫힘으로 바뀌는 순간 인식기에 끝나지 않은 문장이 있는지.
 *
 * [pendingText] 는 마지막으로 본 중간 결과이고, endpoint 로 끝난 문장은 비워진다.
 */
internal fun shouldFlushOnGateClose(wasOpen: Boolean, pendingText: String): Boolean =
    wasOpen && pendingText.isNotBlank()

/**
 * 인식 결과를 토큰에서 직접 만든다.
 *
 * `OnlineRecognizerResult.text` 를 그대로 쓰면 띄어쓰기가 전부 사라진다. sherpa-onnx 가
 * 텍스트를 합친 뒤 `RemoveSpaceBetweenCjk()` 로 CJK 글자 사이 공백을 지우는데, 그 함수의
 * CJK 범위(0xA840~0xD7AF)에 한글 음절(U+AC00~U+D7A3)이 들어가기 때문이다. 중국어와
 * 일본어에는 맞고 한국어에는 틀리다.
 *
 * 토큰 배열에는 어절 경계가 공백으로 남아 있어서 이어붙이면 띄어쓰기가 살아난다.
 * 띄어쓰기가 없으면 뒤에 붙는 LLM 이 명령을 해석하기 어려워진다.
 */
private fun OnlineRecognizerResult.textWithSpacing(): String = tokens.joinToString("").trim()
