# Pinned upstream audio engines

No upstream DSP algorithm is rewritten by this app. VoiceProcessor.cpp only
converts PCM, configures parameters, feeds/drains blocks and mixes dry/wet audio.

* Rubber Band 4.0.0: https://github.com/breakfastquay/rubberband
  commit `1d95888bec3ae0a17c0c4af791810d5a63f6bc35` (tag v4.0.0).
  The unmodified `src`, `rubberband`, `single` directories and COPYING are included.
  GPL-2.0-or-later, distributed here under the app's GPL-3.0 obligations.
  The official single compilation unit selects its built-in FFT and resampler.
* Klarinet: https://github.com/vectencia/Klarinet
  commit `df3b3f58e20d93c5c528f1a5a52db9ee527bc845`.
  The unmodified `klarinet/src/cpp/dsp` directory and LICENSE are included.
  Apache-2.0, compatible with this GPL-3.0 application. Only Tremolo, HPF/LPF,
  Chorus, Delay and required primitives are compiled, not the Android SDK/Oboe.

Rubber Band's R3 offline processor owns independent time, pitch and formants.
The requested absolute formant ratio is divided by pitch ratio before
setFormantScale, because R3 applies envelope correction before pitch resampling.
Robot is an amplitude-modulation effect (Klarinet Tremolo at 40 Hz), not a vocoder.
Radio is a band-limited filter; 80s is chorus/slapback delay. Presets are starting
points, not claims to transform every speaker's perceived sex or identity.

* SpeexDSP 1.2.1: https://github.com/xiph/speexdsp
  commit `1b28a0f61bc31162979e1f26f3981fc3637095c8` (SpeexDSP-1.2.1).
  Unmodified include/libspeexdsp directories and COPYING; only generated
  include/speex/speexdsp_config_types.h is supplied for stdint Android/host types.
  BSD-3-Clause. Floating point preprocessor with upstream Kiss FFT, no downloaded
  weights. Spectral speech noise reduction at -20 dB maximum suppression;
  AGC, VAD and dereverb disabled. Each channel has its own ~20 ms state; no
  resampling, tempo/pitch change, microphone permission or network requirement.
  Our adapter compensates one analysis-frame delay and pads/drains the final
  partial frame, preserving the exact sample count and channel ordering.
  This reduces steady background noise, not all background sounds or voices.
