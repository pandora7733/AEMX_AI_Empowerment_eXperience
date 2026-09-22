#!/usr/bin/env bash
# ai_chat.cpp를 CMake 없이 NDK clang++로 직접 컴파일해서 libai-chat.so를 만드는 스크립트.
#
# 원래 Gradle의 externalNativeBuild(CMake)로 빌드했으나, 특정 PC 환경에서
# cmake.exe가 NDK 툴체인으로 컴파일러를 식별할 때 원인불명으로 크래시하는
# 문제가 있었음 (재현 조건을 못 찾음 - LLM/README.md 참고). ai_chat.cpp는
# 파일 하나뿐이라 CMake 없이 clang++를 직접 호출해도 충분해서 이 방식으로 우회함.
#
# ai_chat.cpp를 수정했다면 이 스크립트를 다시 실행해서 libai-chat.so를 갱신할 것.
#
# 사용법:
#   cd LLM
#   bash setup_llamademo.sh   # 먼저 llama.cpp 헤더 + .so 받아두기
#   bash build_native.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LLAMADEMO="$SCRIPT_DIR/LlamaDemo"
LLAMA_SRC="$LLAMADEMO/.llama.cpp-src"
JNILIBS="$LLAMADEMO/lib/src/main/jniLibs/arm64-v8a"
CPP_DIR="$LLAMADEMO/lib/src/main/cpp"

if [ ! -d "$LLAMA_SRC" ] || [ ! -f "$JNILIBS/libllama.so" ]; then
  echo "먼저 setup_llamademo.sh를 실행해서 llama.cpp 헤더/.so를 받아두세요."
  exit 1
fi

NDK_DIR=$(find "$LOCALAPPDATA/Android/Sdk/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1)
if [ -z "$NDK_DIR" ]; then
  echo "Android NDK를 찾을 수 없습니다. Android Studio SDK Manager에서 NDK를 설치하세요."
  exit 1
fi
echo "사용할 NDK: $NDK_DIR"

# 이 값이 앱 전체의 minSdk 하한을 정한다. llama.cpp 미리 빌드된 .so 들이 API 28
# 기준이라 그보다 낮출 수는 없고, 28이면 안드로이드 9 이상이라 대부분의 기기가 들어온다.
# (처음엔 33으로 잡았다가, 앱 minSdk를 33까지 끌어올려야 해서 28로 낮춤)
API_LEVEL=28

CLANGXX="$NDK_DIR/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android${API_LEVEL}-clang++.cmd"
if [ ! -f "$CLANGXX" ]; then
  # macOS/Linux용 fallback (prebuilt 디렉터리명이 다름)
  CLANGXX=$(find "$NDK_DIR/toolchains/llvm/prebuilt" -maxdepth 2 -name "aarch64-linux-android${API_LEVEL}-clang++*" | head -1)
fi

echo "=== libai-chat.so 컴파일 중 ==="
"$CLANGXX" \
  -std=c++17 -fPIC -shared -O2 \
  -Wl,-z,max-page-size=16384 \
  -I"$LLAMA_SRC" -I"$LLAMA_SRC/common" -I"$LLAMA_SRC/include" -I"$LLAMA_SRC/ggml/include" -I"$LLAMA_SRC/ggml/src" \
  "$CPP_DIR/ai_chat.cpp" \
  -L"$JNILIBS" -lllama -lllama-common -landroid -llog \
  -o "$JNILIBS/libai-chat.so"

echo "=== libc++_shared.so 복사 중 (libai-chat.so 실행에 필요) ==="
CXX_SHARED=$(find "$NDK_DIR/toolchains/llvm/prebuilt" -path "*aarch64-linux-android/libc++_shared.so" | head -1)
cp "$CXX_SHARED" "$JNILIBS/"

echo "완료: $JNILIBS/libai-chat.so, $JNILIBS/libc++_shared.so"
