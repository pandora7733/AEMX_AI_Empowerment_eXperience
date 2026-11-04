package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.PipelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 화면 상태([AssistantUiState])를 들고 있는 곳.
 *
 * 사건은 여러 스레드(오케스트레이터의 작업 스레드, 메인)에서 [post] 로 들어오지만 상태를 바꾸는 것은
 * 코루틴 하나뿐이다. 사건을 채널에 줄 세워 한 번에 하나씩 [AssistantReducer] 에 넣으므로, 사건이
 * 겹쳐 들어와도 상태가 뒤섞이지 않는다.
 */
class AssistantStore(scope: CoroutineScope) {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private val inbox = Channel<PipelineEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in inbox) {
                _state.value = AssistantReducer.reduce(_state.value, event)
            }
        }
    }

    /** 사건을 줄 세운다. 어느 스레드에서 불러도 된다. */
    fun post(event: PipelineEvent) {
        inbox.trySend(event)
    }

    private val _cancelRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * 화면이 보낸 중단 의도. 값은 중단하려는 명령의 runId 다. 화면은 의도만 보내고, 실제로 멈추는 것은
     * 명령을 소유한 쪽(지금은 개발 화면의 오케스트레이터)이 이 흐름을 받아 한다. 상태는 소유한 쪽이
     * CancelRequested 를 내보내야 바뀐다 — 의도를 보냈다고 해서 곧바로 "중단하는 중"이 되지는 않는다.
     */
    val cancelRequests: SharedFlow<String> = _cancelRequests.asSharedFlow()

    /** 지금 명령의 중단을 요청한다. 진행 중이 아니거나 이미 요청했으면 아무것도 하지 않는다. */
    fun requestCancellation() {
        val current = _state.value
        if (current.phase == AssistantPhase.Idle || current.phase.isTerminal || current.cancellationRequested) return
        _cancelRequests.tryEmit(current.runId)
    }

    private val _continueRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * 사람이 직접 처리한 뒤 "계속"을 눌렀다는 의도. 값은 멈춰 있던 명령의 runId 다. 다시 이어서 실행하는 것은
     * 명령을 소유한 쪽(개발 화면의 오케스트레이터)이 이 흐름을 받아 한다. 화면은 의도만 보낸다.
     */
    val continueRequests: SharedFlow<String> = _continueRequests.asSharedFlow()

    /** "계속" 의도. 직접 처리 대기(NeedsUser) 중이고 중단 요청이 없을 때만 보낸다. */
    fun requestContinuation() {
        val current = _state.value
        if (current.phase != AssistantPhase.NeedsUser || current.cancellationRequested) return
        _continueRequests.tryEmit(current.runId)
    }

    private val _sendAnswers = MutableSharedFlow<SendDecision>(extraBufferCapacity = 4)

    /** 보내기 확인에 대한 답. 명령을 소유한 쪽이 받아 이어 간다. */
    val sendAnswers: SharedFlow<SendDecision> = _sendAnswers.asSharedFlow()

    /**
     * 확인 화면의 보내기·취소 버튼. 확인 대기 중일 때만 보낸다. 후보가 있으면 고른 이름이 있어야 보낼 수 있다.
     * 보내기 답에는 고른 이름을 함께 싣는다(후보가 없으면 확인 중인 받는 사람).
     */
    fun answerSend(send: Boolean) {
        val current = _state.value
        if (current.phase != AssistantPhase.ConfirmingAction) return
        if (!send) {
            _sendAnswers.tryEmit(SendDecision(send = false, recipient = null))
            return
        }
        val chosen = if (current.confirmChoices.isEmpty()) current.confirmRecipient else current.confirmSelected
        if (chosen.isNullOrEmpty()) return
        _sendAnswers.tryEmit(SendDecision(send = true, recipient = chosen))
    }

    /** 확인 화면에서 후보 이름 하나를 고른다. 확인 대기 중이고 후보 목록에 있는 이름일 때만 바뀐다. */
    fun selectRecipient(name: String) {
        val current = _state.value
        if (current.phase != AssistantPhase.ConfirmingAction) return
        if (name !in current.confirmChoices) return
        _state.value = current.copy(confirmSelected = name)
    }

    companion object {
        /**
         * 앱 전체가 함께 쓰는 하나. 개발 화면이 사건을 넣고, 다음 단계의 오버레이(접근성 서비스)가
         * 이 상태를 읽는다. 둘이 서로를 모르고도 만나게 하려는 것이다.
         */
        val shared: AssistantStore by lazy {
            AssistantStore(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }
    }
}
