#include "NoiseReducer.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <random>
#include <stdexcept>
#include <chrono>
using Samples = std::vector<int16_t>;
static Samples run(const Samples& input, int rate, int channels, size_t chunk) {
    voice::NoiseReducer reducer(rate, channels);
    Samples output;
    for (size_t pos = 0; pos < input.size(); pos += chunk) {
        auto end = std::min(pos + chunk, input.size());
        auto part = reducer.process(Samples(input.begin() + pos, input.begin() + end));
        output.insert(output.end(), part.begin(), part.end());
    }
    auto tail = reducer.finish();
    output.insert(output.end(), tail.begin(), tail.end());
    assert(output.size() == input.size());
    return output;
}
static double energy(const Samples& data, size_t start, size_t end) {
    double sum = 0;
    for (size_t i = start; i < end; ++i) sum += double(data[i]) * data[i];
    return sum / (end - start);
}
int main() {
    auto started = std::chrono::steady_clock::now();
    for (int rate : {8000, 16000, 22050, 44100, 48000, 96000, 192000}) {
        for (int channels : {1, 2}) {
            for (int count : {0, 1, 71, rate / 50 - 1, rate / 50, rate / 50 + 1, rate + 17}) {
                Samples silence(count * channels);
                auto out = run(silence, rate, channels, 74);
                assert(std::all_of(out.begin(), out.end(), [](int16_t x) { return x == 0; }));
            }
        }
    }
    std::mt19937 rng(42);
    std::uniform_int_distribution<int> noise(-1600, 1600);
    constexpr int rate = 48000;
    Samples input(rate * 5), speech(rate * 5);
    // Deterministic voiced, syllabic multi-harmonic signal preceded by noise-only
    // adaptation. This is a regression signal, not a speech-quality benchmark.
    for (size_t i = 0; i < input.size(); ++i) {
        double t = double(i) / rate;
        double envelope = t >= 2 ? 0.3 + 0.7 * std::pow(std::sin(2 * 3.141592653589793 * 2 * t), 2) : 0;
        double voiced = 0;
        for (int harmonic = 1; harmonic <= 8; ++harmonic)
            voiced += std::sin(2 * 3.141592653589793 * (130 + 15 * std::sin(t * 5)) * harmonic * t) / harmonic;
        speech[i] = static_cast<int16_t>(6500 * envelope * voiced);
        input[i] = speech[i] + noise(rng);
    }
    auto output = run(input, rate, 1, 65536);
    assert(output == run(input, rate, 1, 137)); // Arbitrary chunk boundaries invariant.
    double noiseDb = 10 * std::log10(energy(output, rate, 2*rate) / energy(input, rate, 2*rate));
    double voiceRatio = energy(output, 2*rate, 5*rate) / energy(speech, 2*rate, 5*rate);
    std::cout << "noise-only attenuation: " << noiseDb << " dB; voiced energy ratio: " << voiceRatio << '\n';
    assert(noiseDb < -6);
    assert(voiceRatio > 0.25 && voiceRatio < 1.5);
    // Stereo independence: left channel matches mono exactly; right stays silent.
    Samples stereo(input.size()*2);
    for (size_t i = 0; i < input.size(); ++i) stereo[2*i] = input[i];
    auto stereoOut = run(stereo, rate, 2, 382);
    for (size_t i = 0; i < input.size(); ++i) {
        assert(stereoOut[2*i] == output[i]);
        assert(stereoOut[2*i+1] == 0);
    }
    // An isolated transient with suppression enabled stays at its original time
    // (one-frame library analysis delay is removed, not retained in the file).
    Samples impulse(rate, 0); impulse[rate/2] = 30000;
    auto impulseOut = run(impulse, rate, 1, 101);
    auto peak = std::max_element(impulseOut.begin(), impulseOut.end());
    assert(std::distance(impulseOut.begin(), peak) == rate/2);
    voice::NoiseReducer cancel(rate, 1);
    bool canceled = false;
    try { cancel.process(Samples(4096), [] { return true; }); } catch (const std::runtime_error& error) { canceled = std::string(error.what()) == "Noise reduction canceled"; }
    assert(canceled);
    bool invalid = false;
    try { voice::NoiseReducer bad(0, 1); } catch (const std::invalid_argument&) { invalid = true; }
    assert(invalid);
    voice::NoiseReducer finished(rate, 1); finished.finish();
    bool closed = false;
    try { finished.process({1}); } catch (const std::logic_error&) { closed = true; }
    assert(closed);
    std::cout << "Noise reduction tests passed (" << std::chrono::duration<double>(std::chrono::steady_clock::now()-started).count() << " s)\n";
}
