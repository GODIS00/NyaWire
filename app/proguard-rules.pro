# Persisted Gson fields, app entry points and test-visible contracts retain their names.
# Most APK savings come from unused Compose icons and dependency code.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep,allowoptimization class com.gdhapp.client.** { *; }
# JNI bridges also ship these rules in their AARs; keep them explicit for future core updates.
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keep class hev.htproxy.** { *; }
# Preserve shared public contracts used by Android instrumentation and native adapters.
# Compose and its large icon catalogue remain fully shrinkable.
-keep,allowoptimization class androidx.tracing.** { public *; protected *; }
-keep,allowoptimization class androidx.lifecycle.** { public *; protected *; }
-keep,allowoptimization class androidx.activity.** { public *; protected *; }
-keep,allowoptimization class kotlin.** { public *; protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public *; protected *; }
-keep,allowoptimization class com.google.gson.** { public *; protected *; }
-keep,allowoptimization class okhttp3.** { public *; protected *; }
-keep,allowoptimization class okio.** { public *; protected *; }

