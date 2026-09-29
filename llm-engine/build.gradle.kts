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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// AGP 9 에서는 android { sourceSets["main"] } 로 접근하면 Kotlin DSL 이 예전 AGP 타입으로
// 캐스팅을 시도하다 깨진다. 새 DSL 인터페이스를 직접 지정해 생성된 접근자를 우회한다.
extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    val libSrc = "../LLM/LlamaDemo/lib/src/main"
    sourceSets.getByName("main") {
        manifest.srcFile("$libSrc/AndroidManifest.xml")
        // 소스가 전부 .kt 라서 kotlin 쪽을 반드시 같이 지정해야 한다. java 만 지정하면
        // compileKotlin 이 NO-SOURCE 로 지나가고, 앱에서 Unresolved reference 가 난다.
        java.srcDirs("$libSrc/java")
        kotlin.srcDirs("$libSrc/java")
        jniLibs.srcDirs("$libSrc/jniLibs")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
