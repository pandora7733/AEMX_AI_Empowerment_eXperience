package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import com.sihoo.aemx.pipeline.model.Expectation
import com.sihoo.aemx.pipeline.model.UnchangedOk
import com.sihoo.aemx.pipeline.port.AppResolver

/**
 * step 마다 "실행 뒤 화면이 어떠해야 하는가"를 채워 넣는다.
 *
 * ## 왜 필요한가
 *
 * 화면 변화를 검증하려면 기대치가 있어야 하는데, 지금 LLM 프롬프트는 그런 필드를
 * 만들지 않는다(`LLM/system_prompt.txt` 의 출력 형식은 action/target/value 뿐).
 * 프롬프트를 고쳐 모델에게 시킬 수도 있지만, 1.7B 모델을 temperature=0 으로
 * 의도 3종까지 검증해 둔 상태라 건드리면 깨질 위험이 크다.
 *
 * 그래서 **action 종류만 보고 이쪽에서 기대치를 유추한다.** 모델이 주는 정보가
 * 늘어나면([ActionStep.expect] 가 비어 있지 않으면) 그 값을 그대로 존중하고 유추하지 않는다.
 *
 * 유추는 보수적으로 한다. 틀린 기대는 멀쩡한 실행을 실패로 만들어 쓸데없는 재분석을
 * 부르므로, "이건 확실하다" 싶은 것만 넣는다.
 */
class ExpectationInferencer(
    private val appResolver: AppResolver
) {

    /** 계획 전체의 step 에 기대치와 타임아웃을 채운 새 계획을 돌려준다. */
    fun enrich(plan: ActionPlan): ActionPlan =
        plan.copy(steps = plan.steps.map { step -> enrich(step, plan.app) })

    fun enrich(step: ActionStep, appName: String): ActionStep {
        // 모델이 직접 기대치를 준 경우에는 손대지 않는다.
        if (!step.expect.isEmpty) return step

        return when (step.action) {
            ActionType.OPEN_APP -> {
                val wanted = step.target ?: appName
                val packageName = appResolver.resolvePackage(wanted)
                step.copy(
                    expect = Expectation(
                        // 패키지를 못 찾으면 패키지 조건은 걸지 않는다.
                        // 앱이 안 깔려 있을 수도 있고, 라벨이 다를 수도 있다.
                        packageName = packageName,
                        requireScreenChange = true,
                        note = if (packageName != null) {
                            "$wanted 앱($packageName) 화면이 떠야 한다"
                        } else {
                            "$wanted 앱 화면으로 바뀌어야 한다(패키지명을 몰라 화면 변화만 본다)"
                        }
                    ),
                    timeoutMs = APP_LAUNCH_TIMEOUT_MS
                )
            }

            ActionType.TAP -> step.copy(
                expect = Expectation(
                    requireScreenChange = true,
                    note = "\"${step.target}\" 을(를) 누르면 화면이 반응해야 한다"
                )
            )

            ActionType.INPUT_TEXT -> {
                // 입력한 내용이 화면에 보이는 게 정상이다. 다만 검색어가 길면
                // 줄바꿈·말줄임으로 잘려 보일 수 있어 앞부분만 확인한다.
                val typed = step.value?.trim().orEmpty()
                val probe = typed.take(INPUT_PROBE_LENGTH)
                step.copy(
                    expect = if (probe.isEmpty()) {
                        Expectation(note = "입력할 내용이 비어 있어 확인을 건너뛴다")
                    } else {
                        Expectation(
                            anyText = listOf(probe),
                            note = "입력한 \"$probe\" 이(가) 화면에 보여야 한다"
                        )
                    }
                )
            }

            // 검색은 이번 검색어가 화면에 보여야 성공이다. 화면이 바뀌었다는 것만으로는 옛 결과 화면과
            // 구별되지 않는다(같은 앱의 이전 검색 결과가 그대로 떠 있을 수 있다). 앞부분만 본다.
            ActionType.SEARCH -> {
                val packageName = appResolver.resolvePackage(appName)
                val probe = step.target?.trim().orEmpty().take(SEARCH_PROBE_LENGTH).trim()
                step.copy(
                    expect = if (probe.isEmpty()) {
                        Expectation(requireScreenChange = true, note = "검색하면 결과 화면으로 넘어가야 한다")
                    } else {
                        Expectation(
                            packageName = packageName,
                            anyText = listOf(probe),
                            note = "\"$probe\" 검색 결과 화면이어야 한다"
                        )
                    }
                )
            }

            // 쇼츠 열기도 영상 열기와 같다. 유튜브 앱 화면이 쇼츠 피드로 바뀌어야 한다.
            ActionType.OPEN_SHORTS -> step.copy(
                expect = Expectation(
                    packageName = appResolver.resolvePackage(appName),
                    requireScreenChange = true,
                    note = "유튜브 쇼츠 화면으로 넘어가야 한다"
                )
            )

            // 영상 열기는 유튜브 앱 화면으로 바뀌어야 한다. 재생 화면 글자는 믿기 어려워 앱과 화면 변화만 본다.
            ActionType.OPEN_VIDEO -> step.copy(
                expect = Expectation(
                    packageName = appResolver.resolvePackage(appName),
                    requireScreenChange = true,
                    note = "유튜브 영상 화면으로 넘어가야 한다"
                )
            )

            // 영상 재생과 다음 영상은 화면이 바뀌는 것 말고 확인할 방법이 마땅치 않다.
            // 재생이 실제로 시작됐는지는 접근성 정보로 알기 어렵다(ACCESSIBILITY/README.md).
            ActionType.PLAY_FIRST, ActionType.NEXT_VIDEO -> step.copy(
                expect = Expectation(
                    requireScreenChange = true,
                    note = "영상이 바뀌면 화면 내용도 달라진다"
                )
            )

            // 화면 변화를 요구하지 않는다. 화면 지문은 위쪽 글자 일부만 보는데, 웹 결과처럼 위쪽이
            // 그대로인 화면은 스크롤해도 같아 보여서 성공한 스크롤이 불일치로 판정되고 계획 전체가
            // 되풀이됐다(실기기). 끝까지 내려가 더 안 움직이는 경우도 실패가 아니다. 제스처를 못
            // 보냈으면 실행모듈이 직접 실패를 알린다.
            ActionType.SCROLL -> step.copy(
                expect = Expectation(note = "스크롤은 실행모듈이 성공을 알리면 통과한다")
            )

            // 대기는 오히려 화면이 그대로일 수도 있다. 아무것도 요구하지 않는다.
            ActionType.WAIT -> step.copy(
                expect = Expectation(note = "대기만 한다"),
                timeoutMs = WAIT_TIMEOUT_MS
            )

            // 보낸 뒤 확인은 실행모듈(MessageSender)이 마지막 말풍선으로 직접 한다. 여기서는 요구하지 않는다.
            ActionType.SEND_MESSAGE -> step.copy(
                expect = Expectation(note = "메시지 전송은 실행모듈이 성공을 알리면 통과한다"),
                timeoutMs = SEND_MESSAGE_TIMEOUT_MS
            )

            ActionType.UNKNOWN -> step
        }
    }

    private companion object {
        /** 앱 실행은 콜드 스타트면 오래 걸린다. */
        const val APP_LAUNCH_TIMEOUT_MS = 10_000L
        const val WAIT_TIMEOUT_MS = 3_000L
        const val SEND_MESSAGE_TIMEOUT_MS = 45_000L

        /** 입력 확인에 쓸 앞부분 길이. 너무 길게 잡으면 잘려 보일 때 오탐이 난다. */
        const val INPUT_PROBE_LENGTH = 6

        /** 이미 떠 있는 검색 결과를 알아보는 검색어 앞부분 길이. */
        const val SEARCH_PROBE_LENGTH = 8
    }
}
