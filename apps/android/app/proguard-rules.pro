# Preserve JNI method signatures so the Rust FFI bridge can find them at runtime.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep the full NativeGbcam bridge class and the Progress callback interface
# that the native side calls into via JNI.
-keep class fyi.r0.gbxcam.NativeGbcam { *; }
-keep interface fyi.r0.gbxcam.NativeGbcam$Progress { *; }

# Keep all classes (including R8-synthesized lambda wrappers) that implement
# Progress — the native side holds a jobject reference and calls onProgress()
# via JNI, so R8 must not merge or remove these implementations.
-keep class * implements fyi.r0.gbxcam.NativeGbcam$Progress { *; }
