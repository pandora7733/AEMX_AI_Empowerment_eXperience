package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.pipeline.json.ActionPlanParser
import com.sihoo.aemx.pipeline.json.ParseResult
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionType
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.PlanGenerator
import com.sihoo.aemx.pipeline.port.PlanRequest
import com.sihoo.aemx.pipeline.port.PlanResult
import kotlinx.coroutines.CancellationException

/**
 * 클라우드 AI 로 계획을 만든다. 로컬과 같은 JSON 스키마·파서·가드를 쓴다.
 *
 * 실패(네트워크, 한도, 형식 오류)는 모두 [PlanResult.EngineError]/[PlanResult.Malformed] 로 돌려준다.
 * 그러면 [HybridPlanGenerator] 가 로컬 계획으로 되돌린다.
 *
 * 개인정보는 여기서도 막는다. [CloudRoutingPolicy] 가 개인정보 동작이라고 보면 호출 자체를 거절한다.
 */
class CloudPlanGenerator(
    private val client: CloudChatClient,
    private val systemPrompt: String,
    private val log: (String) -> Unit = {},
) : PlanGenerator {

    override fun isReady(): Boolean = true

    override suspend fun generate(request: PlanRequest): PlanResult {
        // 마지막 방어선. 부르는 쪽 실수로 개인정보 명령이 들어와도 보내지 않는다.
        if (!CloudRoutingPolicy.mayUploadScreen(request.utterance, null)) {
            return PlanResult.EngineError("개인정보 명령은 클라우드로 보내지 않는다")
        }
        if (request.mode == PlanRequest.Mode.REPLAN) {
            val appHint = request.screen?.packageName
            if (!CloudRoutingPolicy.mayUploadScreen(request.utterance, appHint)) {
                return PlanResult.EngineError("개인정보 앱 화면은 클라우드로 보내지 않는다")
            }
        }

        val raw = try {
            client.complete(systemPrompt, buildPrompt(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudChatException) {
            log("클라우드 실패: ${e.message}")
            return PlanResult.EngineError(e.message ?: "클라우드 오류")
        } catch (e: Exception) {
            log("클라우드 실패: ${e::class.java.simpleName}")
            return PlanResult.EngineError(e::class.java.simpleName)
        }

        return when (val parsed = ActionPlanParser.parse(raw)) {
            is ParseResult.Success -> PlanGuards.apply(request, parsed.plan, raw, parsed.warnings)
            is ParseResult.Failure -> PlanResult.Malformed(raw, parsed.reason)
        }
    }

    /** 로컬과 같은 문장 형식. 재분석 규칙은 요청과 함께 붙인다. */
    internal fun buildPrompt(request: PlanRequest): String = when (request.mode) {
        PlanRequest.Mode.INITIAL -> request.utterance

        PlanRequest.Mode.REPLAN -> buildString {
            appendLine("원래 명령: ${request.utterance}")
            appendLine()
            appendLine("지금까지 한 것:")
            request.history.forEach { appendLine("- ${it.toPromptLine()}") }
            appendLine()
            request.reason?.let { appendLine("문제: $it") }
            request.screen?.let { appendLine(describeScreen(it)) }
            appendLine()
            appendLine("현재 화면에서 이어서 할 일을 새 계획으로 만들어 주세요.")
            appendLine("- 이미 성공한 단계는 넣지 마세요.")
            appendLine("- 누르려던 것이 없으면 위에 보이는 것 중 같은 역할을 하는 것을 고르세요.")
            appendLine("- 목적과 관계없는 것은 누르지 마세요.")
            append("- 쓸 만한 것이 없으면 scroll 로 더 찾아보세요.")
        }

        PlanRequest.Mode.REPAIR_JSON -> buildString {
            appendLine("직전 응답이 형식에 맞지 않았습니다: ${request.parseError}")
            appendLine()
            appendLine("원래 명령: ${request.utterance}")
            append("설명 없이 JSON만 다시 출력해 주세요.")
        }
    }

    private fun describeScreen(screen: ScreenSnapshot): String = buildString {
        appendLine("현재 화면:")
        screen.packageName?.let { appendLine("- 앱: $it") }
        if (screen.isDialog) appendLine("- 팝업이 떠 있음")
        if (screen.texts.isNotEmpty()) {
            appendLine("- 보이는 글자: ${screen.texts.take(MAX_SCREEN_TEXTS).joinToString(", ")}")
        }
        if (screen.clickableLabels.isNotEmpty()) {
            append("- 누를 수 있는 것: ${screen.clickableLabels.take(MAX_SCREEN_TEXTS).joinToString(", ")}")
        }
    }

    private companion object {
        const val MAX_SCREEN_TEXTS = 20
    }
}

/**
 * [LlamaPlanGenerator.generate] 의 가드 순서를 그대로 옮긴 것이다. 로컬 쪽 코드는 건드리지 않았다.
 *
 * 가드를 고치면 **두 곳**을 같이 고쳐야 한다. 테스트로 로컬 동작이 확인되면 로컬도 이 함수를 쓰도록 합친다.
 */
internal object PlanGuards {

    fun apply(
        request: PlanRequest,
        parsedPlan: ActionPlan,
        raw: String,
        warnings: List<String>,
    ): PlanResult {
        val plan = AppMentionGuard.apply(request.utterance, parsedPlan)
        AppMentionGuard.searchRefusal(request.utterance, plan)?.let { return PlanResult.Refused(it) }
        MessageChannelGuard.refusal(request.utterance, plan)?.let { return PlanResult.Refused(it) }
        if (MessageChannelGuard.asksToSend(request.utterance) &&
            plan.steps.none { it.action == ActionType.SEND_MESSAGE }
        ) {
            return if (request.mode == PlanRequest.Mode.INITIAL) {
                PlanResult.Malformed(raw, "보내기 요청인데 send_message 가 없다")
            } else {
                PlanResult.Refused("메시지 보내기 계획을 만들지 못해 보내지 않았어요")
            }
        }
        val channelChecked = when (val channel = MessageChannelGuard.apply(request.utterance, plan)) {
            is MessageChannelGuard.Result.Ok -> channel.plan
            is MessageChannelGuard.Result.Unsupported -> return PlanResult.Refused(channel.reason)
        }
        val checked = SendMessageGuard.check(request.utterance, channelChecked)
            ?: return PlanResult.Malformed(raw, "send_message 의 value 가 사용자가 말한 내용에 없다")
        return PlanResult.Success(PlayOrdinalGuard.apply(request.utterance, checked), raw, warnings)
    }
}
