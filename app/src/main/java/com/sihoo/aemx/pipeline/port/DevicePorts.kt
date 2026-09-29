package com.sihoo.aemx.pipeline.port

/**
 * 저장된 계정을 꺼내는 경계.
 *
 * LLM 은 `input_credential` 로 "저장된 아이디/비밀번호를 넣어라"라고만 지시하고
 * 실제 값은 절대 만들지 않는다(`LLM/system_prompt.txt`). 그 값을 들고 있는 쪽이 여기다.
 *
 * 앱의 계정 관리 화면과 이어지는 지점이며, 실제 구현은 암호화된 저장소를 읽어야 한다.
 * 파이프라인은 꺼낸 값을 로그에 절대 남기지 않는다.
 */
interface CredentialStore {

    /**
     * 앱 이름("알라딘")으로 저장된 계정을 찾는다.
     * 없으면 null — 이 경우 파이프라인은 사용자에게 계정 등록을 요청하고 멈춘다.
     */
    fun lookup(appName: String): Credential?
}

data class Credential(
    val loginId: String,
    val password: String
)

/**
 * 앱 이름을 패키지명으로 바꾸는 경계.
 *
 * "알라딘" 같은 한글 이름만으로는 앱을 실행할 수 없다.
 * 패키지명을 코드에 박아 두면 앱이 업데이트되거나 다른 기기에서 다를 때 깨지므로,
 * 실제 구현은 기기에 설치된 앱 목록의 라벨을 조회해서 찾는다.
 */
interface AppResolver {

    /** 앱 이름에 해당하는 패키지명. 설치돼 있지 않거나 못 찾으면 null. */
    fun resolvePackage(appName: String): String?
}
