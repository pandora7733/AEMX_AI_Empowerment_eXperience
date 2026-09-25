package com.sihoo.aemx.accessibility

/**
 * 실행 계층과 저장소의 경계. 백그라운드 스레드에서 호출됩니다.
 * 기본 구현은 Android Keystore로 암호화하며 반환값이나 예외에 비밀을 기록하면 안 됩니다.
 */
interface CredentialProvider {
    fun getCredential(app: String, field: CredentialField): CharSequence?
}

enum class CredentialField {
    ID,
    PASSWORD,
}

object UnconfiguredCredentialProvider : CredentialProvider {
    override fun getCredential(app: String, field: CredentialField): CharSequence? = null
}
