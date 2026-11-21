# ONNX Runtime uses JNI to bind Java wrappers to native runtime symbols.
-keep class ai.onnxruntime.** { *; }

# Preserve runtime-visible annotations and generic signatures used by Android libraries.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
