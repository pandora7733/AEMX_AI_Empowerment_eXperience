#!/usr/bin/env bash
# STT 개발용 대용량 에셋(한국어 STT 모델 + sherpa-onnx 네이티브 라이브러리) 다운로드 스크립트
#
# 이 파일들은 GitHub 100MB 파일 크기 제한 및 저장소 용량 문제로 git에 커밋하지 않았습니다.
# 이 스크립트를 실행하면 app/src/main/assets, app/src/main/jniLibs에 자동으로 받아집니다.
#
# 사용법: STT/SherpaOnnx 디렉터리에서 실행
#   cd STT/SherpaOnnx
#   bash ../setup_assets.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/SherpaOnnx"
TMP_DIR="$SCRIPT_DIR/.tmp_assets"

mkdir -p "$TMP_DIR"
cd "$TMP_DIR"

MODEL_NAME="sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL_NAME}.tar.bz2"
LIBS_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-v1.13.7-android.tar.bz2"

echo "=== [1/4] 한국어 STT 모델 다운로드 ==="
if [ ! -f "${MODEL_NAME}.tar.bz2" ]; then
  curl -L -C - -o "${MODEL_NAME}.tar.bz2" "$MODEL_URL"
fi

echo "=== [2/4] 네이티브 라이브러리(.so) 다운로드 ==="
if [ ! -f "sherpa-onnx-v1.13.7-android.tar.bz2" ]; then
  curl -L -C - -o "sherpa-onnx-v1.13.7-android.tar.bz2" "$LIBS_URL"
fi

echo "=== [3/4] 압축 해제 및 모델 파일 배치 ==="
tar -xjf "${MODEL_NAME}.tar.bz2"
MODEL_DEST="$PROJECT_DIR/app/src/main/assets/sherpa-onnx-streaming-zipformer-korean-2024-06-16"
mkdir -p "$MODEL_DEST"
cp "$MODEL_NAME/encoder-epoch-99-avg-1.int8.onnx" "$MODEL_DEST/"
cp "$MODEL_NAME/decoder-epoch-99-avg-1.onnx" "$MODEL_DEST/"
cp "$MODEL_NAME/joiner-epoch-99-avg-1.int8.onnx" "$MODEL_DEST/"
cp "$MODEL_NAME/tokens.txt" "$MODEL_DEST/"

echo "=== [4/4] 압축 해제 및 네이티브 라이브러리 배치 ==="
tar -xjf "sherpa-onnx-v1.13.7-android.tar.bz2"
JNI_DEST="$PROJECT_DIR/app/src/main/jniLibs"
mkdir -p "$JNI_DEST/x86_64" "$JNI_DEST/arm64-v8a"
cp jniLibs/x86_64/*.so "$JNI_DEST/x86_64/"
cp jniLibs/arm64-v8a/*.so "$JNI_DEST/arm64-v8a/"

echo "=== [추가] 화자 확인 모델 다운로드 (내 목소리만 듣기용, 28MB) ==="
SPEAKER_DEST="$PROJECT_DIR/app/src/main/assets/speaker"
SPEAKER_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
mkdir -p "$SPEAKER_DEST"
if [ ! -f "$SPEAKER_DEST/campplus_zh_en.onnx" ]; then
  curl -L -C - -o "$SPEAKER_DEST/campplus_zh_en.onnx" "$SPEAKER_URL"
fi

echo ""
echo "완료. Android Studio에서 STT/SherpaOnnx 프로젝트를 열고 빌드하세요."
echo "임시 다운로드 파일은 $TMP_DIR 에 남아있습니다 (삭제해도 무방)."
