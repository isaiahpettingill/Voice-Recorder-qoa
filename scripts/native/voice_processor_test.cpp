#include "VoiceProcessor.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <stdexcept>

static double magnitude(const std::vector<int16_t>& pcm, int rate, double hz) {
    double real=0, imag=0; size_t start=pcm.size()/4, end=pcm.size()*3/4;
    for (size_t i=start;i<end;++i) { double phase=2*M_PI*hz*i/rate; real+=pcm[i]*cos(phase); imag+=pcm[i]*sin(phase); }
    return std::hypot(real,imag)/(end-start);
}
static double peak(const std::vector<int16_t>& pcm,int rate,int low,int high,int step) {
    double best=-1; int found=0;
    for(int hz=low;hz<=high;hz+=step) { double m=magnitude(pcm,rate,hz); if(m>best){best=m;found=hz;} }
    return found;
}
int main() {
    const int rate=16000;
    std::vector<int16_t> sine(rate);
    for(size_t i=0;i<sine.size();++i) sine[i]=static_cast<int16_t>(10000*sin(2*M_PI*220*i/rate));
    assert(voice::process(sine,rate,1,0,0,1,0,0)==sine);
    auto pitched=voice::process(sine,rate,1,12,0,1,0,0);
    assert(std::abs(static_cast<long>(pitched.size())-rate)<160);
    assert(peak(pitched,rate,400,480,5)==440);
    auto faster=voice::process(sine,rate,1,0,0,2,0,0);
    assert(std::abs(static_cast<long>(faster.size())-rate/2)<160);
    assert(peak(faster,rate,200,240,5)==220);
    auto slower=voice::process(sine,rate,1,0,0,.5,0,0);
    assert(std::abs(static_cast<long>(slower.size())-rate*2)<160);
    // Harmonic test vowel with an envelope around 1 kHz, not a recorded voice.
    std::vector<int16_t> vowel(rate*2);
    for(size_t i=0;i<vowel.size();++i){ double v=0; for(int h=1;h<=40;++h) { double hz=h*100; double a=exp(-pow((hz-1000)/220,2)); v+=a*sin(2*M_PI*hz*i/rate); } vowel[i]=static_cast<int16_t>(v*3000); }
    auto up=voice::process(vowel,rate,1,0,6,1,0,0);
    auto down=voice::process(vowel,rate,1,0,-6,1,0,0);
    auto preserved=voice::process(vowel,rate,1,12,0,1,0,0);
    double upPeak=peak(up,rate,500,2000,100), downPeak=peak(down,rate,400,1400,100), keepPeak=peak(preserved,rate,600,2200,200);
    std::cout<<"formant peaks up="<<upPeak<<" down="<<downPeak<<" preserved="<<keepPeak<<"\n";
    assert(upPeak>=1200 && downPeak<=900 && keepPeak<=1400);
    for(int effect=1;effect<=8;++effect){ assert(voice::process(sine,rate,1,0,0,1,effect,0)==sine); auto wet=voice::process(sine,rate,1,0,0,1,effect,1); assert(wet.size()==sine.size() && wet!=sine); }
    // Creative effects are upstream algorithms: zero mix is exact bypass, silence
    // stays silent, and state never leaks between stereo channels.
    std::vector<int16_t> silence(rate * 2, 0), stereo(rate * 2, 0);
    for (int i=0; i<rate; ++i) stereo[i*2]=sine[i];
    for (int effect=4; effect<=8; ++effect) {
        assert(voice::process(silence,rate,2,0,0,1,effect,1)==silence);
        auto wet=voice::process(stereo,rate,2,0,0,1,effect,.5);
        assert(wet.size()==stereo.size() && wet!=stereo);
        // Reverb deliberately spreads stereo; other effects retain channel isolation.
        if (effect!=5) for(size_t i=1;i<wet.size();i+=2) assert(wet[i]==0);
    }
    std::vector<int16_t> impulse(rate,0); impulse[0]=16000;
    auto echo=voice::process(impulse,rate,1,0,0,1,4,1);
    assert(std::abs(echo[rate*280/1000+1])>1000); // configured 280 ms delay
    auto cave=voice::process(impulse,rate,1,0,0,1,5,1);
    long long tail=0; for(size_t i=rate/20;i<cave.size();++i) tail+=std::abs(cave[i]);
    assert(tail>1000); // real reverb decay rather than a gain-only placeholder
    bool rejected=false; try { voice::process(sine,rate,1,13,0,1,0,0); } catch(const std::invalid_argument&){rejected=true;} assert(rejected);
    bool canceled=false; try { voice::process(sine,rate,1,3,2,1,0,0,[]{return true;}); } catch(const std::runtime_error&){canceled=true;} assert(canceled);
    std::cout<<"Native library-backed voice tests passed\n";
}
