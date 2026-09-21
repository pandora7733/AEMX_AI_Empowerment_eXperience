#!/usr/bin/env bash
# LlamaDemo(Android) 빌드에 필요한 llama.cpp 소스(헤더용)와 미리 빌드된
# 네이티브 라이브러리(.so)를 받아오는 스크립트.
#
# 이 프로젝트는 llama.cpp를 NDK로 소스 빌드하지 않고(이 PC에서 CMake가
# 크래시하는 문제가 있었음 - LLM/README.md 참고), GitHub 릴리스의 미리 빌드된
# .so를 그대로 링크한다. 대신 헤더 파일들(common.h, llama.h 등)은 소스에서
# 참조해야 해서 llama.cpp 저장소를 정확히 같은 빌드(b11067)로 clone 받는다.
# 버전이 안 맞으면 헤더와 .so의 ABI가 어긋나서 이상하게 동작하거나
# 크래시할 수 있으니 반드시 이 태그를 유지할 것.
#
# 사용법:
#   cd LLM
#   bash setup_llamademo.sh
#   그 다음 Android Studio에서 LLM/LlamaDemo 를 열고 Gradle sync

set -e

LLAMA_CPP_TAG="b11067"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DEST="$SCRIPT_DIR/LlamaDemo/.llama.cpp-src"
JNILIBS_DEST="$SCRIPT_DIR/LlamaDemo/lib/src/main/jniLibs/arm64-v8a"

if [ -d "$SRC_DEST" ]; then
  echo "이미 존재함: $SRC_DEST (다시 받으려면 폴더를 지우고 재실행)"
else
  echo "=== llama.cpp 소스 clone 중 (헤더 참조용, $LLAMA_CPP_TAG) ==="
  git clone --branch "$LLAMA_CPP_TAG" --depth 1 https://github.com/ggml-org/llama.cpp.git "$SRC_DEST"
fi

if [ -f "$JNILIBS_DEST/libllama.so" ]; then
  echo "이미 존재함: $JNILIBS_DEST (다시 받으려면 .so 파일들을 지우고 재실행)"
else
  echo "=== 미리 빌드된 네이티브 라이브러리 다운로드 중 (arm64, $LLAMA_CPP_TAG) ==="
  TMP_DIR="$SCRIPT_DIR/.tmp_llamademo_libs"
  mkdir -p "$TMP_DIR" "$JNILIBS_DEST"
  cd "$TMP_DIR"
  curl -L -C - -o "llama-android-arm64.tar.gz" \
    "https://github.com/ggml-org/llama.cpp/releases/download/${LLAMA_CPP_TAG}/llama-${LLAMA_CPP_TAG}-bin-android-arm64.tar.gz"
  tar -xzf "llama-android-arm64.tar.gz"
  cp "${LLAMA_CPP_TAG}"/libggml-base.so \
     "${LLAMA_CPP_TAG}"/libggml.so \
     "${LLAMA_CPP_TAG}"/libggml-cpu-android_*.so \
     "${LLAMA_CPP_TAG}"/libggml-rpc.so \
     "${LLAMA_CPP_TAG}"/libllama.so \
     "${LLAMA_CPP_TAG}"/libllama-common.so \
     "${LLAMA_CPP_TAG}"/libmtmd.so \
     "$JNILIBS_DEST/"
fi

echo ""
echo "완료. Android Studio에서 LLM/LlamaDemo 폴더를 열고 Gradle sync 하세요."
echo "(NDK 27, CMake 3.22.1이 필요하며, 없으면 Gradle sync 중 자동 설치를 시도합니다.)"
echo "주의: 미리 빌드된 라이브러리가 arm64-v8a(실기기)만 있음 - 에뮬레이터 대신 실기기로 테스트할 것."
