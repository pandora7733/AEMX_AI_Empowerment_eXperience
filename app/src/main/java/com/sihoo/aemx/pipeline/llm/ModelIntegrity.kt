package com.sihoo.aemx.pipeline.llm

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 받은 모델 파일이 우리가 정한 그 파일인지 확인한다(이슈 #16).
 *
 * 1.2GB 를 읽어 SHA-256 을 구하는 데 몇 초가 걸려서, 통과한 파일에는 표시 파일을 남기고
 * 이후 실행에서는 크기와 표시만 본다. 표시에는 해시와 파일 수정 시각을 같이 적어서, 파일이
 * 바뀌었는데 표시만 남은 경우에는 다시 계산한다.
 */
internal object ModelIntegrity {

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * [file] 이 기대한 크기와 해시인지. 통과하면 [marker] 를 남긴다.
     *
     * @param onHashing 해시를 새로 계산하기 직전에 불린다(화면에 "확인 중"을 보여 주려는 용도).
     */
    fun verify(
        file: File,
        expectedSize: Long,
        expectedSha256: String,
        marker: File,
        onHashing: () -> Unit = {},
    ): Boolean {
        if (!file.exists() || file.length() != expectedSize) return false
        val stamp = "$expectedSha256:${file.lastModified()}"
        if (marker.exists() && marker.readText().trim() == stamp) return true

        onHashing()
        if (!sha256(file).equals(expectedSha256, ignoreCase = true)) return false
        marker.writeText(stamp)
        return true
    }
}
