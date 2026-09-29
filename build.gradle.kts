// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // 버전은 여기서 한 번만 정한다. 모듈에서 버전과 함께 다시 요청하면 "이미 클래스패스에
    // 있어 호환성을 확인할 수 없다"며 실패한다.
    alias(libs.plugins.android.library) apply false
}