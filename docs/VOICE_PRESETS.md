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
