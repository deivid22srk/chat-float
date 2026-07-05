# Keep Ktor / kotlinx.serialization
-keep class io.ktor.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontwarn kotlinx.serialization.**
-dontwarn io.ktor.**
