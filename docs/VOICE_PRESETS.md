# Library-backed voice presets

The voice editor uses pinned, upstream **Rubber Band 4.0.0** for pitch, tempo and
formants, and the **Klarinet** DSP library for creative effects. The app's native
adapter handles PCM conversion, buffer lifecycle and cancellation; it does not
implement a pitch shifter, formant shifter, time stretcher, chorus or filter.

## Presets and tuning

- **Feminine:** +4.5 semitones pitch, +2 semitones absolute formant shift.
- **Masculine:** −4 semitones pitch, −2 semitones absolute formant shift.
- **Chipmunk:** +7 semitones pitch and formants.
- **Deep voice:** −7 semitones pitch, −3 semitones formants.
- Additional modulation, radio and 80s chorus/delay presets use Klarinet.
- **Sound FX:** Echo (280 ms feedback delay), Cave (Freeverb reverb),
  Chorus, Flanger and Phaser use Klarinet's unmodified upstream processors.
  Effect mix controls their strength; pitch, resonance and speed remain independent.
  These are conventional effects, not AI-generated sound effects. Delay/reverb tails
  remain inside the selected clip; no extra silence is appended.

The first three starting points follow the pitch/formant choices in
[TuxVoice's preset configurations](https://github.com/Behruzbek34/tuxvoice/blob/master/src/audio/dsp.rs).
We do **not** copy its custom DSP engine or promise identical sound. TuxVoice also
applies EQ to its feminine/masculine presets; those EQ settings are not reproduced
here. Deep voice and effect mix defaults are app configuration choices, not
vendor-certified voice models. No preset guarantees a gender identity, impersonates
an individual or turns every source voice into the same voice.

Choose a preset, then adjust pitch, resonance/formants, speed, volume and creative
mix separately. Reset voice preset restores its initial pitch/formants/mix/speed
without changing the selected trim or volume. Clean plus zero pitch/formants and
normal speed bypasses voice processing. Preset settings survive activity recreation.
Edits always render from the original and save a separate QOA copy.

Rubber Band's formant scale is relative to its pitch resampling stage. The adapter
converts an absolute requested formant displacement F and pitch displacement P to
`2^((F-P)/12)`, using the R3 engine where explicit formant control is supported.
Do not pass an absolute formant ratio directly or a pitch adjustment will move
resonances again. Pitch and formants are distinct controls.

## Recording with presets

Use **Record → Presets** before or during QOA recording. **Tune** changes pitch,
formants, speed and effect mix; Apply takes effect at the next accepted audio
buffer, Cancel keeps the previous settings. Changes during a pause start at the
next recorded sample rather than elapsed wall-clock time.

The microphone capture stays dry. The badge **Effects saved after recording**
means that Stop first saves the untouched original, then renders a separate QOA
copy using the recorded preset timeline. This is not live monitoring. Repeated
identical settings share one segment; genuine preset/tuning changes restart the
library processors, so echoes/reverb can end at those boundaries. Changed speed
alters that segment's duration in the effects copy, not the original.

The app persists the dry recovery file and sample-offset preset journal until
both outputs are saved. If rendering fails or is interrupted, **Retry pending
effects** on Record retries recovery into the selected recording folder.
Interrupted capture recovers complete encoded QOA blocks only; up to one
incomplete block can be lost. Over-budget recordings retain the original and
journal rather than silently dropping effects or overwriting audio. Other capture
formats must be switched to QOA to use recording presets.

## Limits and verification

This remains an offline render/preview editor, not a live microphone voice changer.
The existing 8-million-sample editing limit remains; capture/playback stream as
before. Preview latency depends on clip length and device CPU. No Pixel audio-quality,
Bluetooth latency or battery claim is made. Quality varies with source voice and
large pitch/formant shifts. Normalization is peak normalization, not LUFS.

Tests cover pitch/duration, stereo alignment, source immutability, bypass and
preset ranges; Android tests exercise the actual packaged native library. Listen
to your recording and tune the starting points instead of expecting a fixed
male/female conversion. Native source revisions, licenses and build provenance
are retained beside the vendored source.

## Voice cleanup and recording names

Noise reduction is **on by default**, including when upgrading from a version without
this option. The Record and Edit switches remember an explicit Off choice; changing
presets does not re-enable it. Turn it off for music, ambience, and sound effects you
want to preserve. Cleanup uses the pinned official **SpeexDSP 1.2.1** spectral speech
preprocessor (BSD-3-Clause), not a noise gate or our own denoising algorithm. Its
license and source notices are included in the APK. No model download, internet
permission, or uploaded recording is involved.

Processing order is trim → noise reduction → Rubber Band pitch/formants/tempo →
Klarinet creative effects → gain/normalization. Cleanup itself changes neither
pitch nor speed. It reduces background noise; it cannot remove every sound or
recover speech already obscured by noise. Supports 8–192 kHz mono/stereo. Other
sample rates fail explicitly; switch cleanup off for such imported files.

While recording, changes are saved at accepted audio-frame boundaries and applied
**after Stop**, not to live headphone monitoring. The dry QOA original remains
untouched, and the processed result is a separate `-effects.qoa` copy. Cleanup-only
recordings stream through bounded buffers without the editor's duration cap. If
pitch/tempo/creative effects exceed the existing editing limit, the original and
settings journal remain recoverable; cleanup is never silently skipped.

The Record page name field can be edited before or during QOA recording, including
while paused. Blank uses an automatic name. Names are single sanitized basenames,
`.qoa` is preserved, collisions receive a numeric suffix, and recovery retains the
chosen name. Other recording formats do not offer this QOA-specific field.

Verification includes deterministic synthetic voiced/noise, silence, stereo,
multiple sample-rate and partial-tail fixtures, native/JNI cancellation, streamed
recordings past the editing limit, and switch/settings recovery. Synthetic energy
measurements are regression checks, not human listening or speech-quality scores.

Normal Save keeps the existing foreground service and its notification alive until
publication/processing completes (the microphone is stopped first). OS force-stop
or process death can still interrupt work; reopen Record and use the pending-effects
retry control. The durable dry source and journal are retained until success.
