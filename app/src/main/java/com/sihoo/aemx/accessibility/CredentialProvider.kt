package com.sihoo.aemx.accessibility

/**
 * Credential 저장 방식을 정하지 않은 상태에서 실행 계층과 보안 저장소를 분리하는 경계입니다.
 * 구현체는 평문을 로그에 남기면 안 되며 Android Keystore/Autofill 결정 후 연결합니다.
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
