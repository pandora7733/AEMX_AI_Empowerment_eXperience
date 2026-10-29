package com.sihoo.aemx.pipeline

import com.sihoo.aemx.accessibility.Ordinal
import com.sihoo.aemx.accessibility.WaitDuration
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import com.sihoo.aemx.pipeline.model.ExecutedStep
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.model.StepOutcome
import com.sihoo.aemx.pipeline.port.ActionExecutor
import com.sihoo.aemx.pipeline.model.Expectation
import com.sihoo.aemx.pipeline.model.TextMatch
import com.sihoo.aemx.pipeline.port.AppResolver

import com.sihoo.aemx.pipeline.port.ExecutionResult
import com.sihoo.aemx.pipeline.port.PlanGenerator
import com.sihoo.aemx.pipeline.port.PlanRequest
import com.sihoo.aemx.pipeline.port.PlanResult
import com.sihoo.aemx.pipeline.port.ScreenChange
import com.sihoo.aemx.pipeline.port.ScreenObserver
import com.sihoo.aemx.pipeline.port.SpeechResult
import com.sihoo.aemx.pipeline.port.SpeechSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * STT -> LLM -> 실행모듈을 잇는 지휘자.
 *
 * 이 클래스가 하는 일은 다섯 가지다.
 *  1. 발화(또는 입력한 텍스트)를 받아 LLM 에 실행계획을 요청한다.
 *  2. 계획의 step 을 실행모듈이 이해하는 명령으로 바꾼다.
 *     `open_app` 은 앱 이름을 기기의 패키지명으로 바꾼다.
 *  3. 명령을 하나씩 실행모듈에 넘긴다. **루프는 여기가 돈다.**
 *  4. step 마다 화면이 안정될 때까지 기다렸다가 기대대로 바뀌었는지 검사한다.
 *  5. 기대와 다르면 현재 화면 + 원래 명령 + 지금까지의 기록을 묶어 다시 LLM 에 물어본다.
 *
 * 모든 바깥 파트가 인터페이스 너머에 있어서, 아직 합류하지 않은 파트는
 * Fake 구현으로 갈아 끼운 채 개발할 수 있다.
 */
class PipelineOrchestrator(
    private val planner: PlanGenerator,
    private val executor: ActionExecutor,
    private val screenObserver: ScreenObserver,
    private val appResolver: AppResolver,
    private val scope: CoroutineScope,
    private val speech: SpeechSource? = null,
    private val config: PipelineConfig = PipelineConfig(),
    /** LLM 추론과 실행은 메인스레드를 막으면 안 된다. */
    private val worker: CoroutineDispatcher = Dispatchers.Default
) {

    private val inferencer = ExpectationInferencer(appResolver)

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<PipelineLogEntry>>(emptyList())
    val log: StateFlow<List<PipelineLogEntry>> = _log.asStateFlow()

    /** 가장 최근에 LLM 이 내놓은 계획 JSON(입력값은 가려져 있다). 디버그 화면에 그대로 띄운다. */
    private val _lastPlanJson = MutableStateFlow<String?>(null)
    val lastPlanJson: StateFlow<String?> = _lastPlanJson.asStateFlow()

    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = EVENT_BUFFER)

    /**
     * 화면(오버레이)용 사건 흐름. [state] 와 별개로 하나 더 내보내며 runId, sequence 가 붙는다.
     * 구독하기 전의 사건은 받지 못하므로 화면은 오케스트레이터를 만들자마자 구독한다.
     */
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private var runJob: Job? = null
    private var listenJob: Job? = null

    /** 가장 최근에 시작한 명령. 중단 확인이 어느 명령의 것인지 가리는 데 쓴다. */
    @Volatile
    private var activeRun: Run? = null

    val isBusy: Boolean
        get() = runJob?.isActive == true

    /** 가장 최근에 시작한 명령의 runId. 화면이 보낸 중단이 이 명령의 것인지 맞춰 볼 때 쓴다. */
    val currentRunId: String?
        get() = activeRun?.id

    // ---------------------------------------------------------------- 입력

    /** 마이크를 열고 STT 결과를 기다린다. 최종 결과가 나오면 곧바로 [submit] 한다. */
    fun startListening() {
        val source = speech
        if (source == null) {
            append(PipelineLogEntry.Level.ERROR, "STT 모듈이 연결되지 않았다")
            return
        }
        if (!source.isReady()) {
            _state.value = PipelineState.NeedsSetup("마이크 권한이나 STT 모델이 준비되지 않았다")
            return
        }
        if (isBusy) {
            append(PipelineLogEntry.Level.WARN, "이미 명령을 처리하는 중이다")
            return
        }

        listenJob?.cancel()
        listenJob = scope.launch {
            source.results.collect { result ->
                when (result) {
                    is SpeechResult.Partial ->
                        if (_state.value is PipelineState.Listening) {
                            _state.value = PipelineState.Listening(result.text)
                        }

                    is SpeechResult.Final -> {
                        source.stop()
                        append(PipelineLogEntry.Level.INFO, "인식됨: ${result.text}")
                        submit(result.text)
                    }

                    is SpeechResult.Error ->
                        append(PipelineLogEntry.Level.ERROR, "STT 오류: ${result.message}")
                }
            }
        }
        source.start()
        _state.value = PipelineState.Listening()
        append(PipelineLogEntry.Level.INFO, "듣는 중...")
    }

    fun stopListening() {
        speech?.stop()
        listenJob?.cancel()
        listenJob = null
        if (_state.value is PipelineState.Listening) _state.value = PipelineState.Idle
    }

    /**
     * 명령 하나를 처리한다. STT 없이 텍스트만으로도 파이프라인 전체를 돌릴 수 있어서
     * 실기기 없이 개발할 때도 이 경로를 쓴다.
     *
     * 이미 처리 중이면 새 명령을 받지 않는다(false 반환).
     */
    fun submit(utterance: String): Boolean {
        val command = utterance.trim()
        if (command.isEmpty()) return false
        if (isBusy) {
            append(PipelineLogEntry.Level.WARN, "처리 중이라 \"$command\" 를 받지 않았다")
            return false
        }

        val run = Run(newRunId())
        activeRun = run
        emit(run) { id, seq -> PipelineEvent.RunStarted(id, seq, command) }

        runJob = scope.launch {
            try {
                runCommand(command, run)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("예상 못한 오류: ${e.message ?: e::class.java.simpleName}", run)
            }
        }
        return true
    }

    /** 진행 중인 명령을 중단한다. 상태는 곧바로 Idle 로 돌아간다. */
    fun cancel() {
        val job = runJob
        val wasRunning = job?.isActive == true
        job?.cancel()
        runJob = null
        stopListening()
        append(PipelineLogEntry.Level.WARN, "사용자가 중단했다")
        _state.value = PipelineState.Idle
        // 화면에는 곧바로 "취소됨"을 알리지 않는다. 멈춘 것이 확인된 뒤에야 알린다.
        if (wasRunning) activeRun?.let { beginCancelConfirmation(job, it) }
    }

    /**
     * 진행 중인 명령의 중단을 요청한다. [cancel] 과 달리 상태를 바로 Idle 로 돌리지 않는다.
     *
     * 하는 일은 명령 처리를 취소하고 [PipelineEvent.CancelRequested] 를 내보내는 것까지다.
     * [PipelineEvent.Cancelled] 는 명령 처리가 끝나고 실행모듈도 멈춘 것을 확인한 뒤에만 나간다.
     * LLM 추론 중이면 추론이 돌아올 때까지 기다렸다가 결과를 버리고 아무것도 실행하지 않은 채 끝낸다.
     */
    fun requestCancel(runId: String? = null) {
        val job = runJob ?: return
        if (!job.isActive) return
        val run = activeRun ?: return
        // 화면이 누른 중단이 지난 명령의 것이면(그 사이 새 명령이 시작됐다) 새 명령을 멈추지 않는다.
        if (runId != null && runId != run.id) return
        append(PipelineLogEntry.Level.WARN, "중단을 요청했다. 멈췄는지 확인한다")
        job.cancel()
        beginCancelConfirmation(job, run)
    }

    private fun beginCancelConfirmation(job: Job?, run: Run) {
        if (run.cancelRequested) return
        run.cancelRequested = true
        emit(run, force = true) { id, seq -> PipelineEvent.CancelRequested(id, seq) }
        scope.launch { confirmCancel(job, run) }
    }

    /** 명령 처리가 끝나고 실행모듈이 멈췄는지 확인해서 [PipelineEvent.Cancelled] 나 실패를 알린다. */
    private suspend fun confirmCancel(job: Job?, run: Run) {
        val finished = withTimeoutOrNull(config.cancelJoinTimeoutMs) { job?.join(); true } ?: false
        val stopped = finished && withTimeoutOrNull(config.cancelConfirmTimeoutMs) {
            while (!executor.isIdle()) delay(CANCEL_POLL_MS)
            true
        } == true

        if (!stopped) {
            append(PipelineLogEntry.Level.ERROR, "중단을 확인하지 못했다")
            emit(run, force = true) { id, seq -> PipelineEvent.Failed(id, seq, "중단을 확인하지 못했어요") }
            return
        }
        append(PipelineLogEntry.Level.INFO, "중단이 확인됐다")
        emit(run, force = true) { id, seq -> PipelineEvent.Cancelled(id, seq, run.completedStepIds.toSet()) }
        if (activeRun === run) _state.value = PipelineState.Idle
    }

    fun clearLog() {
        _log.value = emptyList()
        _lastPlanJson.value = null
    }

    // ------------------------------------------------------------ 본체 루프

    private suspend fun runCommand(utterance: String, run: Run) {
        append(PipelineLogEntry.Level.INFO, "명령: $utterance")

        if (!executor.isReady()) {
            _state.value = PipelineState.NeedsSetup("접근성 서비스가 꺼져 있다")
            append(PipelineLogEntry.Level.ERROR, "접근성 서비스가 꺼져 있어 실행할 수 없다")
            emit(run) { id, seq -> PipelineEvent.Failed(id, seq, "접근성 서비스가 꺼져 있어요") }
            return
        }

        // 새 명령은 이전 대화를 이어받지 않겠다는 뜻으로 INITIAL 을 쓴다.
        // 엔진이 실제로 히스토리를 비울 수 있는지는 어댑터 사정이다(PlanRequest 주석 참고).
        var plan = requestPlan(
            PlanRequest(utterance = utterance, mode = PlanRequest.Mode.INITIAL),
            replanCount = 0,
            run = run
        ) ?: return
        // 추론 중에 중단됐다면 받은 계획을 버리고 아무것도 실행하지 않는다.
        currentCoroutineContext().ensureActive()
        announcePlan(run, plan)

        val history = mutableListOf<ExecutedStep>()
        val screenVisits = mutableMapOf<String, Int>()
        var replans = 0
        var executedTotal = 0
        var stepIndex = 0
        // 이번 명령에서 성공한 마지막 검색어. 재생 전에 이 검색 결과 화면인지 확인하는 데 쓴다.
        var lastSearchQuery: String? = null

        while (true) {
            if (stepIndex >= plan.steps.size) {
                finish("계획의 모든 단계를 끝냈다", run)
                return
            }
            if (executedTotal >= config.maxSteps) {
                fail("step 을 ${config.maxSteps}개 넘게 실행해 안전장치가 걸렸다", run)
                return
            }

            val step = plan.steps[stepIndex]
            val stepId = stepIdOf(run, stepIndex)
            _state.value = PipelineState.Executing(step, stepIndex + 1, plan.steps.size)
            append(PipelineLogEntry.Level.STEP, step.describe())
            emit(run) { id, seq -> PipelineEvent.StepChanged(id, seq, stepId, StepStatus.InProgress) }

            val outcome = performAndVerify(step, plan.app, lastSearchQuery)
            executedTotal++
            history += ExecutedStep(step, outcome)

            when (outcome) {
                is StepOutcome.Success -> {
                    if (step.action == ActionType.SEARCH) lastSearchQuery = step.target
                    run.completedStepIds += stepId
                    run.lastEvidence = outcome.evidence
                    emit(run) { id, seq -> PipelineEvent.StepChanged(id, seq, stepId, StepStatus.Completed) }
                    stepIndex++
                    continue
                }

                is StepOutcome.NeedsUser -> {
                    handOff(outcome.reason, outcome.screen, run)
                    return
                }

                is StepOutcome.Mismatch,
                is StepOutcome.Timeout,
                is StepOutcome.ExecutorError -> {
                    val failure = when (outcome) {
                        is StepOutcome.Mismatch -> outcome.reason
                        is StepOutcome.Timeout -> "화면이 시간 안에 안정되지 않았다"
                        is StepOutcome.ExecutorError -> outcome.message
                        else -> ""
                    }
                    emit(run) { id, seq -> PipelineEvent.StepChanged(id, seq, stepId, StepStatus.Failed, failure) }

                    if (outcome is StepOutcome.ExecutorError && outcome.unrecoverable) {
                        fail(outcome.message, run)
                        return
                    }

                    val current = outcome.screen
                    val reason = history.last().toPromptLine()
                    append(PipelineLogEntry.Level.WARN, "예상과 다름 -> 재분석: $reason")

                    replans++
                    if (replans > config.maxReplans) {
                        fail("재분석을 ${config.maxReplans}번 했는데도 풀리지 않아 멈춘다", run)
                        return
                    }

                    // 같은 화면을 맴돌면 재분석해도 소용없다.
                    val signature = current.signature()
                    val visits = (screenVisits[signature] ?: 0) + 1
                    screenVisits[signature] = visits
                    if (visits > config.sameScreenReplanLimit) {
                        fail("같은 화면에서 ${visits}번 막혀 더 진행하지 않는다", run)
                        return
                    }

                    val replanCount = replans
                    emit(run) { id, seq -> PipelineEvent.Replanning(id, seq, replanCount, failure) }
                    val next = requestPlan(
                        PlanRequest(
                            utterance = utterance,
                            mode = PlanRequest.Mode.REPLAN,
                            screen = current,
                            history = history.toList(),
                            reason = reason
                        ),
                        replanCount = replans,
                        run = run
                    ) ?: return
                    currentCoroutineContext().ensureActive()
                    announcePlan(run, next)

                    plan = next
                    stepIndex = 0
                    continue
                }
            }
        }
    }

    /**
     * step 하나를 실행하고, 화면이 안정될 때까지 기다린 뒤 기대와 대조한다.
     *
     * 화면이 계속 움직이더라도(애니메이션·광고 등) 기대 조건을 이미 만족했으면 성공으로 본다.
     * 그렇지 않을 때만 시간 초과로 처리한다.
     */
    private suspend fun performAndVerify(step: ActionStep, appName: String, searchQuery: String?): StepOutcome {
        val before = screenObserver.capture() ?: ScreenSnapshot.EMPTY

        // 재생은 이번 검색 결과 화면에서만 한다. 옛 결과 화면의 세 번째 영상을 재생하지 않도록, 검색어가
        // 화면에 보일 때까지만 짧게 기다리고 끝내 안 보이면 누르지 않고 재분석으로 보낸다.
        if (step.action == ActionType.PLAY_FIRST && !searchQuery.isNullOrBlank() &&
            !awaitQueryVisible(searchQuery)
        ) {
            return StepOutcome.Mismatch(screenObserver.capture() ?: before, "이번 검색 결과 화면이 아니라 재생하지 않았다")
        }
        // 실행모듈이 직접 확인한 결과(메시지 전송 확인 등). 화면 검증의 증거와 함께 성공의 근거가 된다.
        var executorEvidence: String? = null

        // WAIT 은 실행모듈에 넘기지 않고 여기서 직접 기다린다. 실행모듈의 WaitDuration 과 같은
        // 규칙(정수는 초, ms/s 단위, 최대 30초)으로 읽는다. 오케스트레이터가 기다리는 이유는
        // 기다리는 동안 화면을 보고 있는 쪽이 여기라서이고, 코루틴 delay 라 가상 시간 테스트가
        // 된다. 다 기다린 뒤에는 아래 공통 경로로 화면이 잠잠해지기만 기다린다.
        if (step.action == ActionType.WAIT) {
            val waitMs = try {
                WaitDuration.millis(step.value ?: step.target.orEmpty())
            } catch (e: IllegalArgumentException) {
                return StepOutcome.Mismatch(before, "대기 시간을 읽지 못했다: ${e.message}")
            }
            delay(waitMs)
        } else {
            val command = when (val mapped = toCommand(step, appName)) {
                is CommandMapping.Ready -> mapped.command
                is CommandMapping.Rejected -> return mapped.outcome(before)
            }

            when (val result = withContext(worker) { executor.execute(command) }) {
                is ExecutionResult.Performed -> executorEvidence = result.evidence

                is ExecutionResult.TargetNotFound ->
                    return StepOutcome.ExecutorError(
                        screenObserver.capture() ?: before,
                        "대상을 찾지 못했다(${result.description})"
                    )

                is ExecutionResult.NotReady ->
                    return StepOutcome.ExecutorError(before, result.message)

                is ExecutionResult.Failed ->
                    return StepOutcome.ExecutorError(
                        screenObserver.capture() ?: before,
                        result.message,
                        // 메시지는 보냈을 수도 있고, 받는 사람을 못 정한 것은 재분석으로 풀리지 않는다.
                        // 모델이 다른 조작을 지어내거나 같은 메시지를 또 보내지 않도록 바로 포기한다.
                        unrecoverable = step.action == ActionType.SEND_MESSAGE
                    )

                is ExecutionResult.Unsupported ->
                    return StepOutcome.Mismatch(
                        before,
                        "실행모듈이 ${result.action.label} 을(를) 아직 지원하지 않는다"
                    )
            }
        }

        _state.value = PipelineState.Verifying(step)

        val settle = awaitSettle(step.timeoutMs, before, step.expect)
        val after = settle.snapshot

        return when (val verdict = ScreenVerifier.verify(step.expect, before, after)) {
            is ScreenVerifier.Verdict.Match -> StepOutcome.Success(
                after,
                evidence = listOfNotNull(executorEvidence, verdict.evidence).joinToString(", ").ifEmpty { null }
            )

            is ScreenVerifier.Verdict.Mismatch -> when {
                verdict.situation?.requiresHuman == true ->
                    StepOutcome.NeedsUser(after, verdict.reason)

                !settle.settled ->
                    StepOutcome.Timeout(after, step.timeoutMs)

                else ->
                    StepOutcome.Mismatch(after, verdict.reason)
            }
        }
    }

    /**
     * 계획의 step 을 실행모듈이 이해하는 명령으로 바꾼다.
     *
     * `open_app` 은 앱 이름을 패키지명으로 치환한다. 패키지명은 LLM 이 알 수 없는
     * 정보라 이쪽에서 채워야 한다.
     */
    private fun toCommand(step: ActionStep, appName: String): CommandMapping =
        when (step.action) {
            ActionType.OPEN_APP -> {
                val wanted = step.target ?: appName
                val packageName = appResolver.resolvePackage(wanted)
                if (packageName == null) {
                    CommandMapping.Rejected { screen ->
                        // 설치되지 않았거나 이름을 알 수 없는 앱은 화면을 다시 봐도 열 수 없다.
                        // 재분석하면 엉뚱한 버튼을 누르며 시간만 쓰므로 바로 포기한다.
                        StepOutcome.ExecutorError(
                            screen,
                            "\"$wanted\" 앱을 기기에서 찾지 못했다",
                            unrecoverable = true
                        )
                    }
                } else {
                    CommandMapping.Ready(ActionExecutor.Command.LaunchApp(packageName))
                }
            }

            ActionType.TAP -> {
                val label = step.target
                if (label.isNullOrBlank()) {
                    CommandMapping.Rejected { screen ->
                        StepOutcome.Mismatch(screen, "누를 대상이 지정되지 않았다")
                    }
                } else {
                    CommandMapping.Ready(ActionExecutor.Command.Tap(label))
                }
            }

            ActionType.INPUT_TEXT ->
                CommandMapping.Ready(
                    ActionExecutor.Command.InputText(
                        fieldLabel = step.target,
                        text = step.value.orEmpty()
                    )
                )

            // 검색어는 value 가 아니라 target 에 온다 (실행모듈이 그렇게 받는다).
            ActionType.SEARCH -> {
                val query = step.target
                if (query.isNullOrBlank()) {
                    CommandMapping.Rejected { screen ->
                        StepOutcome.Mismatch(screen, "검색어가 비어 있다")
                    }
                } else {
                    CommandMapping.Ready(ActionExecutor.Command.Search(appName, query))
                }
            }

            ActionType.PLAY_FIRST -> {
                // 순번은 value 로 오고, 모델이 target("두 번째 동영상")에 적은 것도 읽는다. 첫 번째는 그대로 PlayFirst.
                val ordinal = Ordinal.find(step.value) ?: Ordinal.inUtterance(step.target.orEmpty()) ?: 1
                CommandMapping.Ready(
                    if (ordinal > 1) ActionExecutor.Command.PlayNth(ordinal) else ActionExecutor.Command.PlayFirst
                )
            }

            ActionType.NEXT_VIDEO ->
                CommandMapping.Ready(ActionExecutor.Command.NextVideo(step.value))

            ActionType.SCROLL ->
                CommandMapping.Ready(
                    ActionExecutor.Command.Scroll(
                        // 새 스키마는 방향을 target 에 up/down/forward/backward 로 준다.
                        // 앞 판정(target 의 "위", value 의 up)도 그대로 둔다.
                        if (step.target?.trim()?.lowercase() in UP_TARGETS ||
                            step.target?.contains("위") == true ||
                            step.value?.contains("up", ignoreCase = true) == true
                        ) {
                            ActionExecutor.Command.Direction.UP
                        } else {
                            ActionExecutor.Command.Direction.DOWN
                        }
                    )
                )

            ActionType.SEND_MESSAGE -> {
                val recipient = step.target
                val text = step.value
                if (recipient.isNullOrBlank() || text.isNullOrBlank()) {
                    CommandMapping.Rejected { screen ->
                        StepOutcome.ExecutorError(
                            screen, "받는 사람이나 보낼 내용이 비어 있다", unrecoverable = true
                        )
                    }
                } else {
                    CommandMapping.Ready(ActionExecutor.Command.SendMessage(appName, recipient, text))
                }
            }

            ActionType.WAIT ->
                CommandMapping.Rejected { screen ->
                    StepOutcome.Mismatch(screen, "대기는 실행모듈에 넘기지 않는다")
                }

            ActionType.UNKNOWN ->
                CommandMapping.Rejected { screen ->
                    StepOutcome.Mismatch(screen, "실행할 수 없는 action 이라 다시 판단한다")
                }
        }

    private sealed interface CommandMapping {
        data class Ready(val command: ActionExecutor.Command) : CommandMapping

        /** 명령으로 바꿀 수 없을 때. 화면을 받아 결과를 만든다. */
        class Rejected(val outcome: (ScreenSnapshot) -> StepOutcome) : CommandMapping
    }

    /** 검색어가 화면에 보일 때까지 짧게 기다린다(상한 [PLAY_QUERY_WAIT_MS]). 끝내 안 보이면 false. */
    private suspend fun awaitQueryVisible(query: String): Boolean =
        withTimeoutOrNull(PLAY_QUERY_WAIT_MS) {
            while (!queryOnScreen(query)) delay(PLAY_QUERY_POLL_MS)
            true
        } ?: false

    private fun queryOnScreen(query: String): Boolean {
        val snapshot = screenObserver.capture() ?: return false
        return TextMatch.queryVisible(snapshot.texts + snapshot.clickableLabels, query)
    }

    /**
     * 화면 변화가 [PipelineConfig.quietPeriodMs] 동안 멈출 때까지 기다린다.
     *
     * TYPE_WINDOW_CONTENT_CHANGED 는 한 화면에서도 수십 번씩 쏟아지므로,
     * 마지막 이벤트만 남기는 conflated 채널로 받아 "조용해졌는가"만 본다.
     */
    private suspend fun awaitSettle(timeoutMs: Long, seed: ScreenSnapshot, expect: Expectation): SettleResult =
        coroutineScope {
            val inbox = Channel<ScreenChange>(Channel.CONFLATED)
            val collectJob = launch { screenObserver.changes.collect { inbox.trySend(it) } }

            try {
                var last = seed
                var early = false

                // 시간은 전부 코루틴 시계로 잰다. 벽시계를 섞으면 가상 시간 테스트에서
                // 타임아웃 경로를 검증할 수 없게 된다.
                val settled = withTimeoutOrNull(timeoutMs) {
                    var quiet = false
                    while (!quiet) {
                        val change = withTimeoutOrNull(config.quietPeriodMs) { inbox.receive() }
                        if (change == null) {
                            // 기다리는 동안 아무 일도 없었다 = 화면이 안정됐다.
                            quiet = true
                        } else {
                            last = change.snapshot
                            // 기대 조건이 이미 맞으면 화면이 더 조용해지기를 기다리지 않는다.
                            // 기대가 없는 step 은 화면을 확인한 것이 없으므로 여기서 끝내지 않는다.
                            if (!expect.isEmpty &&
                                ScreenVerifier.verify(expect, seed, last) is ScreenVerifier.Verdict.Match
                            ) {
                                early = true
                                return@withTimeoutOrNull true
                            }
                        }
                    }
                    true
                } ?: false

                if (early) SettleResult(last, settled = true)
                else SettleResult(screenObserver.capture() ?: last, settled)
            } finally {
                collectJob.cancel()
                inbox.close()
            }
        }

    /**
     * LLM 에 계획을 요청한다. JSON 이 깨져서 오면 형식만 고쳐 달라고 정해진 횟수만큼 다시 묻는다.
     * 끝내 못 받으면 null 을 돌려주고 상태를 실패로 바꾼다.
     */
    private suspend fun requestPlan(request: PlanRequest, replanCount: Int, run: Run): ActionPlan? {
        var attempt = request

        repeat(config.maxJsonRepairs + 1) {
            _state.value = PipelineState.Planning(request.utterance, replanCount)
            if (attempt.mode == PlanRequest.Mode.REPLAN) {
                append(PipelineLogEntry.Level.INFO, "화면을 다시 분석해 달라고 요청한다")
            }

            when (val result = withContext(worker) { planner.generate(attempt) }) {
                is PlanResult.Success -> {
                    val masked = PipelineRedaction.maskPlanJson(result.raw)
                    _lastPlanJson.value = masked
                    append(PipelineLogEntry.Level.JSON, masked)
                    result.warnings.forEach { append(PipelineLogEntry.Level.WARN, "파서 경고: $it") }

                    // 뒤의 동작이 앱을 함께 여는 "앱 열기"는 뺀다. LLM 이 주지 않는 기대치는 그다음에 채운다.
                    val pruned = PlanStepPruner.prune(result.plan)
                    val enriched = inferencer.enrich(pruned)
                    append(
                        PipelineLogEntry.Level.INFO,
                        "계획 ${enriched.steps.size}단계를 받았다 (대상 앱: ${enriched.app})"
                    )
                    return enriched
                }

                is PlanResult.Malformed -> {
                    append(PipelineLogEntry.Level.WARN, "JSON 을 읽지 못했다: ${result.reason}")
                    attempt = attempt.copy(
                        mode = PlanRequest.Mode.REPAIR_JSON,
                        previousRaw = result.raw,
                        parseError = result.reason
                    )
                }

                is PlanResult.EngineError -> {
                    fail("LLM 오류: ${result.message}", run)
                    return null
                }

                // 가드의 거절. 다시 묻거나 재분석하지 않고 이유 그대로 끝낸다.
                is PlanResult.Refused -> {
                    fail(result.message, run, speakReason = true)
                    return null
                }
            }
        }

        fail("LLM 이 올바른 형식의 계획을 주지 않았다", run)
        return null
    }

    // ------------------------------------------------------------ 상태 전이

    private fun finish(message: String, run: Run) {
        append(PipelineLogEntry.Level.INFO, "완료: $message")
        _state.value = PipelineState.Finished(message)
        val evidence = run.lastEvidence
        emit(run) { id, seq -> PipelineEvent.ResultVerified(id, seq, evidence) }
    }

    private fun fail(reason: String, run: Run, speakReason: Boolean = false) {
        append(PipelineLogEntry.Level.ERROR, reason)
        _state.value = PipelineState.Failed(reason, speakReason)
        emit(run) { id, seq -> PipelineEvent.Failed(id, seq, reason) }
    }

    private fun handOff(reason: String, snapshot: ScreenSnapshot, run: Run) {
        append(PipelineLogEntry.Level.WARN, "사람이 처리해야 한다: $reason")
        _state.value = PipelineState.NeedsUser(reason, snapshot)
        emit(run) { id, seq -> PipelineEvent.NeedsUser(id, seq, reason) }
    }

    // ------------------------------------------------------------ 사건 흐름

    /**
     * 명령 하나의 사건 기록 상태.
     * 사건마다 [id] 를 붙이고, 중단을 요청한 뒤에는 중단 관련이 아닌 사건을 내보내지 않는다.
     */
    private class Run(val id: String) {
        @Volatile
        var cancelRequested = false

        /** 지금까지 받은 계획의 수. stepId 의 "p{번호}" 가 된다. */
        var planNumber = 0

        /** 끝난 단계. 재분석으로 계획이 바뀌어도 남는다. */
        val completedStepIds = mutableSetOf<String>()

        /** 가장 최근에 성공한 단계의 증거. 마지막 단계가 끝나면 결과의 증거가 된다. */
        var lastEvidence: String? = null
    }

    private fun stepIdOf(run: Run, index: Int) = "p${run.planNumber}-s$index"

    /** 받은 계획을 사건으로 알린다. 보낼 내용(value)은 담지 않는다. */
    private fun announcePlan(run: Run, plan: ActionPlan) {
        run.planNumber++
        val steps = plan.steps.mapIndexed { index, step ->
            PlannedStep(stepIdOf(run, index), step.action, step.target, plan.app)
        }
        val number = run.planNumber
        emit(run) { id, seq -> PipelineEvent.PlanValidated(id, seq, number, steps) }
    }

    /**
     * 사건 하나를 내보낸다. 번호를 매기는 것과 내보내는 것을 한 덩어리로 묶어서, 여러 스레드에서
     * 불러도 번호 순서대로 나간다.
     *
     * @param force 중단을 요청한 뒤에도 내보낼 사건(중단 요청·확인·실패)이면 true.
     */
    @Synchronized
    private fun emit(run: Run, force: Boolean = false, make: (runId: String, sequence: Long) -> PipelineEvent) {
        if (run.cancelRequested && !force) return
        _events.tryEmit(make(run.id, SEQUENCE.incrementAndGet()))
    }

    private fun newRunId(): String = UUID.randomUUID().toString().take(8)

    private fun append(level: PipelineLogEntry.Level, message: String) {
        _log.value = (_log.value + PipelineLogEntry(level, message)).takeLast(MAX_LOG_ENTRIES)
    }

    private data class SettleResult(val snapshot: ScreenSnapshot, val settled: Boolean)

    private companion object {
        const val MAX_LOG_ENTRIES = 200

        /** 사건 흐름의 여유 칸. 한 명령이 내는 사건은 수십 개 안쪽이라 구독자가 잠깐 늦어도 넘치지 않는다. */
        const val EVENT_BUFFER = 256

        /** 중단 뒤 실행모듈이 멈췄는지 다시 보는 간격. */
        const val CANCEL_POLL_MS = 100L

        /** 재생 전에 검색 결과 화면을 기다리는 상한과 간격. 검색 단계가 이미 검색어를 확인했으면 바로 통과한다. */
        const val PLAY_QUERY_WAIT_MS = 2_000L
        const val PLAY_QUERY_POLL_MS = 250L

        /**
         * 사건 번호. 오케스트레이터를 새로 만들어도 이어서 커진다(개발 화면이 자주 갈아 끼운다).
         * 화면은 "이미 본 번호 이하"를 버리므로 새 오케스트레이터의 사건도 더 커야 받아 준다.
         */
        val SEQUENCE = AtomicLong()

        /** scroll 의 target 중 위쪽(되돌아가는) 방향. 나머지(down, forward)는 아래쪽이다. */
        val UP_TARGETS = setOf("up", "backward")
    }
}
