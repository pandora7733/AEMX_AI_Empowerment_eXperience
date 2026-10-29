package com.sihoo.aemx.pipeline.llm

import kotlinx.coroutines.withTimeoutOrNull

/**
 * 첫 모델이 실패하면 예비 모델로 한 번 더 묻는다. 전체 마감 시간([totalBudgetMs])을 알고 있어서,
 * 첫 모델은 [primaryTimeoutMs] 까지만 기다리고, 예비 모델은 남은 시간만 쓴다.
 * 남은 시간이 [minFallbackMs] 보다 적으면 예비 모델을 부르지 않고 실패한다.
 *
 * 예비로 넘기는 실패는 시간 초과·한도·서버·네트워크 쪽뿐이다. 인증 실패(401/403)처럼 모델을 바꿔도 같은 실패는 그대로 던진다.
 */
class FallbackChatClient(
    private val primary: CloudChatClient,
    private val fallback: CloudChatClient,
    private val totalBudgetMs: Long = HybridPlanGenerator.DEFAULT_CLOUD_TIMEOUT_MS,
    private val primaryTimeoutMs: Long = PRIMARY_TIMEOUT_MS,
    private val minFallbackMs: Long = MIN_FALLBACK_MS,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : CloudChatClient {

    @Volatile
    private var answeredBy: CloudChatClient? = null

    override val lastModel: String? get() = answeredBy?.lastModel

    override suspend fun complete(system: String, user: String): String {
        val started = now()
        fun remainingMs() = totalBudgetMs - (now() - started)

        try {
            val answer = withTimeoutOrNull(minOf(primaryTimeoutMs, totalBudgetMs)) {
                primary.complete(system, user)
            }
            if (answer != null) return answer.also { answeredBy = primary }
            log("첫 모델 시간 초과, 예비 모델로 다시 묻는다")
        } catch (e: CloudChatException) {
            if (!shouldFallback(e)) throw e
            log("첫 모델 실패, 예비 모델로 다시 묻는다: ${e.message}")
        }

        val left = remainingMs()
        if (left < minFallbackMs) throw CloudChatException("남은 시간이 없어 예비 모델을 부르지 않았다")
        val answer = withTimeoutOrNull(left) { fallback.complete(system, user) }
            ?: throw CloudChatException("예비 모델 시간 초과")
        return answer.also { answeredBy = fallback }
    }

    companion object {
        /** 첫 모델 한 번의 시간 한도. */
        const val PRIMARY_TIMEOUT_MS = 3_000L

        /** 예비 모델을 부르기에 남아 있어야 하는 최소 시간. 이보다 적으면 부르지 않는다. */
        const val MIN_FALLBACK_MS = 800L

        internal fun shouldFallback(e: CloudChatException): Boolean {
            // 코드가 없는 실패는 네트워크 오류나 빈 응답이므로 예비로 넘긴다.
            val code = e.httpCode ?: return true
            return code == 400 || code == 404 || code == 429 || code in 500..599
        }
    }
}
