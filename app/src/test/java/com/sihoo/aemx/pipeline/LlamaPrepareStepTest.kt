package com.sihoo.aemx.pipeline

import com.arm.aichat.InferenceEngine.State
import com.sihoo.aemx.pipeline.llm.LlamaPlanGenerator
import com.sihoo.aemx.pipeline.llm.LlamaPlanGenerator.Companion.EngineStep
import org.junit.Assert.assertEquals
import org.junit.Test

class LlamaPrepareStepTest {

    @Test
    fun `모델과 시스템 프롬프트가 이미 올라 있으면 다시 올리지 않는다`() {
        assertEquals(EngineStep.REUSE, LlamaPlanGenerator.nextStep(State.ModelReady, primed = true))
    }

    @Test
    fun `처음 상태면 그냥 올린다`() {
        assertEquals(EngineStep.LOAD, LlamaPlanGenerator.nextStep(State.Initialized, primed = false))
    }

    @Test
    fun `프롬프트까지 끝났는지 모르는 ModelReady 와 오류 상태는 내렸다가 올린다`() {
        assertEquals(EngineStep.UNLOAD_THEN_LOAD, LlamaPlanGenerator.nextStep(State.ModelReady, primed = false))
        assertEquals(EngineStep.UNLOAD_THEN_LOAD, LlamaPlanGenerator.nextStep(State.Error(RuntimeException()), primed = true))
    }
}
