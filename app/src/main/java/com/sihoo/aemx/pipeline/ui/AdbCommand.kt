package com.sihoo.aemx.pipeline.ui

import java.util.Base64

/**
 * adb 로 넘어온 명령 문자열을 고른다. 개발 화면 전용 훅이다(릴리스에는 개발 화면이 없다).
 *
 * `--es cmd "..."` 은 PowerShell 에서 한글이 깨질 수 있어서, 같은 내용을 base64(UTF-8) 로 넘기는 `--es cmd_b64` 도 받는다.
 * 둘 다 있으면 평문 cmd 를 쓴다. 비었거나 base64 가 깨졌으면 null 이다. 명령 내용은 로그에 남기지 않는다.
 */
object AdbCommand {

    fun resolve(cmd: String?, cmdB64: String?): String? {
        cmd?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val encoded = cmdB64?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8).trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }
}
