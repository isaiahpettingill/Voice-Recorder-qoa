# SpeexDSP noise reduction regression tests

Run `scripts/test_native_noise.sh`. Upstream source hashes are verified first.
This compiles the same unmodified floating-point SpeexDSP C units as Android.
No network, microphone, model download or personal recordings are used.

Coverage: 8/16/22.05/44.1/48/96/192 kHz, mono/stereo, silence, empty/one-sample/
partial tails, arbitrary chunk-boundary equality, independent channels, exact
sample length, impulse timing after overlap-delay compensation, cancellation,
invalid configuration and finished-session refusal. Android JNI coverage lives
in `NativeNoiseReducerTest`, including >8M-sample streaming with fixed buffers.

The deterministic fixture is synthetic harmonic voiced audio with a changing
pitch and syllabic envelope, preceded by two seconds of noise for adaptation.
Reference host result: noise-only attenuation -11.77 dB; voiced output energy
98.2% of the clean synthetic fixture. These are regression checks, not a perceptual
speech benchmark or guarantees for a user's recording. The denoiser reduces
steady background noise; competing voices, music and transient noise can remain.
The on/off option is important when recording non-speech sounds.

Host AddressSanitizer and UndefinedBehaviorSanitizer pass the same suite.
LeakSanitizer cannot operate in the sandbox's ptrace environment; the sanitizer
run uses `ASAN_OPTIONS=detect_leaks=0`. Android CPU/device quality measurements
are not implied by these host results.
