# Independent QOA fixtures

These deterministic PCM fixtures were encoded and decoded with the official MIT-licensed
phoboslab/qoa `qoa.h`, revision `bc0589710ff6aa2f50e08fb4d0a815d567bc0f3e`:
https://github.com/phoboslab/qoa/blob/bc0589710ff6aa2f50e08fb4d0a815d567bc0f3e/qoa.h
SHA-256 of reference header: 7b2396063eb960b10001db7db28ba25e284d4e772191968012e612dc8834952c.

`.pcm` files are original signed little-endian 16-bit samples; `.qoa` files are official
encoder outputs; `.qoa.pcm` files are official decoder outputs. All use 48,000 Hz.
Mono/tiny are mono; stereo/silence have two channels. Frame counts: 5237, 3, 10321, 43.
They cover multiframe, stereo interleaving, short final slices, and silence.

Fixture generation used seed 42; each channel sample advances unsigned32 seed by
`seed = seed * 1664525 + 1013904223`. Sample at frame i/channel c is integer-truncated
`12000 * sin(i * 2*pi * (440 + c*127) / 48000) + (seed % 4001 - 2000)`.
Silence is all zero. No microphone/user recording data is included.

The tests require byte-exact independent encoding and decoding, not merely round trips.
