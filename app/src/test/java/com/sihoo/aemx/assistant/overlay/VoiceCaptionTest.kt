package com.sihoo.aemx.assistant.overlay

import com.sihoo.aemx.assistant.AssistantPhase
import com.sihoo.aemx.assistant.AssistantUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 음성 캡슐의 글자: 듣는 중에는 말한 내용(줄여서), 말이 없으면 "듣고 있어요…", 이해 단계에서는 최종 문장을 한 번만. */
class VoiceCaptionTest {

    @Test fun `듣는 중에는 들은 말을 캡슐 글자에 보인다`() {
        val text = CapsuleTextFactory.from(
            AssistantUiState(phase = AssistantPhase.Recognizing, transcript = "날씨 알려줘"),
            liveCaption = LiveCaption.display("날씨 알려줘"),
        )

        assertEquals("날씨 알려줘", text.title)
        assertEquals(VoiceState.Recognizing, text.voice)
    }

    @Test fun `말이 아직 없으면 듣고 있어요 한 줄이 보인다`() {
        val listening = CapsuleTextFactory.from(AssistantUiState(phase = AssistantPhase.Listening))
        val recognizingEmpty = CapsuleTextFactory.from(AssistantUiState(phase = AssistantPhase.Recognizing), liveCaption = "")

        assertEquals(CapsuleTextFactory.LISTENING, listening.title)
        assertEquals(CapsuleTextFactory.LISTENING, recognizingEmpty.title)
        assertEquals("", listening.subtitle)
    }

    @Test fun `듣는 중에는 보조 줄이 없다(글자 줄 하나로 끝난다)`() {
        val text = CapsuleTextFactory.from(
            AssistantUiState(phase = AssistantPhase.Listening, transcript = "날씨"),
            liveCaption = "날씨",
        )

        assertEquals("", text.subtitle)
    }

    @Test fun `이해 단계에서는 최종 문장을 보조 줄에 한 번 보인다`() {
        val text = CapsuleTextFactory.from(
            AssistantUiState(phase = AssistantPhase.Understanding, transcript = "유튜브에서 고양이 영상 틀어줘"),
        )

        assertEquals(CapsuleTextFactory.UNDERSTANDING, text.title)
        assertEquals("유튜브에서 고양이 영상 틀어줘", text.subtitle)
    }

    @Test fun `긴 최종 문장은 말줄임으로 자르고 줄바꿈은 없앤다`() {
        val long = "유튜브에서 고양이 영상을 틀어 달라고 했는데\n그 영상을 다섯 번째 것으로 바꿔 주세요 아주 길게"
        val line = CapsuleTextFactory.sentenceLine(long)

        assertFalse(line.contains('\n'))
        assertTrue(line.endsWith("…"))
        assertTrue(line.length <= CapsuleTextFactory.SENTENCE_MAX + 1)
    }

    @Test fun `빈 문장이면 보조 줄이 없다`() {
        assertEquals("", CapsuleTextFactory.sentenceLine("   "))
        assertEquals("", CapsuleTextFactory.from(AssistantUiState(phase = AssistantPhase.Understanding)).subtitle)
    }

    @Test fun `글자 나타내기 효과는 꺼져 있다`() {
        assertFalse(OverlayMotion.TYPEWRITER_ON)
    }
}
