cmake_minimum_required(VERSION 3.22.1)
project(voice_dsp LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_library(voice-dsp SHARED
    VoiceProcessor.cpp VoiceJni.cpp
    vendor/rubberband/single/RubberBandSingle.cpp
    vendor/klarinet/dsp/primitives/Biquad.cpp
    vendor/klarinet/dsp/primitives/CircularBuffer.cpp
    vendor/klarinet/dsp/primitives/LFO.cpp
    vendor/klarinet/dsp/effects/Tremolo.cpp
    vendor/klarinet/dsp/effects/HighPassFilter.cpp
    vendor/klarinet/dsp/effects/LowPassFilter.cpp
    vendor/klarinet/dsp/effects/Chorus.cpp
    vendor/klarinet/dsp/effects/Delay.cpp
)
target_include_directories(voice-dsp PRIVATE vendor/klarinet/dsp)
target_compile_options(voice-dsp PRIVATE -fexceptions -frtti)
# 16 KiB alignment supports recent Android devices without a compatibility mode.
target_link_options(voice-dsp PRIVATE "-Wl,-z,max-page-size=16384")
