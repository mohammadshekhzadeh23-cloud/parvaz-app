# Keep everything from the Xray-core Go bindings; obfuscating these breaks JNI lookups.
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn libv2ray.**
-dontwarn go.**
