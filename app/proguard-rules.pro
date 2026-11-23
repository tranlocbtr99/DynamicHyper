# Proguard rules for Dynamic Hyper
-keep class io.github.d4viddf.hyperisland_kit.** { *; }
-keep class com.ltm.dynamichyper.** { *; }
-keep class com.google.gson.** { *; }
-keep class dev.chrisbanes.haze.** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn dev.chrisbanes.haze.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
