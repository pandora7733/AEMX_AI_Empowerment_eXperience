package com.sihoo.aemx.pipeline.port

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
