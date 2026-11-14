# Keep TFLite / OkHttp / org.json
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn okhttp3.**
-dontwarn okio.**
