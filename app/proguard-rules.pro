# Keep reflection targets used by SystemProperties, Storage, OEM Hardware Probes & ZXing
-keep class android.os.SystemProperties { *; }
-keepclassmembers class com.sanepo.unitcheck.engine.** { *; }
-keepclassmembers class com.sanepo.unitcheck.model.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-dontwarn android.os.SystemProperties
-dontwarn android.hardware.biometrics.**
-dontwarn android.telephony.**
-dontwarn android.app.usage.StorageStatsManager
