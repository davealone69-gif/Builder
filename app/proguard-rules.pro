# SwarmBuilder release rules
# Keep this file intentionally minimal. R8/ProGuard can optimize the app
# while Android and the networking/JSON libraries retain their required metadata.

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn org.jetbrains.annotations.**
