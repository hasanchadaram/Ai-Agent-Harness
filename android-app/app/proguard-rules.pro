# Keep kotlinx serialization classes
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# ONNX Runtime's native code looks up Java classes and constructors by name.
# The AAR's consumer rules do not cover all of these JNI lookups in minified builds.
-keep class ai.onnxruntime.** { *; }
