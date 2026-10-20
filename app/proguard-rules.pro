# Libsu Root Shell
-keep class com.topjohnwu.libsu.** { *; }
-dontwarn com.topjohnwu.libsu.**

# Model Data Partisi
-keep class id.dumper.parti.data.model.** { *; }

# OTA Decompressors
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# Kotlin Reflection & Metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
