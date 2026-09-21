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
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // 파이프라인 상태머신은 코루틴/Flow 위에서 돈다.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json 은 안드로이드 프레임워크에 들어 있지만 JVM 단위테스트에서는 빈 스텁이다.
    // 실제 구현을 테스트 클래스패스에 올려 파서를 로컬에서 검증한다.
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}