// AEMX 루트 빌드 스크립트.
// 플러그인은 여기서 "선언만" 하고(apply false), 실제 적용은 app 모듈에서 한다.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
