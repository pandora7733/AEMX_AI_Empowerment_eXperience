#!/usr/bin/env bash
# LLM 개발/테스트용 대용량 파일(모델 + llama.cpp 실행파일) 다운로드 스크립트 (Windows 기준)
#
# 용량 문제로 git에 커밋하지 않음. 이 스크립트로 로컬에 받아서 test_prompt.sh와 함께 사용.
#
# 사용법:
#   cd LLM
#   bash setup_llm.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$SCRIPT_DIR/.tools"
mkdir -p "$TOOLS_DIR"
cd "$TOOLS_DIR"

MODEL_URL="https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf"
LLAMA_CLI_URL="https://github.com/ggml-org/llama.cpp/releases/download/b11067/llama-b11067-bin-win-cpu-x64.zip"

echo "=== [1/2] Qwen3 1.7B (Q4_K_M, GGUF, 약 1.2GB) 다운로드 ==="
if [ ! -f "Qwen_Qwen3-1.7B-Q4_K_M.gguf" ]; then
  curl -L -C - -o "Qwen_Qwen3-1.7B-Q4_K_M.gguf" "$MODEL_URL"
fi

echo "=== [2/2] llama.cpp CLI (Windows CPU 빌드, 약 18MB) 다운로드 ==="
if [ ! -f "llama-cli.exe" ]; then
  curl -L -C - -o "llama-bin-win-cpu-x64.zip" "$LLAMA_CLI_URL"
  unzip -o llama-bin-win-cpu-x64.zip -d _extracted
  cp _extracted/llama-cli.exe .
  cp _extracted/llama-cli-impl.dll . 2>/dev/null || true
  # llama-cli 실행에 필요한 나머지 dll도 함께 복사
  cp _extracted/*.dll . 2>/dev/null || true
fi

echo ""
echo "완료. 다음으로 테스트: bash ../test_prompt.sh"
