# Keep reflection targets used by SystemProperties, Storage, and OEM Hardware Probes
-keep class android.os.SystemProperties { *; }
-keepclassmembers class com.sanepo.unitcheck.engine.** { *; }
-keepclassmembers class com.sanepo.unitcheck.model.** { *; }
-dontwarn android.os.SystemProperties
-dontwarn android.hardware.biometrics.**
-dontwarn android.telephony.**
