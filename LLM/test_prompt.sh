#!/usr/bin/env bash
# LLM 의도분석 프롬프트 테스트 스크립트 (PC용, llama.cpp CLI 사용)
#
# 사용법:
#   1) setup_llm.sh 로 llama-cli와 모델을 먼저 받는다
#   2) bash test_prompt.sh "네이버에서 서울 날씨 찾아줘"
#      (인자를 안 주면 아래 기본 테스트 케이스들을 전부 돌림)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LLAMA_CLI="$SCRIPT_DIR/.tools/llama-cli.exe"
MODEL="$SCRIPT_DIR/.tools/Qwen_Qwen3-1.7B-Q4_K_M.gguf"
SYS_PROMPT_FILE="$SCRIPT_DIR/system_prompt.txt"

if [ ! -f "$LLAMA_CLI" ] || [ ! -f "$MODEL" ]; then
  echo "llama-cli 또는 모델이 없습니다. 먼저 setup_llm.sh를 실행하세요."
  exit 1
fi

run_case() {
  local cmd="$1"
  echo "=== 입력: $cmd ==="
  "$LLAMA_CLI" -m "$MODEL" -sysf "$SYS_PROMPT_FILE" -p "$cmd" -st -n 800 --temp 0 --no-display-prompt 2>&1 \
    | grep -oE '\{"app".*\]\}' | head -1
  echo ""
}

if [ $# -gt 0 ]; then
  run_case "$1"
else
  run_case "네이버에서 서울 날씨 찾아줘"
  run_case "구글에서 오늘 주요 뉴스 검색해줘"
  run_case "유튜브에서 고양이 영상 검색해서 첫 번째 영상 틀어줘"
  run_case "인스타 릴스 다음 영상 보여줘"
fi
