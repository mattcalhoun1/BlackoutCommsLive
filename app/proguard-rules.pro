# Keep OSMDroid
-keep class org.osmdroid.** { *; }

# Keep Gson model classes
-keep class com.blackoutcomms.live.model.** { *; }
-keepclassmembers class com.blackoutcomms.live.model.** {
    <fields>;
}

# Keep USB serial driver classes
-keep class com.hoho.android.usbserial.** { *; }

# General Gson rules
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
