#include "VoiceProcessor.h"
#include "vendor/rubberband/rubberband/RubberBandStretcher.h"
#include "vendor/klarinet/dsp/effects/Tremolo.h"
#include "vendor/klarinet/dsp/effects/HighPassFilter.h"
#include "vendor/klarinet/dsp/effects/LowPassFilter.h"
#include "vendor/klarinet/dsp/effects/Chorus.h"
#include "vendor/klarinet/dsp/effects/Delay.h"
#include "vendor/klarinet/dsp/effects/Reverb.h"
#include "vendor/klarinet/dsp/effects/Flanger.h"
#include "vendor/klarinet/dsp/effects/Phaser.h"
#include <algorithm>
#include <cmath>
#include <memory>
#include <stdexcept>

namespace voice {
namespace {
constexpr size_t MAX_INPUT_SAMPLES = 8'000'000;
constexpr size_t MAX_OUTPUT_SAMPLES = 16'000'000;
constexpr size_t BLOCK = 1024;
void checkCancel(const std::function<bool()>& canceled) {
    if (canceled()) throw std::runtime_error("Audio edit canceled");
}
}
std::vector<int16_t> process(const std::vector<int16_t>& samples, int rate, int channels,
    double pitchSemitones, double formantSemitones, double speed, int effect, double mix,
    const std::function<bool()>& canceled) {
    if (channels < 1 || channels > 2 || rate < 8000 || rate > 192000 || samples.empty() ||
        samples.size() % channels != 0 || samples.size() > MAX_INPUT_SAMPLES ||
        !std::isfinite(pitchSemitones) || std::abs(pitchSemitones) > 12 ||
        !std::isfinite(formantSemitones) || std::abs(formantSemitones) > 12 ||
        !std::isfinite(speed) || speed < .5 || speed > 2 ||
        effect < 0 || effect > 8 || !std::isfinite(mix) || mix < 0 || mix > 1) {
        throw std::invalid_argument("Invalid audio processing settings");
    }
    checkCancel(canceled);
    const size_t frames = samples.size() / channels;
    const size_t targetFrames = static_cast<size_t>(std::llround(frames / speed));
    if (targetFrames < 1 || targetFrames * channels > MAX_OUTPUT_SAMPLES)
        throw std::invalid_argument("Edited audio exceeds memory limit; trim it first");
    std::vector<int16_t> output;
    if (pitchSemitones == 0 && formantSemitones == 0 && speed == 1) {
        output = samples;
    } else {
        using RB = RubberBand::RubberBandStretcher;
        const double pitch = std::pow(2.0, pitchSemitones / 12.0);
        RB engine(rate, channels, RB::OptionProcessOffline | RB::OptionEngineFiner |
            RB::OptionChannelsTogether | RB::OptionThreadingNever | RB::OptionFormantPreserved,
            1.0 / speed, pitch);
        // R3 adjusts the spectral envelope before pitch resampling. Compensate for
        // pitch so the user's formant slider specifies an absolute envelope shift.
        engine.setFormantScale(std::pow(2.0, formantSemitones / 12.0) / pitch);
        engine.setExpectedInputDuration(frames);
        engine.setMaxProcessSize(BLOCK);
        std::vector<std::vector<float>> block(channels, std::vector<float>(BLOCK));
        std::vector<const float*> in(channels);
        std::vector<float*> out(channels);
        for (int c = 0; c < channels; ++c) { in[c] = block[c].data(); out[c] = block[c].data(); }
        const auto load = [&](size_t offset, size_t count) {
            for (size_t f = 0; f < count; ++f) for (int c = 0; c < channels; ++c)
                block[c][f] = samples[(offset + f) * channels + c] / 32768.0f;
        };
        for (size_t pos = 0; pos < frames; pos += BLOCK) {
            checkCancel(canceled);
            const size_t n = std::min(BLOCK, frames - pos);
            load(pos, n); engine.study(in.data(), n, pos + n == frames);
        }
        output.reserve(targetFrames * channels);
        const auto drain = [&]() {
            while (engine.available() > 0) {
                checkCancel(canceled);
                size_t n = engine.retrieve(out.data(), std::min(BLOCK, static_cast<size_t>(engine.available())));
                if (output.size() + n * channels > MAX_OUTPUT_SAMPLES)
                    throw std::runtime_error("Edited audio exceeds memory limit");
                for (size_t f = 0; f < n; ++f) for (int c = 0; c < channels; ++c) {
                    double value = std::isfinite(block[c][f]) ? block[c][f] * 32768.0 : 0;
                    output.push_back(static_cast<int16_t>(std::clamp(std::lround(value), -32768L, 32767L)));
                }
            }
        };
        for (size_t pos = 0; pos < frames; pos += BLOCK) {
            checkCancel(canceled);
            const size_t n = std::min(BLOCK, frames - pos);
            load(pos, n); engine.process(in.data(), n, pos + n == frames); drain();
        }
        drain();
        if (output.empty()) throw std::runtime_error("Audio processor produced no output");
    }
    if (effect != 0 && mix > 0) {
        std::vector<std::unique_ptr<klarinet::AudioEffect>> chain;
        if (effect == 1) {
            auto tremolo = std::make_unique<klarinet::Tremolo>();
            tremolo->setParameter(klarinet::TremoloParams::kRateHz, 40.f);
            tremolo->setParameter(klarinet::TremoloParams::kDepth, 1.f);
            chain.push_back(std::move(tremolo));
        } else if (effect == 2) {
            auto high = std::make_unique<klarinet::HighPassFilter>();
            high->setParameter(klarinet::HPFParams::kCutoffHz, 300.f);
            auto low = std::make_unique<klarinet::LowPassFilter>();
            low->setParameter(klarinet::LPFParams::kCutoffHz, std::min(3400.f, rate * .4f));
            chain.push_back(std::move(high)); chain.push_back(std::move(low));
        } else if (effect == 3) {
            auto chorus = std::make_unique<klarinet::Chorus>();
            chorus->setParameter(klarinet::ChorusParams::kRateHz, .8f);
            chorus->setParameter(klarinet::ChorusParams::kDepth, .6f);
            chorus->setParameter(klarinet::ChorusParams::kWetDryMix, .35f);
            auto delay = std::make_unique<klarinet::Delay>();
            delay->setParameter(klarinet::DelayParams::kTimeMs, 120.f);
            delay->setParameter(klarinet::DelayParams::kFeedback, .2f);
            delay->setParameter(klarinet::DelayParams::kWetDryMix, .2f);
            chain.push_back(std::move(chorus)); chain.push_back(std::move(delay));
        } else if (effect == 4) {
            auto delay = std::make_unique<klarinet::Delay>();
            delay->setParameter(klarinet::DelayParams::kTimeMs, 280.f);
            delay->setParameter(klarinet::DelayParams::kFeedback, .35f);
            delay->setParameter(klarinet::DelayParams::kWetDryMix, 1.f);
            chain.push_back(std::move(delay));
        } else if (effect == 5) {
            auto reverb = std::make_unique<klarinet::Reverb>();
            reverb->setParameter(klarinet::ReverbParams::kRoomSize, .85f);
            reverb->setParameter(klarinet::ReverbParams::kDamping, .35f);
            reverb->setParameter(klarinet::ReverbParams::kWetDryMix, 1.f);
            chain.push_back(std::move(reverb));
        } else if (effect == 6) {
            auto chorus = std::make_unique<klarinet::Chorus>();
            chorus->setParameter(klarinet::ChorusParams::kRateHz, .8f);
            chorus->setParameter(klarinet::ChorusParams::kDepth, .6f);
            chorus->setParameter(klarinet::ChorusParams::kWetDryMix, 1.f);
            chain.push_back(std::move(chorus));
        } else if (effect == 7) {
            auto flanger = std::make_unique<klarinet::Flanger>();
            flanger->setParameter(klarinet::FlangerParams::kRateHz, .4f);
            flanger->setParameter(klarinet::FlangerParams::kDepth, .7f);
            flanger->setParameter(klarinet::FlangerParams::kFeedback, .35f);
            flanger->setParameter(klarinet::FlangerParams::kWetDryMix, 1.f);
            chain.push_back(std::move(flanger));
        } else if (effect == 8) {
            auto phaser = std::make_unique<klarinet::Phaser>();
            phaser->setParameter(klarinet::PhaserParams::kRateHz, .5f);
            phaser->setParameter(klarinet::PhaserParams::kDepth, .7f);
            phaser->setParameter(klarinet::PhaserParams::kStages, 4.f);
            phaser->setParameter(klarinet::PhaserParams::kFeedback, .2f);
            chain.push_back(std::move(phaser));
        }
        for (auto& fx : chain) fx->prepare(rate, channels);
        std::vector<float> block(BLOCK * channels);
        for (size_t pos = 0; pos < output.size(); pos += block.size()) {
            checkCancel(canceled);
            size_t n = std::min(block.size(), output.size() - pos);
            for (size_t i = 0; i < n; ++i) block[i] = output[pos + i] / 32768.f;
            for (auto& fx : chain) fx->process(block.data(), n / channels, channels);
            for (size_t i = 0; i < n; ++i) {
                double value = output[pos + i] * (1 - mix) + block[i] * 32768.0 * mix;
                output[pos + i] = static_cast<int16_t>(std::clamp(std::lround(std::isfinite(value) ? value : 0), -32768L, 32767L));
            }
        }
    }
    return output;
}
}
