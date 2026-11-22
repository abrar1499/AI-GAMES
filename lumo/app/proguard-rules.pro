-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class com.lumo.app.data.** { *; }
-keepattributes Signature, *Annotation*

# MediaPipe gesture recognizer (reflection + protobuf).
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
# MediaPipe logs through Flogger, which finds its backend by class name at startup.
# Renaming it makes MediaPipe crash on first use, so keep Flogger as is.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
