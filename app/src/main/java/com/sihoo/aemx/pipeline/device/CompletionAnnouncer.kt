package com.sihoo.aemx.pipeline.device

import com.sihoo.aemx.pipeline.PipelineState

/** 명령이 끝났을 때 말할 문구. 화면이 strings.xml 에서 채워서 넘긴다. */
data class AnnouncementTexts(
    val finished: String,
    val failed: String,
    val needsUser: String,
    val needsSetup: String
)

/**
 * 오케스트레이터 상태가 끝(최종 상태)이면 그때 말할 문구를, 아니면 null 을 돌려준다.
 *
 * 어댑터가 step 마다 `announceCompletion = false` 로 제출해서 서비스의 끝 안내가 나오지 않는다.
 * 그래서 명령 하나가 끝났을 때 한 번만 말하도록 상태를 보고 정한다.
 *
 * Idle 은 말하지 않는다. 오케스트레이터는 취소해도 Idle 로 돌아갈 뿐 취소 상태가 따로 없고,
 * 사용자가 직접 멈춘 것이라 알릴 필요도 없다. 진행 중인 상태도 말하지 않는다.
 */
fun announcementFor(state: PipelineState, texts: AnnouncementTexts): String? = when (state) {
    is PipelineState.Finished -> texts.finished
    is PipelineState.Failed -> texts.failed
    is PipelineState.NeedsUser -> texts.needsUser
    is PipelineState.NeedsSetup -> texts.needsSetup
    is PipelineState.Idle,
    is PipelineState.Listening,
    is PipelineState.Planning,
    is PipelineState.Executing,
    is PipelineState.Verifying -> null
}
