package com.sihoo.aemx.debug

import com.sihoo.aemx.pipeline.port.SpeechResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 디버깅 패널이 보는 음성 인식의 지금 상태.
 *
 * 호출어 문을 지나기 전의 원본 결과를 담는다. 그래서 호출어 없이 한 말도 보인다.
 */
data class SpeechTraceState(
    /** VAD 문이 열려 있는지. VAD 를 쓰지 않으면 항상 열림이다. */
    val gateOpen: Boolean = false,
    val rms: Double = 0.0,
    val threshold: Double = 0.0,
    /** 지금 말하고 있는 문장의 중간 결과. 문장이 끝나면 비운다. */
    val partial: String = "",
    /** 끝난 문장들. 새것이 앞이다. */
    val finals: List<String> = emptyList(),
    val error: String? = null,
) {
    /** 중간 결과를 띄어쓰기로 나눈 단어들. */
    val words: List<String> get() = SpeechTrace.words(partial)
}

/**
 * 음성 인식 상태를 한곳에 모은다. 디버깅 모드가 켜져 있을 때만 받는다.
 *
 * 인식된 글은 사용자 말이라 메모리에만 두고 로그나 파일에 남기지 않는다. 모드를 끄면 지운다.
 */
object SpeechTrace {

    private val _state = MutableStateFlow(SpeechTraceState())
    val state: StateFlow<SpeechTraceState> = _state.asStateFlow()

    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) _state.value = SpeechTraceState()
        }

    /** 프레임(100ms)마다 소리 크기와 문 상태를 받는다. 녹음 스레드에서 불린다. */
    fun onLevel(rms: Double, threshold: Double, gateOpen: Boolean) {
        if (!enabled) return
        _state.update { it.copy(rms = rms, threshold = threshold, gateOpen = gateOpen) }
    }

    fun onResult(result: SpeechResult) {
        if (!enabled) return
        _state.update { reduce(it, result) }
    }

    /** 결과 하나를 상태에 반영한다. */
    fun reduce(state: SpeechTraceState, result: SpeechResult): SpeechTraceState = when (result) {
        is SpeechResult.Partial -> state.copy(partial = result.text, error = null)
        is SpeechResult.Final -> state.copy(
            partial = "",
            finals = if (result.text.isBlank()) state.finals else (listOf(result.text) + state.finals).take(MAX_FINALS),
            error = null,
        )
        is SpeechResult.Error -> state.copy(error = result.message)
    }

    fun words(text: String): List<String> = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** 화면에 남겨 둘 끝난 문장 수. */
    const val MAX_FINALS = 8
}
