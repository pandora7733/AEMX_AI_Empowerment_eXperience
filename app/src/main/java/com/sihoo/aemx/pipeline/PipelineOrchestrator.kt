package com.sihoo.aemx.pipeline

import com.sihoo.aemx.accessibility.WaitDuration
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import com.sihoo.aemx.pipeline.model.ExecutedStep
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.model.StepOutcome
import com.sihoo.aemx.pipeline.port.ActionExecutor
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    private var runJob: Job? = null
    private var listenJob: Job? = null

    val isBusy: Boolean
        get() = runJob?.isActive == true

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

        runJob = scope.launch {
            try {
                runCommand(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("예상 못한 오류: ${e.message ?: e::class.java.simpleName}")
            }
        }
        return true
    }

    /** 진행 중인 명령을 중단한다. */
    fun cancel() {
        runJob?.cancel()
        runJob = null
        stopListening()
        append(PipelineLogEntry.Level.WARN, "사용자가 중단했다")
        _state.value = PipelineState.Idle
    }

    fun clearLog() {
        _log.value = emptyList()
        _lastPlanJson.value = null
    }

    // ------------------------------------------------------------ 본체 루프

    private suspend fun runCommand(utterance: String) {
        append(PipelineLogEntry.Level.INFO, "명령: $utterance")

        if (!executor.isReady()) {
            _state.value = PipelineState.NeedsSetup("접근성 서비스가 꺼져 있다")
            append(PipelineLogEntry.Level.ERROR, "접근성 서비스가 꺼져 있어 실행할 수 없다")
            return
        }

        // 새 명령은 이전 대화를 이어받지 않겠다는 뜻으로 INITIAL 을 쓴다.
        // 엔진이 실제로 히스토리를 비울 수 있는지는 어댑터 사정이다(PlanRequest 주석 참고).
        var plan = requestPlan(
            PlanRequest(utterance = utterance, mode = PlanRequest.Mode.INITIAL),
            replanCount = 0
        ) ?: return

        val history = mutableListOf<ExecutedStep>()
        val screenVisits = mutableMapOf<String, Int>()
        var replans = 0
        var executedTotal = 0
        var stepIndex = 0

        while (true) {
            if (stepIndex >= plan.steps.size) {
                finish("계획의 모든 단계를 끝냈다")
                return
            }
            if (executedTotal >= config.maxSteps) {
                fail("step 을 ${config.maxSteps}개 넘게 실행해 안전장치가 걸렸다")
                return
            }

            val step = plan.steps[stepIndex]
            _state.value = PipelineState.Executing(step, stepIndex + 1, plan.steps.size)
            append(PipelineLogEntry.Level.STEP, step.describe())

            val outcome = performAndVerify(step, plan.app)
            executedTotal++
            history += ExecutedStep(step, outcome)

            when (outcome) {
                is StepOutcome.Success -> {
                    stepIndex++
                    continue
                }

                is StepOutcome.NeedsUser -> {
                    handOff(outcome.reason, outcome.screen)
                    return
                }

                is StepOutcome.Mismatch,
                is StepOutcome.Timeout,
                is StepOutcome.ExecutorError -> {
                    if (outcome is StepOutcome.ExecutorError && outcome.unrecoverable) {
                        fail(outcome.message)
                        return
                    }

                    val current = outcome.screen
                    val reason = history.last().toPromptLine()
                    append(PipelineLogEntry.Level.WARN, "예상과 다름 -> 재분석: $reason")

                    replans++
                    if (replans > config.maxReplans) {
                        fail("재분석을 ${config.maxReplans}번 했는데도 풀리지 않아 멈춘다")
                        return
                    }

                    // 같은 화면을 맴돌면 재분석해도 소용없다.
                    val signature = current.signature()
                    val visits = (screenVisits[signature] ?: 0) + 1
                    screenVisits[signature] = visits
                    if (visits > config.sameScreenReplanLimit) {
                        fail("같은 화면에서 ${visits}번 막혀 더 진행하지 않는다")
                        return
                    }

                    val next = requestPlan(
                        PlanRequest(
                            utterance = utterance,
                            mode = PlanRequest.Mode.REPLAN,
                            screen = current,
                            history = history.toList(),
                            reason = reason
                        ),
                        replanCount = replans
                    ) ?: return

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
    private suspend fun performAndVerify(step: ActionStep, appName: String): StepOutcome {
        val before = screenObserver.capture() ?: ScreenSnapshot.EMPTY

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
                is ExecutionResult.Performed -> Unit

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

        val settle = awaitSettle(step.timeoutMs, before)
        val after = settle.snapshot

        return when (val verdict = ScreenVerifier.verify(step.expect, before, after)) {
            is ScreenVerifier.Verdict.Match -> StepOutcome.Success(after)

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

            ActionType.PLAY_FIRST ->
                CommandMapping.Ready(ActionExecutor.Command.PlayFirst)

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

    /**
     * 화면 변화가 [PipelineConfig.quietPeriodMs] 동안 멈출 때까지 기다린다.
     *
     * TYPE_WINDOW_CONTENT_CHANGED 는 한 화면에서도 수십 번씩 쏟아지므로,
     * 마지막 이벤트만 남기는 conflated 채널로 받아 "조용해졌는가"만 본다.
     */
    private suspend fun awaitSettle(timeoutMs: Long, seed: ScreenSnapshot): SettleResult =
        coroutineScope {
            val inbox = Channel<ScreenChange>(Channel.CONFLATED)
            val collectJob = launch { screenObserver.changes.collect { inbox.trySend(it) } }

            try {
                var last = seed

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
                        }
                    }
                    true
                } ?: false

                SettleResult(screenObserver.capture() ?: last, settled)
            } finally {
                collectJob.cancel()
                inbox.close()
            }
        }

    /**
     * LLM 에 계획을 요청한다. JSON 이 깨져서 오면 형식만 고쳐 달라고 정해진 횟수만큼 다시 묻는다.
     * 끝내 못 받으면 null 을 돌려주고 상태를 실패로 바꾼다.
     */
    private suspend fun requestPlan(request: PlanRequest, replanCount: Int): ActionPlan? {
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

                    // LLM 이 주지 않는 기대치를 여기서 채운다.
                    val enriched = inferencer.enrich(result.plan)
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
                    fail("LLM 오류: ${result.message}")
                    return null
                }
            }
        }

        fail("LLM 이 올바른 형식의 계획을 주지 않았다")
        return null
    }

    // ------------------------------------------------------------ 상태 전이

    private fun finish(message: String) {
        append(PipelineLogEntry.Level.INFO, "완료: $message")
        _state.value = PipelineState.Finished(message)
    }

    private fun fail(reason: String) {
        append(PipelineLogEntry.Level.ERROR, reason)
        _state.value = PipelineState.Failed(reason)
    }

    private fun handOff(reason: String, snapshot: ScreenSnapshot) {
        append(PipelineLogEntry.Level.WARN, "사람이 처리해야 한다: $reason")
        _state.value = PipelineState.NeedsUser(reason, snapshot)
    }

    private fun append(level: PipelineLogEntry.Level, message: String) {
        _log.value = (_log.value + PipelineLogEntry(level, message)).takeLast(MAX_LOG_ENTRIES)
    }

    private data class SettleResult(val snapshot: ScreenSnapshot, val settled: Boolean)

    private companion object {
        const val MAX_LOG_ENTRIES = 200

        /** scroll 의 target 중 위쪽(되돌아가는) 방향. 나머지(down, forward)는 아래쪽이다. */
        val UP_TARGETS = setOf("up", "backward")
    }
}
