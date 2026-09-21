plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.arm.aichat"
    compileSdk = 36

    defaultConfig {
        minSdk = 33

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
             // AEMX: 이 PC에서 CMake가 NDK 툴체인으로 컴파일러를 식별할 때 원인불명
             // 크래시(재현 조건 특정 못 함)가 있어서, Gradle의 externalNativeBuild(CMake)를
             // 아예 쓰지 않기로 함. 대신 NDK의 clang++를 직접 호출해서 libai-chat.so를
             // 미리 컴파일해두고 jniLibs에 넣음 (build_native.sh 참고, llama 본체 .so들과
             // 같은 방식). 그 .so가 arm64-v8a만 있어서 x86_64 제외.
             // 에뮬레이터가 아니라 실기기로 테스트할 것.
             abiFilters += listOf("arm64-v8a")
        }
        aarMetadata {
            minCompileSdk = 35
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)

        compileOptions {
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    publishing {
        singleVariant("release") {
            withJavadocJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // AEMX: androidx.datastore.preferences 제거함 - 코드에서 전혀 안 쓰이는데
    // x86_64용 .so(libdatastore_shared_counter.so)를 같이 넣고 있어서, 안드로이드가
    // 이 앱의 "네이티브 지원 ABI"를 x86_64로 착각하고 arm64-v8a 폴더(우리 llama
    // 라이브러리들이 있는 곳)를 통째로 무시해버리는 원인이었음.

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
