# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Wearable Data Layer relies on reflection in places.
-keep class com.google.android.gms.wearable.** { *; }
