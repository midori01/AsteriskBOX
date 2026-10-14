-allowaccessmodification
-repackageclasses
-optimizationpasses 5

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Parameter checks only guard Kotlin-to-Kotlin calls; checkNotNull backs `!!` and must keep throwing.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(...);
}
