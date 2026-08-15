# kotlinx.serialization keeps generated serializers reachable via companion objects.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.amarhelper.console.** {
    *** Companion;
}
-keepclasseswithmembers class com.amarhelper.console.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit interfaces are referenced reflectively.
-keep,allowobfuscation interface com.amarhelper.console.data.remote.**
-keepattributes Signature, Exceptions
