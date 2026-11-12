package com.sihoo.aemx.assistant.overlay

/** 빠른 메뉴의 마이크 칸이 보여 주는 상태. */
enum class MicState {
    /** 듣고 있고 소리가 들어온다. */
    LISTENING,

    /** 듣고 있지만 소리가 0 만 들어온다(백그라운드 마이크 제한 등). */
    SILENCED,

    /** 마이크를 여는 중이다. */
    STARTING,

    /** 꺼져 있다. */
    OFF,
}

/** 빠른 메뉴의 순수 규칙. 안드로이드 없이 테스트한다. */
object QuickMenuRules {

    /**
     * 마이크 상태를 정한다.
     *
     * @param listening 듣기가 켜져 있는지(PipelineCore.listening).
     * @param micIdle 꺼져 있고 여는 중도 아닌지(PipelineCore.isMicIdle).
     * @param silenced 소리가 3초 넘게 0 만 들어오는지(MicStatus.silenced).
     */
    fun micState(listening: Boolean, micIdle: Boolean, silenced: Boolean): MicState = when {
        listening && silenced -> MicState.SILENCED
        listening -> MicState.LISTENING
        !micIdle -> MicState.STARTING
        else -> MicState.OFF
    }

    fun micLabel(state: MicState): String = when (state) {
        MicState.LISTENING -> "마이크 켜짐"
        MicState.SILENCED -> "소리가 안 들어옴"
        MicState.STARTING -> "마이크 켜는 중"
        MicState.OFF -> "마이크 꺼짐"
    }

    fun micHint(state: MicState): String = when (state) {
        MicState.LISTENING, MicState.SILENCED -> "누르면 잠시 끕니다"
        MicState.STARTING -> "잠시만 기다려 주세요"
        MicState.OFF -> "누르면 다시 켭니다"
    }

    /** 누르면 끄는 상태인지. 아니면 켠다. 여는 중에는 아무것도 하지 않는다. */
    fun tapTurnsOff(state: MicState): Boolean? = when (state) {
        MicState.LISTENING, MicState.SILENCED -> true
        MicState.OFF -> false
        MicState.STARTING -> null
    }

    /** 소리 크기(RMS)를 막대의 0~100 으로 바꾼다. 실제 명령이 0.07~0.30 이라 0.3 에서 꽉 찬다. */
    fun levelPercent(rms: Double): Int = (rms / LEVEL_FULL_RMS * 100).toInt().coerceIn(0, 100)

    private const val LEVEL_FULL_RMS = 0.3
}
