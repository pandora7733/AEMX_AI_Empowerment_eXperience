package com.sihoo.aemx.pipeline.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class AdbCommandTest {

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `평문 cmd 를 다듬어서 쓴다`() {
        assertEquals("유튜브에서 고양이 영상 틀어줘", AdbCommand.resolve("  유튜브에서 고양이 영상 틀어줘 ", null))
    }

    @Test
    fun `cmd 가 없으면 base64 로 받은 한글을 UTF-8 로 되살린다`() {
        val command = "유튜브에서 고양이 영상 틀어줘"

        assertEquals(command, AdbCommand.resolve(null, b64(command)))
    }

    @Test
    fun `둘 다 있으면 평문 cmd 를 쓴다`() {
        assertEquals("평문", AdbCommand.resolve("평문", b64("base64")))
    }

    @Test
    fun `비었거나 base64 가 깨졌으면 명령이 없다`() {
        assertNull(AdbCommand.resolve(null, null))
        assertNull(AdbCommand.resolve("   ", "  "))
        assertNull(AdbCommand.resolve(null, "!!!이건 base64 가 아니다!!!"))
    }
}
