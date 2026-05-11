-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class live.agor.app.**$$serializer { *; }
-keepclassmembers class live.agor.app.** { *** Companion; }
-keepclasseswithmembers class live.agor.app.** { kotlinx.serialization.KSerializer serializer(...); }

-keepattributes *Annotation*, InnerClasses
-keep class io.socket.** { *; }
-keep class okhttp3.** { *; }
-keep class retrofit2.** { *; }

-keep class live.agor.app.voice.WhisperJni { *; }
