# Regles ProGuard / R8 pour Torchons & Serviettes
#
# Ce fichier est court parce qu'il n'a pas a etre long : mesure du 2026-09-03,
# la configuration effectivement appliquee fait 1123 lignes, dont la quasi
# totalite vient des bibliotheques elles-memes (Room garde ses RoomDatabase,
# Gson garde les sous-classes de TypeToken, Retrofit / Hilt / Dagger apportent
# les leurs). Elle se relit apres un build :
#   android/app/build/outputs/mapping/prodRelease/configuration.txt
#
# Avant d'ajouter une regle ici, verifier qu'elle n'y est pas deja — une regle
# redondante ne casse rien mais laisse croire qu'elle protege quelque chose.

# Conserver les modeles de donnees pour Gson
#
# Rule: every class Gson serialises lives in `data.model`. Outside this package
# R8 renames fields (measured 2026-09-21 on the v96 mapping: 2639 renamed
# fields elsewhere, zero here) and Gson then reads and writes obfuscated keys --
# no crash, no log, just empty fields.
-keep class com.torchons.app.data.model.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keep class com.google.gson.** { *; }
