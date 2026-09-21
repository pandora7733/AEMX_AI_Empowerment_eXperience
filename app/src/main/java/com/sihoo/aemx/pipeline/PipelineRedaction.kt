package com.sihoo.aemx.pipeline

/**
 * 로그와 화면에 내보내기 전에 민감한 값을 가린다.
 *
 * 접근성으로 읽은 화면 텍스트와 계정 저장소에서 꺼낸 자격증명에는 비밀번호가 섞인다.
 * 개발용 디버그 화면이라도 그대로 찍지 않는다.
 */
object PipelineRedaction {

    /**
     * 계획 JSON 의 value 필드를 길이 표시로 바꾼다.
     *
     * 큰따옴표가 많아 이스케이프가 지저분해지므로 raw 문자열로 쓴다.
     * 실제 패턴: "value" 뒤의 콜론과 공백을 건너뛰고, 이스케이프를 존중하며 문자열 본문을 잡는다.
     */
    private val VALUE_FIELD = Regex(""""value"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    fun maskPlanJson(raw: String): String =
        VALUE_FIELD.replace(raw) { match ->
            "\"value\": \"***(" + match.groupValues[1].length + "자)\""
        }

    /** 한 줄짜리 값에 쓰는 가림 처리. */
    fun maskValue(value: String?): String =
        if (value.isNullOrEmpty()) "(없음)" else "***(" + value.length + "자)"
}
