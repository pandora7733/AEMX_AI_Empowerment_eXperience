plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sihoo.aemx.devicechecks"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.sihoo.aemx.devicechecks"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
