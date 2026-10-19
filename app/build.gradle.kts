plugins {
    alias(libs.plugins.android.application)
}

/** 빌드할 때 만들어 넣는 에셋이 모이는 곳. 아래 copySystemPrompt 참고. */
val generatedAssetsDir = layout.buildDirectory.dir("generated/aemxAssets")

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
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // 저장소 경로에 한글이 있으면(예: "AI 대회") Windows 기본 인코딩(MS949)으로는
            // 테스트 클래스 경로를 읽지 못해 ClassNotFoundException 이 난다(이슈 #7).
            it.jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
        }
    }

    packaging {
        jniLibs {
            // 설치할 때 .so 를 실제 파일로 풀어 놓는다. llama.cpp 가 ggml 백엔드를 경로로
            // dlopen 하기 때문에, APK 안에 압축된 채로 두면 "no backends are loaded" 로
            // 모델 로딩이 실패한다. 설치 용량이 늘어나는 대신 이 동작이 꼭 필요하다.
            useLegacyPackaging = true
        }
    }

    // 시스템 프롬프트는 LLM/system_prompt.txt 하나만 진짜로 친다. 빌드할 때 assets 로
    // 복사해서 앱이 읽게 한다 — 예전엔 코드 상수로 복사해 두는 바람에 프롬프트를 고치면
    // 두 곳을 같이 고쳐야 했고, 안 고치면 조용히 어긋났다.
    //
    // AGP 9 는 소스 경로로 Provider 를 거부해서(생성물인지 원본인지 구분할 수 없다는 이유)
    // 경로를 File 로 풀어서 넘기고, 복사 시점은 아래 preBuild 의존으로 직접 보장한다.
    sourceSets["main"].assets.srcDir(generatedAssetsDir.get().asFile)
}

val copySystemPrompt by tasks.registering(Copy::class) {
    from(rootProject.file("LLM/system_prompt.txt"))
    into(generatedAssetsDir)
}

tasks.named("preBuild") {
    dependsOn(copySystemPrompt)
}

dependencies {
    implementation(project(":llm-engine"))
    implementation(project(":stt-engine"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // 파이프라인 상태머신은 코루틴/Flow 위에서 돈다.
    implementation(libs.kotlinx.coroutines.android)
    // lifecycleScope 로 화면 수명에 맞춰 파이프라인을 돌린다.
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    // org.json 은 안드로이드 프레임워크에 들어 있지만 JVM 단위테스트에서는 빈 스텁이다.
    // 실제 구현을 테스트 클래스패스에 올려 파서를 로컬에서 검증한다.
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
