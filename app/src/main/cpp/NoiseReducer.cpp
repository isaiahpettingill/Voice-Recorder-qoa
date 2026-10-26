#include "NoiseReducer.h"
#include "speex/speex_preprocess.h"
#include <algorithm>
#include <stdexcept>
namespace voice {
NoiseReducer::NoiseReducer(int rate, int channels) : channels_(channels), block_(rate / 50) {
    if (rate < 8000 || rate > 192000 || channels < 1 || channels > 2)
        throw std::invalid_argument("Noise reduction supports 8–192 kHz mono/stereo PCM");
    mono_.resize(block_);
    pending_.reserve(block_ * channels_);
    states_.reserve(channels_);
    try {
        for (int ch = 0; ch < channels_; ++ch) {
            auto* state = speex_preprocess_state_init(block_, rate);
            if (!state) throw std::bad_alloc();
            states_.push_back(state);
            int on = 1, off = 0, suppression = -20;
            speex_preprocess_ctl(state, SPEEX_PREPROCESS_SET_DENOISE, &on);
            speex_preprocess_ctl(state, SPEEX_PREPROCESS_SET_AGC, &off);
            // VAD defaults off. Do not invoke its deprecated ctl (logs warning).
            speex_preprocess_ctl(state, SPEEX_PREPROCESS_SET_DEREVERB, &off);
            speex_preprocess_ctl(state, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &suppression);
        }
    } catch (...) {
        for (auto* state : states_) speex_preprocess_state_destroy(state);
        throw;
    }
}
NoiseReducer::~NoiseReducer() {
    for (auto* state : states_) speex_preprocess_state_destroy(state);
}
void NoiseReducer::frame(std::vector<int16_t>& output, const std::function<bool()>& canceled) {
    if (canceled()) throw std::runtime_error("Noise reduction canceled");
    for (int ch = 0; ch < channels_; ++ch) {
        for (int i = 0; i < block_; ++i) mono_[i] = pending_[i * channels_ + ch];
        speex_preprocess_run(states_[ch], mono_.data());
        for (int i = 0; i < block_; ++i) pending_[i * channels_ + ch] = mono_[i];
    }
    // Speex 1.2.1 uses N3=frame_size overlap; first output is pre-roll.
    if (primed_) {
        const size_t count = static_cast<size_t>(std::min<uint64_t>(pending_.size(), accepted_ - emitted_));
        output.insert(output.end(), pending_.begin(), pending_.begin() + count);
        emitted_ += count;
    }
    primed_ = true;
    pending_.clear();
}
std::vector<int16_t> NoiseReducer::process(const std::vector<int16_t>& input,
                                         const std::function<bool()>& canceled) {
    if (finished_) throw std::logic_error("Noise reduction session finished");
    if (input.size() > 65536 || input.size() % channels_ != 0)
        throw std::invalid_argument("Use aligned PCM chunks of at most 65536 samples");
    if (canceled()) throw std::runtime_error("Noise reduction canceled");
    accepted_ += input.size();
    std::vector<int16_t> output;
    output.reserve(input.size() + block_ * channels_);
    size_t offset = 0;
    while (offset < input.size()) {
        size_t count = std::min(input.size() - offset, block_ * channels_ - pending_.size());
        pending_.insert(pending_.end(), input.begin() + offset, input.begin() + offset + count);
        offset += count;
        if (pending_.size() == static_cast<size_t>(block_ * channels_)) frame(output, canceled);
    }
    return output;
}
std::vector<int16_t> NoiseReducer::finish(const std::function<bool()>& canceled) {
    if (finished_) throw std::logic_error("Noise reduction session finished");
    std::vector<int16_t> output;
    while (emitted_ < accepted_) {
        pending_.resize(block_ * channels_, 0);
        frame(output, canceled);
    }
    finished_ = true;
    return output;
}
}
