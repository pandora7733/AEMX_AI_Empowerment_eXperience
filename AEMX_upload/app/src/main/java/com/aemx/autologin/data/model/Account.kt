package com.aemx.autologin.data.model

/**
 * 저장된 사이트 계정 한 건을 나타내는 모델.
 * 껍데기 단계이므로 비밀번호는 담지 않고 화면 표시에 필요한 값만 가진다.
 */
data class Account(
    val id: Long,
    val siteName: String,
    val siteUrl: String,
    val loginId: String,
    val category: String,
    val autoLoginEnabled: Boolean,
    val lastUsed: String
)
