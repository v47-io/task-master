-keepclasseswithmembers public class * {
    public static void main(java.lang.String[]);
}

-adaptkotlinmetadata

-keep class kotlin.Metadata { *; }

-dontobfuscate # To obfuscate remove this and the following line and use the commented line below
-keep,includecode,allowshrinking @kotlin.Metadata class ** { *; }
#-keep,includecode,allowobfuscation,allowshrinking @kotlin.Metadata class ** { *; }

-keepclassmembernames,allowoptimization,allowshrinking public class * {
    private <fields>;
    public synthetic <methods>;
    native <methods>;
}

-dontwarn kotlin.coroutines.experimental.intrinsics.**
-dontwarn kotlin.reflect.jvm.internal.**

-dontwarn ch.qos.logback.**
-dontwarn org.slf4j.*

-keep public class ch.qos.logback.** { *; }
