# Public releases must not retain application-owned Android log calls. Hardware-test releases keep
# diagnostics because the Rokid vendor boundary can only be validated from device logs.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
