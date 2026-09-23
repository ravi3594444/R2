# Keep class and member names readable in crash reports; only unused code is removed.
-dontobfuscate

# sherpa-onnx's native library reads its Kotlin config classes and fields through JNI by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
