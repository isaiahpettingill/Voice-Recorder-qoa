#pragma once
#include <cstdint>
#include <functional>
#include <vector>
struct SpeexPreprocessState_;
namespace voice {
// PCM framing/latency adapter only. All noise estimation/filtering is SpeexDSP.
class NoiseReducer {
public:
    NoiseReducer(int rate, int channels);
    ~NoiseReducer();
    NoiseReducer(const NoiseReducer&) = delete;
    NoiseReducer& operator=(const NoiseReducer&) = delete;
    std::vector<int16_t> process(const std::vector<int16_t>& input,
        const std::function<bool()>& canceled = [] { return false; });
    std::vector<int16_t> finish(const std::function<bool()>& canceled = [] { return false; });
private:
    void frame(std::vector<int16_t>& output, const std::function<bool()>& canceled);
    int channels_, block_;
    std::vector<SpeexPreprocessState_*> states_;
    std::vector<int16_t> pending_, mono_;
    uint64_t accepted_ = 0, emitted_ = 0;
    bool primed_ = false, finished_ = false;
};
}
