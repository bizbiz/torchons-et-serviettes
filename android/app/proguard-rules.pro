# Regles ProGuard pour Torchons & Serviettes
# Ajouter ici les regles specifiques au projet si necessaire

# Conserver les modeles de donnees pour Gson
-keep class com.torchons.app.data.model.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
