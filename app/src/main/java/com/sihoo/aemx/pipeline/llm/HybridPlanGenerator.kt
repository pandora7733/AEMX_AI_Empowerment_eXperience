package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.pipeline.port.PlanGenerator
import com.sihoo.aemx.pipeline.port.PlanRequest
import com.sihoo.aemx.pipeline.port.PlanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 어려운 명령은 클라우드를 먼저 부르고, 그 밖의 명령은 기기에서 만든다.
 *
 * 규칙([CloudRoutingPolicy]):
 * - 개인정보 동작(메시지·연락처 등)은 항상 기기에서만 만든다.
 * - 발화가 어려운 동작(영상 재생·넘기기·입력·N번째 등)이면 클라우드를 먼저 묻고, 기기 계획은 만들지 않는다.
 *   클라우드 답이 쓸 만하면 그대로 쓴다. 실패, 시간 초과, 형식 오류, 개인정보 계획이면 기기 계획으로 되돌린다.
 * - 발화는 쉬운데 기기 계획에 어려운 동작이 나오면(단서 누락) 그때 클라우드에 다시 묻는다.
 * - 형식 재요청(REPAIR_JSON)은 기기에서만 한다.
 * - 클라우드가 꺼져 있거나(키 없음) 없으면 지금과 똑같이 기기에서만 만든다.
 *
 * 어디서 처리했는지는 [onRoute] 로 알린다(개발 화면 표시용). 같은 내용이 [log] 로도 나간다.
 *
 * @param cloud 켜져 있을 때만 돌려주는 함수. 호출마다 불러서 설정 변경이 바로 반영된다.
 */
class HybridPlanGenerator(
    private val local: PlanGenerator,
    private val cloud: () -> PlanGenerator?,
    private val cloudTimeoutMs: Long = DEFAULT_CLOUD_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
    private val onRoute: (RouteReport) -> Unit = {},
) : PlanGenerator {

    override fun isReady(): Boolean = local.isReady()

    override suspend fun generate(request: PlanRequest): PlanResult {
        // 형식 재요청은 기기 모델이 낸 깨진 응답에 대한 것이므로 기기에서만 처리한다.
        if (request.mode == PlanRequest.Mode.REPAIR_JSON) return local.generate(request)

        val started = System.currentTimeMillis()
        val cloudGenerator = cloud()
        var cloudFailure: String? = null

        // 1) 발화가 어려운 동작이면 기기 계획을 만들기 전에 클라우드를 먼저 부른다.
        if (cloudGenerator != null &&
            CloudRoutingPolicy.routeUtterance(request.utterance) == CloudRoutingPolicy.Route.CLOUD
        ) {
            log("어려운 동작이라 클라우드를 먼저 묻는다")
            when (val attempt = askCloud(cloudGenerator, request)) {
                is CloudAttempt.Usable ->
                    return finish(RouteReport.Via.CLOUD, attempt.model, null, attempt.result, started)
                is CloudAttempt.Failed -> cloudFailure = attempt.reason
            }
        }

        // 2) 기기 계획. 클라우드에서 이미 실패했다면 여기서 다시 묻지 않는다.
        val localResult = local.generate(request)
        val localVia = if (cloudFailure == null) RouteReport.Via.LOCAL else RouteReport.Via.LOCAL_AFTER_CLOUD_FAIL
        val localPlan = (localResult as? PlanResult.Success)?.plan

        // 3) 기기 계획에 어려운 동작이 있는데 아직 클라우드를 안 불렀다면(발화 단서 누락) 다시 묻는다.
        val recheck = cloudGenerator.takeIf {
            it != null && cloudFailure == null && localPlan != null &&
                CloudRoutingPolicy.route(request.utterance, localPlan) == CloudRoutingPolicy.Route.CLOUD
        }
        if (recheck == null) return finish(localVia, null, cloudFailure, localResult, started)

        log("기기 계획에 어려운 동작이 있어 클라우드에 다시 묻는다")
        return when (val attempt = askCloud(recheck, request)) {
            is CloudAttempt.Usable ->
                finish(RouteReport.Via.CLOUD, attempt.model, null, attempt.result, started)
            is CloudAttempt.Failed ->
                finish(RouteReport.Via.LOCAL_AFTER_CLOUD_FAIL, null, attempt.reason, localResult, started)
        }
    }

    /** 클라우드를 시간 한도 안에서 부른다. 답이 쓸 만한지(개인정보 계획이 아닌지)까지 여기서 본다. */
    private suspend fun askCloud(generator: PlanGenerator, request: PlanRequest): CloudAttempt {
        val result = try {
            withTimeoutOrNull(cloudTimeoutMs) { generator.generate(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CloudAttempt.Failed(e::class.java.simpleName)
        }
        return when (result) {
            null -> CloudAttempt.Failed("시간 초과")
            is PlanResult.Success ->
                if (CloudRoutingPolicy.isPersonal(request.utterance, result.plan)) {
                    CloudAttempt.Failed("개인정보 계획")
                } else {
                    CloudAttempt.Usable(result, (generator as? CloudPlanGenerator)?.answeredModel)
                }
            is PlanResult.EngineError -> CloudAttempt.Failed(result.message)
            is PlanResult.Malformed -> CloudAttempt.Failed("형식이 맞지 않음")
            is PlanResult.Refused -> CloudAttempt.Failed("가드가 거절함")
        }
    }

    private fun finish(
        via: RouteReport.Via,
        model: String?,
        cloudFailure: String?,
        result: PlanResult,
        started: Long,
    ): PlanResult {
        val report = RouteReport(via, model, System.currentTimeMillis() - started, cloudFailure)
        log(report.summary())
        onRoute(report)
        return result
    }

    private sealed interface CloudAttempt {
        class Usable(val result: PlanResult.Success, val model: String?) : CloudAttempt
        class Failed(val reason: String) : CloudAttempt
    }

    companion object {
        /** 팀 조건: 모든 작업 5초, 길어도 8초. 클라우드 한 번에 쓸 수 있는 전체 시간이다. */
        const val DEFAULT_CLOUD_TIMEOUT_MS = 5_000L
    }
}

/** 명령 하나를 어디서 처리했는지. 개발 화면과 logcat(CloudPlan) 에 같은 내용으로 보인다. */
data class RouteReport(
    val via: Via,
    /** 클라우드가 답한 모델 이름. 클라우드를 쓰지 않았으면 null. */
    val model: String?,
    val elapsedMs: Long,
    /** 클라우드가 실패한 이유. 실패하지 않았으면 null. */
    val cloudFailure: String?,
) {
    enum class Via { CLOUD, LOCAL, LOCAL_AFTER_CLOUD_FAIL }

    fun summary(): String {
        val seconds = "%.1f초".format(elapsedMs / 1000.0)
        return when (via) {
            Via.CLOUD -> "처리: 클라우드 (${model ?: "모델 미상"}, $seconds)"
            Via.LOCAL -> "처리: 기기 ($seconds)"
            Via.LOCAL_AFTER_CLOUD_FAIL -> "처리: 기기 (클라우드 실패: ${cloudFailure ?: "알 수 없음"}, $seconds)"
        }
    }
}
