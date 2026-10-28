package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.port.PlanGenerator
import com.sihoo.aemx.pipeline.port.PlanRequest
import com.sihoo.aemx.pipeline.port.PlanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 로컬 계획을 먼저 만들고, 어려운 동작이면 클라우드 계획으로 바꿔 준다.
 *
 * 규칙([CloudRoutingPolicy]):
 * - 개인정보 동작(메시지·연락처 등)은 항상 로컬 계획 그대로.
 * - 어려운 동작(영상 재생·탭·입력 등)만 클라우드에 묻는다.
 * - 클라우드가 꺼져 있거나(키 없음), 실패하거나, 시간이 넘으면 로컬 계획을 그대로 쓴다.
 *
 * 그래서 키를 넣지 않으면 동작이 지금과 완전히 같다.
 *
 * @param cloud 켜져 있을 때만 돌려주는 함수. 호출마다 불러서 설정 변경이 바로 반영된다.
 */
class HybridPlanGenerator(
    private val local: PlanGenerator,
    private val cloud: () -> PlanGenerator?,
    private val cloudTimeoutMs: Long = DEFAULT_CLOUD_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
) : PlanGenerator {

    override fun isReady(): Boolean = local.isReady()

    override suspend fun generate(request: PlanRequest): PlanResult {
        val localResult = local.generate(request)

        // 형식 재요청은 로컬 모델이 낸 깨진 응답에 대한 것이므로 로컬에서만 처리한다.
        if (request.mode == PlanRequest.Mode.REPAIR_JSON) return localResult

        val cloudGenerator = cloud() ?: return localResult
        val localPlan: ActionPlan = (localResult as? PlanResult.Success)?.plan ?: return localResult

        if (CloudRoutingPolicy.route(request.utterance, localPlan) != CloudRoutingPolicy.Route.CLOUD) {
            return localResult
        }

        log("어려운 동작이라 클라우드에 묻는다")
        val cloudResult = try {
            withTimeoutOrNull(cloudTimeoutMs) { cloudGenerator.generate(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        return when (cloudResult) {
            is PlanResult.Success -> {
                log("클라우드 계획을 쓴다")
                cloudResult
            }
            null -> {
                log("클라우드 시간 초과, 로컬 계획을 쓴다")
                localResult
            }
            else -> {
                log("클라우드 계획을 못 받아 로컬 계획을 쓴다")
                localResult
            }
        }
    }

    companion object {
        /** 클라이언트 자체 시간 초과(8초)보다 약간 길게. 둘 중 먼저 걸리는 쪽에서 끝난다. */
        const val DEFAULT_CLOUD_TIMEOUT_MS = 10_000L
    }
}
