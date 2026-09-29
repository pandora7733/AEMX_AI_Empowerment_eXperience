package com.sihoo.aemx.pipeline.port

import kotlinx.coroutines.flow.Flow

/**
 * STT 파트(sherpa-onnx OnlineRecognizer)를 감싸는 경계.
 *
 * app 모듈은 sherpa-onnx 에 직접 의존하지 않는다. STT 모듈이 합류하면
 * 이 인터페이스를 구현하는 어댑터 하나만 추가하면 된다.
 */
interface SpeechSource {

    /** 마이크 권한과 모델 로딩이 끝나 바로 들을 수 있는 상태인지. */
    fun isReady(): Boolean

    /** 듣기 시작. 결과는 [results] 로 흘러나온다. */
    fun start()

    /** 듣기 중단. 이미 멈춰 있으면 아무 일도 하지 않는다. */
    fun stop()

    val results: Flow<SpeechResult>
}

sealed interface SpeechResult {
    /** 인식 중간 결과. 화면에 실시간으로 보여 주기만 하고 파이프라인을 돌리지는 않는다. */
    data class Partial(val text: String) : SpeechResult

    /**
     * 최종 결과. sherpa-onnx 의 endpoint 판정으로 한 발화가 끝났을 때 나온다.
     * 이 값이 파이프라인을 돌리는 방아쇠다.
     */
    data class Final(val text: String) : SpeechResult

    data class Error(val message: String) : SpeechResult
}
