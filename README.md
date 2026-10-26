# Voice Recorder QOA

An offline fork of [Fossify Voice Recorder](https://github.com/FossifyOrg/Voice-Recorder), with QOA recording and sound editing.

- Android application ID: `us.josemancharo.voicerecorderqoa`.
- Installs alongside Fossify Voice Recorder; it cannot automatically access the original app’s private recordings. Export or copy recordings before removing the original app.
- Signed APKs are published to [GitHub Releases](https://github.com/isaiahpettingill/Voice-Recorder-qoa/releases) after successful main-branch builds. Obtainium can follow this repository.
- No internet permission is added. All recording, playback, decoding and effects run on the device.
- QOA is a lossy audio format. Keep originals when editing; use **Save copy** and export WAV for players that do not support QOA.

## Recording and editing

QOA is selected by default. Recordings can be played directly in the app; import uses Android-supported audio formats and does not require FFmpeg. To edit a recording, choose Edit sound. Preview effects, reset/cancel, then **Save QOA copy** or **Export WAV**; the source recording is never overwritten.

Controls include trim, volume, peak normalization, independent pitch and tempo, robot/radio effects and an 80s chorus-plus-slapback preset. Normalization is peak normalization, not LUFS loudness matching. Pitch and tempo use basic WSOLA processing, not studio-quality mastering.

Editing currently accepts up to 8 million individual channel samples: about 167 seconds of 48 kHz mono or 83 seconds of stereo. Output is capped at 16 million channel samples. Recording and playback stream instead and do not share this short-clip editing limit; available disk space and the WAV 4 GB format limit still apply.

If saving fails after a recording is finalized, the recovery menu can export the intact finalized recovery QOA. This is not a guarantee of recovery after an abrupt device or process crash. Keep important original recordings backed up.

## Build and releases

Use JDK 17 and Android SDK 36, then `./gradlew testFossDebugUnitTest lintFossDebug assembleFossDebug`. Debug builds have the `.debug` suffix. Release builds use the FOSS flavor.

Pull requests run tests/lint, debug and unsigned minified-release builds, and Android emulator checks without signing secrets. Pushes to this fork’s `main` branch (including merges), and manual runs on `main`, publish only after validation succeeds. Release versions are `2.0.<workflow run number>` and version codes are `1000 + run number`; retries retain the same version. Do not reset this workflow’s run sequence or switch to a lower code after publishing. The release verifies package ID, version, signing certificate, absence of debug/internet flags and uploaded checksums before publishing a draft. Existing mismatching assets or tags are refused, never overwritten.

Create a permanent PKCS12 signing key on your own machine. Keep an encrypted backup and save its password separately: losing the key prevents updates to installed copies. Never commit a signing key or paste its password into a command. Configure these repository **Actions secrets**:

| Secret | Value |
| --- | --- |
| `QOA_KEYSTORE_BASE64` | Base64 of the PKCS12 file |
| `QOA_STORE_PASSWORD` | Keystore password |
| `QOA_KEY_ALIAS` | Key alias (recommended `voicerecorderqoa`) |
| `QOA_KEY_PASSWORD` | Key password (same password for PKCS12) |

The workflow maps these to the existing `SIGNING_*` Gradle variables and deletes its temporary keystore even on failure. If secrets are missing, tests still work but publication fails closed; no debug-signed release is substituted. Local release signing may also use the existing `keystore.properties_sample` structure.

A changed app identifier means an existing Fossify APK is **not** an update target. Future releases of this fork must keep this identifier and certificate.

## Fork identity compatibility

Fossify Commons 7.1.3 assumes the official `org.fossify.` package prefix and its reversed brand string in `BaseSimpleActivity`. This fork has a deliberately different app ID. A narrowly scoped AGP bytecode adapter replaces only those two exact branding literals in that class’s `onCreate` and `startCustomizationActivity` methods, so a legitimate renamed fork does not show a misleading fake-app warning or lose customization after repeated launches. It does not remove permission, storage, microphone, authentication, or other security checks; all branches remain intact. Other classes and string constants are untouched. Tests enforce that scope, and the build refuses a Commons version change until the adapter is reviewed. The original library, source namespaces, license and attribution are retained.

## Upstream attribution

This fork retains Fossify’s license and credits. The upstream documentation below describes the original app and distribution channels, not this fork’s release artifacts.

---

# Fossify Voice Recorder

<img alt="Logo" src="graphics/icon.webp" width="120" />

<a href="https://play.google.com/store/apps/details?id=org.fossify.voicerecorder"><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height=80/></a> <a href="https://f-droid.org/packages/org.fossify.voicerecorder/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on-en.svg" alt="Get it on F-Droid" height=80/></a> <a href="https://apt.izzysoft.de/fdroid/index/apk/org.fossify.voicerecorder"><img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height=80/></a>

Introducing Fossify Voice Recorder – where capturing crystal-clear audio and preserving precious moments is effortless and enjoyable. Seamlessly blend
simplicity with functionality as you embark on a journey of seamless recording experiences tailored to your needs.

**🔊 HIGH-QUALITY AUDIO CAPTURE:**  
Remember every word, every note, and every emotion with pristine audio quality. Fossify Voice Recorder empowers you to capture high-fidelity recordings
effortlessly, ensuring that every detail is preserved with clarity and precision.

**🎙️ VERSATILE RECORDING OPTIONS:**  
From voice memos to musical inspirations, this intuitive app transforms your device into a versatile recording studio. Explore the freedom to document your
surroundings and unleash your creativity with ease.

**🚀 NO-FUSS FUNCTIONALITY:**  
Enjoy a clutter-free experience with Fossify Voice Recorder. Say goodbye to unnecessary features and hello to a streamlined interface designed for intuitive
navigation and seamless recording.

**📊 INTUITIVE VISUALIZATION:**  
Immerse yourself in the recording process with real-time sound volume visualization. Experience the thrill of monitoring your recordings with a sleek,
interactive display that enhances your recording experience.

**🔒 PRIVACY-FIRST APPROACH:**  
Rest easy knowing that your privacy is our priority. Fossify Voice Recorder operates offline, ensuring maximum privacy, security, and stability without the need
for internet access. Your recordings remain confidential and under your control at all times.

**🎨 CUSTOMIZABLE INTERFACE:**  
Personalize your recording experience with customizable colors and themes. Embrace the sleek elegance of material design and dark theme options, offering a
visually stunning experience tailored to your preferences.

**🤝 USER-FRIENDLY FEATURES:**  
Discover intuitive functionalities like customizable filename formats and practical widgets for quick recordings. With Fossify Voice Recorder, the power to
record is in your hands.

**🌐 AD-FREE & OPEN-SOURCE:**  
Say goodbye to intrusive ads and unnecessary permissions. Fossify Voice Recorder is ad-free, fully open-source, and grants you the freedom to use the app as you
please, without compromise.

Capture moments, preserve memories, and unleash your creativity with Fossify Voice Recorder. Download now and experience recording like never before.

➡️ Explore more Fossify apps: https://www.fossify.org<br>
➡️ Open-Source Code: https://www.github.com/FossifyOrg<br>
➡️ Join the community on Reddit: https://www.reddit.com/r/Fossify<br>
➡️ Connect on Telegram: https://t.me/Fossify

<div align="center">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" width="30%">
</div>
