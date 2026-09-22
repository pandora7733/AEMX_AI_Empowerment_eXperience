plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sihoo.aemx"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sihoo.aemx"
        // LLM 모듈이 쓰는 llama.cpp .so 들이 API 28 기준이라 24에서 올렸다.
        // 안드로이드 9 미만 기기는 설치할 수 없다.
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // llama.cpp .so 가 arm64-v8a 만 있다. 다른 ABI 를 함께 선언하면 안드로이드가
        // 그쪽을 앱의 ABI 로 골라 arm64 폴더를 통째로 무시하는 일이 생긴다(LLM/README.md 참고).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 시스템 프롬프트는 LLM/system_prompt.txt 하나만 진짜로 친다. 빌드할 때 assets 로
    // 복사해서 앱이 읽게 한다 — 예전엔 코드 상수로 복사해 두는 바람에 프롬프트를 고치면
    // 두 곳을 같이 고쳐야 했고, 안 고치면 조용히 어긋났다.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/aemxAssets"))
}

val copySystemPrompt by tasks.registering(Copy::class) {
    from(rootProject.file("LLM/system_prompt.txt"))
    into(layout.buildDirectory.dir("generated/aemxAssets"))
}

tasks.named("preBuild") {
    dependsOn(copySystemPrompt)
}

dependencies {
    implementation(project(":llm-engine"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // 파이프라인 상태머신은 코루틴/Flow 위에서 돈다.
    implementation(libs.kotlinx.coroutines.android)
    // lifecycleScope 로 화면 수명에 맞춰 파이프라인을 돌린다.
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json 은 안드로이드 프레임워크에 들어 있지만 JVM 단위테스트에서는 빈 스텁이다.
    // 실제 구현을 테스트 클래스패스에 올려 파서를 로컬에서 검증한다.
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}