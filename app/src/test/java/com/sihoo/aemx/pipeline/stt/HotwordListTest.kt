package com.sihoo.aemx.pipeline.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 고정 핫워드의 모든 글자가 모델 tokens.txt 에 있는지, 점수 표기가 맞는지 확인한다.
 * 없는 글자가 섞이면 인코딩이 실패하므로 여기서 먼저 잡는다.
 */
class HotwordListTest {

    /** 모델 토큰 파일. 테스트는 app 폴더에서 돌므로 저장소의 STT 샘플 폴더를 가리킨다. */
    private val tokens: Set<Char> by lazy {
        val file = File("../STT/SherpaOnnx/app/src/main/assets/sherpa-onnx-streaming-zipformer-korean-2024-06-16/tokens.txt")
        assertTrue("모델 tokens.txt 를 찾을 수 없다: ${file.absolutePath}", file.exists())
        file.readLines().mapNotNull { it.substringBeforeLast(' ').singleOrNull() }.toSet()
    }

    @Test fun `고정 핫워드의 모든 글자가 모델 토큰에 있다`() {
        val missing = HotwordList.FIXED.flatMap { entry ->
            entry.substringBefore(':').filterNot { it.isWhitespace() }.filterNot { it in tokens }.map { it to entry }
        }
        assertEquals("토큰에 없는 글자: $missing", emptyList<Pair<Char, String>>(), missing)
    }

    @Test fun `점수 표기는 단어 뒤 콜론 한 번과 소수로 되어 있다`() {
        for (entry in HotwordList.FIXED) {
            if (!entry.contains(':')) continue
            val score = entry.substringAfter(':').trim().toFloatOrNull()
            assertTrue("점수를 읽을 수 없다: $entry", score != null && score > 0f)
        }
    }

    @Test fun `호출어는 다른 핫워드보다 높은 점수다`() {
        val wake = HotwordList.FIXED.first { it.startsWith("하이에이맥스") }
        assertTrue(wake.endsWith(":3.5"))
        assertTrue(HotwordList.GLOBAL_SCORE < 3.0f)
    }
}
