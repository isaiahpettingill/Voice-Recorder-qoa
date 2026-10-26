#pragma once
#include <cstdint>
#include <functional>
#include <vector>
namespace voice {
// Integration only: Rubber Band owns pitch/time/formant DSP; Klarinet owns effects.
std::vector<int16_t> process(const std::vector<int16_t>& samples, int rate, int channels,
    double pitchSemitones, double formantSemitones, double speed, int effect, double mix,
    const std::function<bool()>& canceled = [] { return false; });
}
