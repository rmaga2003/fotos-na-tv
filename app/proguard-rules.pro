# NanoHTTPD usa reflexao interna minima; mantemos as classes publicas.
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Interface JS <-> Kotlin do WebView
-keepclassmembers class com.exemplo.fotostv.** {
    @android.webkit.JavascriptInterface <methods>;
}
