plugins {
    alias(libs.plugins.android.library)
}

/**
 * LLM(의도분석) 엔진 모듈.
 *
 * 소스는 `LLM/LlamaDemo/lib` 에 있는 것을 그대로 쓴다. 복사해 오지 않고 경로만 가리키는
 * 이유는, 그 프로젝트가 LLM 단독 검증용으로 계속 살아 있어야 하는데 AGP 버전이 서로 달라
 * (이 프로젝트 9.x / LlamaDemo 8.x) 빌드 파일을 공유할 수 없기 때문이다. 소스를 복사하면
 * 두 벌이 되어 한쪽만 고치는 사고가 난다.
 *
 * `ai_chat.cpp` 를 고쳤다면 `LLM/build_native.sh` 로 .so 를 다시 만들어야 한다.
 */
android {
    namespace = "com.arm.aichat"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // llama.cpp 미리 빌드된 .so 들이 API 28 기준이라 이보다 낮출 수 없다.
        minSdk = 28
    }

    sourceSets["main"].apply {
        java.srcDirs("../LLM/LlamaDemo/lib/src/main/java")
        jniLibs.srcDirs("../LLM/LlamaDemo/lib/src/main/jniLibs")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
