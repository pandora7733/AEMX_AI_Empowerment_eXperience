// AEMX — Gradle 설정 스크립트. 플러그인/의존성 저장소와 포함할 모듈을 정의한다.

pluginManagement {
    // 플러그인(AGP, Kotlin)을 내려받을 저장소
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 모듈별 repositories 선언을 금지하고 이 파일의 저장소만 사용하도록 강제한다.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AEMX"
include(":app")
