package com.sihoo.aemx.assistant

/**
 * 사용자 화면(캡슐, 패널, 음성)에 나가는 실패 문장. 개발 로그의 문장은 그대로 두고, 여기서 사용자용으로 바꾼다.
 *
 * 개발 문장("재분석을 3번 했는데도 풀리지 않아 멈춘다", "LLM 오류: …")을 그대로 보여 주지 않으려는 것이다.
 * 알아볼 수 없는 문장은 일반 안내로 바꾼다. 순수 함수라서 안드로이드 없이 테스트한다.
 */
object UserMessages {

    const val GIVE_UP = "이 화면에서 더 진행하지 못했어요"
    const val UNDERSTAND_FAILED = "요청을 이해하지 못했어요"
    const val SERVICE_OFF = "접근성 서비스를 켜 주세요"
    const val GENERIC = "문제가 생겨 멈췄어요"

    /** 실패 문장을 사용자용 한 줄로. 빈 문장이면 일반 안내. */
    fun forFailure(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return GENERIC
        return when {
            // 재분석 상한, 같은 화면 반복, 안전장치 모두 "여기서는 더 못 간다"는 뜻이다.
            text.contains("재분석") || text.contains("같은 화면") || text.contains("안전장치") -> GIVE_UP
            text.contains("LLM") || text.contains("클라우드") || text.contains("계획") && text.contains("못") ->
                UNDERSTAND_FAILED
            text.contains("접근성 서비스") && text.contains("꺼") -> SERVICE_OFF
            // 이미 사용자 말로 쓴 문장(안내 문장)은 그대로 둔다.
            text.endsWith("요") || text.endsWith("요.") -> text
            else -> GENERIC
        }
    }
}
