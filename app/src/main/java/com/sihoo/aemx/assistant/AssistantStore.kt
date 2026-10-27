package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.PipelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
