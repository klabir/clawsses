# Rokid's CXR-M AAR contains JNI entry points and supplies no consumer keep rules. Preserve the
# vendor bridge boundary while allowing the rest of the phone application to be optimized.
-keep class com.rokid.cxr.** { *; }
-keep class com.rokid.sprite.aiapp.externalapp.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# sherpa-onnx reads its Kotlin configuration objects by exact JNI field name. Its official AAR
# currently ships an empty consumer rule file, so retain this narrow native boundary explicitly.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Gson constructs these request/response models from fields carrying @SerializedName. Keep the
# annotated field names and generic signatures, while allowing unrelated application code to shrink.
-keepattributes Signature,*Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Android components are normally retained from the manifest by AGP. Keep their public lifecycle
# entry points explicit because this release is the first phone artifact to enable full R8 mode.
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
