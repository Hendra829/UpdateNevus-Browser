# Keep JavascriptInterface methods reachable from WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep public NevusBridge API surface stable for release builds
-keep class com.nevus.mediabridge.bridge.NevusMediaBridge {
    public *;
}

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.nevus.mediabridge.**$$serializer { *; }
-keepclassmembers class com.nevus.mediabridge.** {
    *** Companion;
}
-keepclasseswithmembers class com.nevus.mediabridge.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retain source line info for meaningful crash reports; then strip file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
