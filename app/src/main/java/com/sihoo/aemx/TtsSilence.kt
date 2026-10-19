package com.sihoo.aemx

/**
 * 앱이 말하는 동안 마이크를 막아야 하는지 알려 준다.
 *
 * 앱이 내는 소리("화면 조작을 마쳤습니다…")가 마이크로 되돌아 들어가 명령처럼 인식되는 것을
 * 실기기 로그에서 확인했다. 기기의 에코 제거가 이 소리를 다 지우지 못한다. TTS 와 인식은
 * 서로 다른 곳에 있어서, 둘이 같이 보는 프로세스 단위 상태로 둔다.
 */
internal object TtsSilence {

    @Volatile private var startedAt = NEVER
    @Volatile private var endedAt = NEVER

    fun begin(now: Long = System.currentTimeMillis()) {
        startedAt = now
    }

    fun end(now: Long = System.currentTimeMillis()) {
        endedAt = now
        startedAt = NEVER
    }

    /**
     * 지금 들은 소리를 버려야 하면 true.
     *
     * 끝났다는 신호를 못 받는 경우(TTS 엔진 오류)에도 마이크가 영영 막히지 않도록 말하기
     * 시작 후 [MAX_SPEAKING_MS] 가 지나면 막지 않는다. 끝난 뒤에도 잔향이 있어 [TAIL_MS] 만큼
     * 더 막는다.
     */
    fun isMuted(now: Long = System.currentTimeMillis()): Boolean {
        val started = startedAt
        if (started != NEVER && now - started < MAX_SPEAKING_MS) return true
        return endedAt != NEVER && now - endedAt < TAIL_MS
    }

    private const val NEVER = Long.MIN_VALUE
    private const val TAIL_MS = 600L
    private const val MAX_SPEAKING_MS = 15_000L
}
