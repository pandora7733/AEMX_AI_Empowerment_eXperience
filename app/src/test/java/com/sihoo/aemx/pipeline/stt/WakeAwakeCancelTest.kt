package com.sihoo.aemx.pipeline.stt

import com.sihoo.aemx.pipeline.port.SpeechResult
import com.sihoo.aemx.pipeline.port.SpeechSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 듣는 중 캡슐의 중단이 호출어 창만 닫고 인식기는 살려 두는지. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WakeAwakeCancelTest {

    private class FakeSpeech : SpeechSource {
        val flow = MutableSharedFlow<SpeechResult>(extraBufferCapacity = 8)
        override val results: Flow<SpeechResult> get() = flow
        override fun isReady(): Boolean = true
        override fun start() {}
        override fun stop() {}
    }

    @Test fun `호출어 창을 닫아도 다음 호출은 다시 받는다`() = runBlocking {
        val fake = FakeSpeech()
        val gate = WakeWordSpeechSource(fake, CoroutineScope(Dispatchers.Unconfined), now = { 0L })

        fake.flow.emit(SpeechResult.Final("하이 에이맥스"))
        assertTrue("호출어만 말하면 창이 열린다", gate.awake.value)

        gate.cancelAwake()
        assertFalse("취소하면 창이 닫힌다", gate.awake.value)

        fake.flow.emit(SpeechResult.Final("하이 에이맥스"))
        assertTrue("취소 뒤에도 같은 인식기로 다시 호출을 받는다", gate.awake.value)
    }

    @Test fun `창이 닫혀 있을 때 취소해도 아무 일도 없다`() {
        val gate = WakeWordSpeechSource(FakeSpeech(), CoroutineScope(Dispatchers.Unconfined), now = { 0L })
        gate.cancelAwake()
        assertFalse(gate.awake.value)
    }
}
