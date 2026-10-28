package com.sihoo.aemx.pipeline.llm

/**
 * 클라우드 AI 에 글 하나를 묻고 답을 받는 경계. 서비스(Groq, Gemini …)마다 구현만 바꾼다.
 *
 * 구현은 실패를 예외로 던진다. 부르는 쪽([CloudPlanGenerator])이 모두 잡아서 로컬로 되돌린다.
 */
interface CloudChatClient {
    /** @return 모델이 낸 글(JSON 계획이 들어 있어야 한다). */
    suspend fun complete(system: String, user: String): String
}

/** 네트워크·인증·한도 오류. 메시지에는 키나 사용자 발화를 넣지 않는다. */
class CloudChatException(message: String, val httpCode: Int? = null) : Exception(message)
