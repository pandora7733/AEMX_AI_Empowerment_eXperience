#!/usr/bin/env bash
# LlamaDemo(Android) 빌드에 필요한 llama.cpp 소스를 받아오는 스크립트.
#
# LlamaDemo는 llama.cpp를 NDK로 소스 빌드하는 방식이라, CMake가 llama.cpp
# 전체 소스 트리를 필요로 함. 용량/중복 문제로 git에는 커밋하지 않고,
# 이 스크립트로 LlamaDemo/.llama.cpp-src 에 clone 받아서 사용함
# (LlamaDemo/lib/src/main/cpp/CMakeLists.txt 의 LLAMA_SRC 경로가 여길 가리킴).
#
# 사용법:
#   cd LLM
#   bash setup_llamademo.sh
#   그 다음 Android Studio에서 LLM/LlamaDemo 를 열고 Gradle sync

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$SCRIPT_DIR/LlamaDemo/.llama.cpp-src"

if [ -d "$DEST" ]; then
  echo "이미 존재함: $DEST (다시 받으려면 폴더를 지우고 재실행)"
  exit 0
fi

echo "=== llama.cpp 소스 clone 중 (NDK 빌드용, 약 몇 분 소요) ==="
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "$DEST"

echo ""
echo "완료. Android Studio에서 LLM/LlamaDemo 폴더를 열고 Gradle sync 하세요."
echo "(NDK r29, CMake 3.31.6이 필요하며, 없으면 Gradle sync 중 자동 설치를 시도합니다.)"
