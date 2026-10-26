#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
cpp=app/src/main/cpp
(cd "$cpp/vendor" && sha256sum -c SHA256SUMS >/dev/null)
mkdir -p build/native-tests/speex
for source in preprocess mdf fftwrap filterbank kiss_fft kiss_fftr; do
  "${CC:-cc}" -O2 -DFLOATING_POINT -DUSE_KISS_FFT -DEXPORT= -I"$cpp/vendor/speexdsp/include" \
    -c "$cpp/vendor/speexdsp/libspeexdsp/$source.c" -o "build/native-tests/speex/$source.o"
done
"${CXX:-g++}" -std=c++17 -O2 -I"$cpp" -I"$cpp/vendor/speexdsp/include" \
  scripts/native/noise_reducer_test.cpp "$cpp/NoiseReducer.cpp" build/native-tests/speex/*.o \
  -o build/native-tests/noise-reducer-test
build/native-tests/noise-reducer-test
