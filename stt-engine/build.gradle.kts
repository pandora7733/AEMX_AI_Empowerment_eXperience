plugins {
    alias(libs.plugins.android.library)
}

/**
 * STT(음성인식) 엔진 모듈.
 *
 * 소스는 `STT/SherpaOnnx` 프로젝트의 것을 그대로 쓴다. 복사해 오지 않는 이유는
 * llm-engine 과 같다 — 그 프로젝트가 STT 단독 검증용으로 계속 살아 있어야 하는데,
 * 소스를 두 벌로 두면 한쪽만 고치는 사고가 난다.
 *
 * 다만 그 폴더에는 검증용 화면(`MainActivity.kt`)이 같이 들어 있다. 그 화면은 자기
 * 프로젝트의 R 과 레이아웃을 참조해서 여기서 컴파일하면 깨진다. 그래서 디렉터리를
 * 통째로 가리키지 않고, 라이브러리 소스만 골라 복사한 뒤 그것을 컴파일한다.
 */
val sherpaSrc = rootProject.file("STT/SherpaOnnx/app/src/main")
val generatedApiDir = layout.buildDirectory.dir("generated/sherpaApi")

val copySherpaApi by tasks.registering(Copy::class) {
    from(sherpaSrc.resolve("java")) {
        // 검증용 화면은 빼고 라이브러리 쪽만 가져온다.
        exclude("**/MainActivity.kt")
    }
    into(generatedApiDir)
}

android {
    namespace = "com.k2fsa.sherpa.onnx"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// AGP 9 에서는 android { sourceSets[...] } 로 접근하면 Kotlin DSL 이 예전 AGP 타입으로
// 캐스팅을 시도하다 깨진다(llm-engine 과 같은 문제). 새 DSL 인터페이스를 직접 지정한다.
extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    sourceSets.getByName("main") {
        // 소스가 전부 .kt 라서 kotlin 쪽을 반드시 지정해야 한다. java 만 지정하면
        // compileKotlin 이 NO-SOURCE 로 지나간다.
        java.srcDirs(generatedApiDir.get().asFile)
        kotlin.srcDirs(generatedApiDir.get().asFile)
        jniLibs.srcDirs(sherpaSrc.resolve("jniLibs"))
        assets.srcDirs(sherpaSrc.resolve("assets"))
    }
}

tasks.named("preBuild") {
    dependsOn(copySherpaApi)
}
