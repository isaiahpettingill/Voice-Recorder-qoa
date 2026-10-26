#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
cpp=app/src/main/cpp
(cd "$cpp/vendor" && sha256sum -c SHA256SUMS >/dev/null)
mkdir -p build/native-tests
"${CXX:-g++}" -std=c++17 -O2 -pthread -I"$cpp" -I"$cpp/vendor/klarinet/dsp" \
  scripts/native/voice_processor_test.cpp "$cpp/VoiceProcessor.cpp" \
  "$cpp/vendor/rubberband/single/RubberBandSingle.cpp" \
  "$cpp/vendor/klarinet/dsp/primitives/"{Biquad,CircularBuffer,LFO}.cpp \
  "$cpp/vendor/klarinet/dsp/effects/"{Tremolo,HighPassFilter,LowPassFilter,Chorus,Delay}.cpp \
  -o build/native-tests/voice-processor-test
build/native-tests/voice-processor-test
