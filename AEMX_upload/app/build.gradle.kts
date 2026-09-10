// AEMX 앱 모듈 빌드 스크립트.
// Kotlin + Java 혼용, ViewBinding 기반의 UI 껍데기 앱이다.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aemx.autologin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aemx.autologin"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 껍데기 단계에서는 난독화를 끄고 룰 파일만 연결해 둔다.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // ViewBinding 사용 (DataBinding·Compose는 계약상 사용 금지)
    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // --- AndroidX / Material 기본 구성 ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    // 스플래시 API — 의존성만 확보해 두고 코드에서는 사용하지 않는다.
    implementation(libs.androidx.core.splashscreen)

    // --- 테스트 ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
