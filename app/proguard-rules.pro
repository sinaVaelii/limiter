# Proguard rules for Limiter
# Minification is currently disabled (isMinifyEnabled = false)
# These rules ensure critical components and data models are preserved if shrinking is ever enabled.

# Keep Device Admin receiver
-keep class com.limiter.app.admin.LimiterAdminReceiver { *; }

# Keep models serialized to DataStore
-keep class com.limiter.app.model.** { *; }

# Kotlin coroutines and reflection
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
