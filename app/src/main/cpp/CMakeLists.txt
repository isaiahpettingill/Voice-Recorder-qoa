cmake_minimum_required(VERSION 3.22.1)
project(voice_dsp LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_library(voice-dsp SHARED
    VoiceProcessor.cpp VoiceJni.cpp NoiseReducer.cpp NoiseJni.cpp
    vendor/rubberband/single/RubberBandSingle.cpp
    vendor/klarinet/dsp/primitives/Biquad.cpp
    vendor/klarinet/dsp/primitives/CircularBuffer.cpp
    vendor/klarinet/dsp/primitives/LFO.cpp
    vendor/klarinet/dsp/effects/Tremolo.cpp
    vendor/klarinet/dsp/effects/HighPassFilter.cpp
    vendor/klarinet/dsp/effects/LowPassFilter.cpp
    vendor/klarinet/dsp/effects/Chorus.cpp
    vendor/klarinet/dsp/effects/Delay.cpp
    vendor/klarinet/dsp/effects/Reverb.cpp
    vendor/klarinet/dsp/effects/Flanger.cpp
    vendor/klarinet/dsp/effects/Phaser.cpp
)
target_include_directories(voice-dsp PRIVATE vendor/klarinet/dsp)
target_compile_options(voice-dsp PRIVATE -fexceptions -frtti)
# 16 KiB alignment supports recent Android devices without a compatibility mode.
target_link_options(voice-dsp PRIVATE "-Wl,-z,max-page-size=16384")

# Xiph SpeexDSP 1.2.1, floating-point denoiser. Upstream C is compiled as C.
add_library(speex-denoise STATIC
    vendor/speexdsp/libspeexdsp/preprocess.c
    vendor/speexdsp/libspeexdsp/mdf.c
    vendor/speexdsp/libspeexdsp/fftwrap.c
    vendor/speexdsp/libspeexdsp/filterbank.c
    vendor/speexdsp/libspeexdsp/kiss_fft.c
    vendor/speexdsp/libspeexdsp/kiss_fftr.c)
set_target_properties(speex-denoise PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(speex-denoise PRIVATE FLOATING_POINT USE_KISS_FFT EXPORT=)
target_include_directories(speex-denoise PUBLIC vendor/speexdsp/include)
target_link_libraries(voice-dsp PRIVATE speex-denoise)
