# R8 rules for every Cabinwright app (build-app.sh). Full mode: shrink, optimize, obfuscate.
-allowaccessmodification
-repackageclasses
# readable stack traces with the .map next to the APK (retrace)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Kotlin's stdlib refers to JDK classes Android 4.4 lacks; R8 removes what's unused
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
# Kotlin: the metadata only matters to reflection, which apps don't use
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull(...);
    static void checkNotNullParameter(...);
    static void checkNotNullExpressionValue(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkParameterIsNotNull(...);
}
