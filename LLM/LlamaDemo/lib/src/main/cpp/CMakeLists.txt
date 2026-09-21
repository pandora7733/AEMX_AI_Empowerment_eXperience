cmake_minimum_required(VERSION 3.22.1)

# AEMX: 이 파일은 현재 Gradle에서 쓰이지 않음 (lib/build.gradle.kts에
# externalNativeBuild가 없음) — 참고용으로만 남겨둠.
#
# 원래 llama.cpp 전체를 소스에서 빌드했으나(add_subdirectory), 이 PC에서
# CMake가 NDK 툴체인으로 컴파일러를 식별하는 순간(project() 실행 중) 원인불명
# 크래시(스택 버퍼 오버런)가 났음. 언어 선언 개수, VERSION 유무,
# add_library/add_executable 등 여러 변수를 바꿔가며 재현 조건을 좁혀봤지만
# 특정하지 못했고, 심지어 조금 전엔 성공했던 것과 동일한 설정이 다음 시도에서
# 실패하는 등 비결정적으로 보임 (백신/보안 소프트웨어의 간헐적 개입으로 추정).
#
# 결국 CMake 자체를 포기하고, NDK의 clang++를 직접 호출해서 ai_chat.cpp만
# 컴파일하는 방식으로 우회함 (LLM/build_native.sh). llama.cpp 본체는 STT 때처럼
# GitHub 릴리스의 미리 빌드된 .so를 그대로 링크. 결과물 libai-chat.so는
# 용량이 작아서(수십 KB) git에 커밋해둠 — 다른 사람은 이 삽질을 안 겪어도 됨.
#
# 만약 CMake가 멀쩡하게 동작하는 환경이라면, 이 파일 기준으로 아래처럼
# 구성하면 된다: llama.cpp 소스 전체를 add_subdirectory로 끌어와 처음부터
# 빌드하거나, 지금처럼 미리 빌드된 .so를 IMPORTED 라이브러리로 링크.
#
# 대가: 미리 빌드된 라이브러리가 arm64-v8a(실기기)만 있어서 x86_64
# 에뮬레이터는 지원 안 함 - 실기기로 테스트할 것.
project("ai-chat" VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../.llama.cpp-src)
set(PREBUILT_LIBS_DIR ${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI})

# llama.cpp를 소스로 빌드하지 않고, 헤더만 컴파일에 참조하고
# 실제 구현은 미리 빌드된 .so(build b11067, arm64-v8a)를 링크한다.
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libllama.so)

add_library(llama-common SHARED IMPORTED)
set_target_properties(llama-common PROPERTIES IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libllama-common.so)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
