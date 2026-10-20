package com.sihoo.aemx.pipeline.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** "abc" 의 SHA-256 (FIPS 180-2 시험 값). */
    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun model(text: String = "abc") = tmp.newFile("model.gguf").apply { writeText(text) }

    private fun marker() = tmp.newFile("model.ok").apply { delete() }

    @Test
    fun `알려진 값의 해시를 구한다`() {
        assertEquals(abcSha, ModelIntegrity.sha256(model()))
    }

    @Test
    fun `맞는 파일은 통과하고 표시를 남긴다`() {
        val marker = marker()

        assertTrue(ModelIntegrity.verify(model(), 3, abcSha, marker))
        assertTrue(marker.exists())
    }

    @Test
    fun `표시가 있으면 해시를 다시 계산하지 않는다`() {
        val file = model()
        val marker = marker()
        ModelIntegrity.verify(file, 3, abcSha, marker)

        var hashed = false
        assertTrue(ModelIntegrity.verify(file, 3, abcSha, marker) { hashed = true })
        assertFalse(hashed)
    }

    @Test
    fun `크기나 내용이 다르면 실패하고 표시를 남기지 않는다`() {
        val marker = marker()

        assertFalse(ModelIntegrity.verify(model("abcd"), 3, abcSha, marker))
        assertFalse(ModelIntegrity.verify(tmp.newFile("other.gguf").apply { writeText("xyz") }, 3, abcSha, marker))
        assertFalse(marker.exists())
    }

    @Test
    fun `파일이 바뀌면 남은 표시를 믿지 않는다`() {
        val file = model()
        val marker = marker()
        ModelIntegrity.verify(file, 3, abcSha, marker)

        file.writeText("xyz")
        file.setLastModified(file.lastModified() + 5_000)

        assertFalse(ModelIntegrity.verify(file, 3, abcSha, marker))
    }
}
