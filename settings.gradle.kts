pluginManagement {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AEMX"
include(":app")
include(":accessibility-device-tests")
project(":accessibility-device-tests").projectDir = file("ACCESSIBILITY/device-tests")

// 폴더 이름이 llm 이 아니라 llm-engine 인 이유: 윈도우는 대소문자를 구분하지 않아서
// llm/ 을 만들면 기존 LLM/ 폴더와 같은 것으로 취급된다. 팀원 환경(리눅스/맥)에서만
// 깨지는 종류의 문제라 아예 겹치지 않는 이름을 쓴다.
include(":llm-engine")
