package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.PipelineEvent
import com.sihoo.aemx.pipeline.PipelineOrchestrator
import com.sihoo.aemx.pipeline.PlannedStep
import com.sihoo.aemx.pipeline.StepStatus
import com.sihoo.aemx.pipeline.model.ActionType

/**
 * 패널 디자인과 모션을 마이크와 명령 없이 보는 시연 장면. 개발 화면의 adb 훅(`--es overlay_demo <장면>`)이 쓴다.
 *
 * 장면은 실제 파이프라인 사건과 같은 순서로 [AssistantStore] 에 흘려보낸다. 그래서 화면은 평소와 같은
 * 경로로 그린다. 장면 이름: executing(실행 중, 패널 펼침), replanning(다시 계획 중, 패널 펼침),
 * needs-human(직접 처리 필요, 패널은 저절로 펼친다), success(성공, 패널은 접힌다).
 */
object OverlayDemo {

    /** 실행 중과 다시 계획 중 장면에서 패널을 펼쳐 둔다(사용자가 캡슐을 누른 것처럼). */
    @Volatile
    var panelOpen = false

    /** 장면 이름에 맞는 사건 목록. 모르는 이름이면 null. 순서대로 흘려보내면 된다. */
    fun events(scene: String, runId: String, nextSequence: () -> Long): List<PipelineEvent>? {
        val steps = listOf(
            PlannedStep("s1", ActionType.OPEN_APP, "유튜브", "유튜브"),
            PlannedStep("s2", ActionType.SEARCH, "고양이", "유튜브"),
            PlannedStep("s3", ActionType.PLAY_FIRST, "1", "유튜브"),
        )
        fun changed(id: String, status: StepStatus, reason: String? = null) =
            PipelineEvent.StepChanged(runId, nextSequence(), id, status, reason)

        val start = listOf(
            PipelineEvent.RunStarted(runId, nextSequence(), "유튜브에서 고양이 영상 틀어줘"),
            PipelineEvent.PlanValidated(runId, nextSequence(), 1, steps),
        )
        return when (scene) {
            EXECUTING -> start + changed("s1", StepStatus.Completed) + changed("s2", StepStatus.InProgress)
            REPLANNING -> start + changed("s1", StepStatus.Completed) + changed("s2", StepStatus.InProgress) +
                PipelineEvent.Replanning(runId, nextSequence(), 1, "화면을 다시 확인하고 있어요")
            NEEDS_HUMAN -> start + changed("s1", StepStatus.Completed) +
                PipelineEvent.NeedsUser(runId, nextSequence(), "로그인 화면이 떠 있어요. 직접 로그인해 주세요")
            SUCCESS -> start + steps.map { changed(it.stepId, StepStatus.Completed) } +
                PipelineEvent.ResultVerified(runId, nextSequence(), "고양이 영상을 틀었어요")
            else -> null
        }
    }

    /** 장면을 화면에 흘려보낸다. 모르는 장면이면 아무것도 하지 않고 false. */
    fun play(scene: String): Boolean {
        panelOpen = scene == EXECUTING || scene == REPLANNING
        val runId = "demo-" + scene.take(8)
        val list = events(scene, runId) { PipelineOrchestrator.SEQUENCE.incrementAndGet() } ?: run {
            panelOpen = false
            return false
        }
        list.forEach { AssistantStore.shared.post(it) }
        return true
    }

    const val EXECUTING = "executing"
    const val REPLANNING = "replanning"
    const val NEEDS_HUMAN = "needs-human"
    const val SUCCESS = "success"
}
