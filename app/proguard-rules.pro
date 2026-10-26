# EventBus
-keepattributes *Annotation*
-keepclassmembers class ** {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

-keep class androidx.compose.runtime.** { *; }

# JNI entry point used by the offline library-backed voice renderer.
-keep class org.fossify.voicerecorder.audio.NativeVoiceProcessor { *; }
