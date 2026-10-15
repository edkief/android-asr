# sherpa-onnx exposes its native callbacks through com.k2fsa.sherpa.onnx.
# JNI looks classes up by their fully-qualified name, so R8/ProGuard must
# keep every class, field and method in that package, including native ones.
-keep class com.k2fsa.sherpa.onnx.** { *; }
