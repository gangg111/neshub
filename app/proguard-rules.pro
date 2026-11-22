# Native methods are looked up by name from C, so keep every class that declares one.
-keepclasseswithmembernames class * {
    native <methods>;
}
