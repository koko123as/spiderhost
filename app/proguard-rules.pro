# Host classes that downloaded spider jars link against by name. Never rename or strip them.
-keep class com.github.catvod.** { *; }
-keep class com.whl.quickjs.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.google.gson.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn **
