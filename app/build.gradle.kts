import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

/**
 * 클라우드 AI(Groq) 키. 소스에 적지 않고 빌드할 때만 읽어 BuildConfig 에 넣는다.
 * 순서: 환경변수 GROQ_API_KEY -> local.properties 의 GROQ_API_KEY -> 없으면 빈 값(클라우드 꺼짐).
 * local.properties 는 .gitignore 에 있어 저장소에 올라가지 않는다.
 * 주의: APK 를 풀면 이 값이 보인다. APK 를 공개 배포하지 않는다.
 */
val groqApiKey: String = run {
    val fromEnv = System.getenv("GROQ_API_KEY")
    val fromFile = Properties().let { props ->
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { props.load(it) }
        props.getProperty("GROQ_API_KEY")
    }
    (fromEnv ?: fromFile ?: "").trim().replace("\\", "").replace("\"", "")
}

/**
 * YouTube Data API 키. GROQ 키와 같은 순서로 읽는다: 환경변수 YOUTUBE_API_KEY -> local.properties -> 없으면 빈 값(API 꺼짐).
 * 소스·로그·테스트에는 쓰지 않는다.
 */
val youtubeApiKey: String = run {
    val fromEnv = System.getenv("YOUTUBE_API_KEY")
    val fromFile = Properties().let { props ->
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { props.load(it) }
        props.getProperty("YOUTUBE_API_KEY")
    }
    (fromEnv ?: fromFile ?: "").trim().replace("\\", "").replace("\"", "")
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

        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")
        buildConfigField("String", "YOUTUBE_API_KEY", "\"$youtubeApiKey\"")

        // llama.cpp .so 가 arm64-v8a 만 있다. 다른 ABI 를 함께 선언하면 안드로이드가
        // 그쪽을 앱의 ABI 로 골라 arm64 폴더를 통째로 무시하는 일이 생긴다(LLM/README.md 참고).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
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
