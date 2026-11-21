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

# Exceptions to that rule: classes Gson stores in the local cache from outside `data.model`.
# Without them R8 strips the generic type of `ServerVotes.ratings`, Gson rebuilds it as a list of
# LinkedTreeMap, and the task page crashed on its second opening (ClassCastException, beta
# emulator 2026-11-21, APK 0.48.0 n° 122). Renamed fields would also differ from one release to
# the next, so a cache written by one APK would read back empty in the next.
-keep class com.torchons.app.ui.taskdetail.ServerVotes { *; }
-keep class com.torchons.app.ui.taskdetail.TaskRating { *; }
-keep class com.torchons.app.core.sync.RejectedSyncOp { *; }
-keep enum com.torchons.app.core.sync.PendingOpKind { *; }

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keep class com.google.gson.** { *; }

# Release builds drop Log.d / Log.v calls (and their string arguments), so debug traces
# can never leak values from a shipped APK. Log.i/w/e stay: they carry no user values.
# Needs the optimizing default rules (proguard-android-optimize.txt, see build.gradle.kts).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
