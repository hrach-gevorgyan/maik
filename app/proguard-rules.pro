# LiteRT-LM calls back into Kotlin from native code by class and method name, so
# nothing in the runtime package may be renamed or removed.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class * {
    native <methods>;
}
# The runtime reads Kotlin metadata at run time to find what it calls.
-keep class kotlin.Metadata { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Chat history is stored as JSON. The serialization plugin generates the
# serializers; keep them and the classes they describe.
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class com.maik.app.**$$serializer { *; }
-keepclassmembers class com.maik.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.maik.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Readable stack traces from shipped builds.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
