package com.sihoo.aemx.pipeline.device

import com.sihoo.aemx.pipeline.PipelineState

/** 명령이 끝났을 때 말할 문구. 화면이 strings.xml 에서 채워서 넘긴다. */
data class AnnouncementTexts(
    val finished: String,
    val failed: String,
    val needsUser: String,
    val needsSetup: String,
    val replanning: String
)

/**
 * 오케스트레이터 상태가 끝(최종 상태)이거나 재분석에 들어갔으면 말할 문구를, 아니면 null 을 돌려준다.
 *
 * 어댑터가 step 마다 `announceCompletion = false` 로 제출해서 서비스의 끝 안내가 나오지 않는다.
 * 그래서 명령 하나가 끝났을 때 한 번만 말하도록 상태를 보고 정한다.
 *
 * Idle 은 말하지 않는다. 오케스트레이터는 취소해도 Idle 로 돌아갈 뿐 취소 상태가 따로 없고,
 * 사용자가 직접 멈춘 것이라 알릴 필요도 없다. 진행 중인 상태도 말하지 않는다.
 *
 * 재분석(이슈 #17)은 replanCount 가 1 이상인 Planning 이 될 때 한 번 알린다. 처음 계획(0)은 말하지
 * 않는다. 재분석이 거듭되면 그때마다(최대 maxReplans 번) 새 Planning 이 되어 다시 말한다.
 *
 * 재분석 직후 바로 끝나서 끝 안내가 나오면 [AndroidTtsController.speak] 의 QUEUE_FLUSH 가 앞 문장
 * ("다시 시도합니다")을 끊는다. 그게 맞다. 끝 결과가 더 중요하고, 둘을 이어 말하면 사용자가 결과를
 * 늦게 듣는다. 끊긴 재분석 안내는 이미 지난 정보라 잃는 것이 없다. 반대로 끝 안내가 재분석 안내보다
 * 먼저 나가는 일은 없다(상태가 재분석 → 끝 순서로만 바뀐다).
 */
fun announcementFor(state: PipelineState, texts: AnnouncementTexts): String? = when (state) {
    is PipelineState.Finished -> texts.finished
    is PipelineState.Failed -> texts.failed
    is PipelineState.NeedsUser -> texts.needsUser
    is PipelineState.NeedsSetup -> texts.needsSetup
    is PipelineState.Planning -> if (state.replanCount >= 1) texts.replanning else null
    is PipelineState.Idle,
    is PipelineState.Listening,
    is PipelineState.Executing,
    is PipelineState.Verifying -> null
}
