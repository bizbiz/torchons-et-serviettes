import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
}

// Config lue depuis local.properties (gitignore) avec fallback gradle
// property puis defaut. Helper unique pour toutes les cles `ts.*`.
val tsLocalProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun tsProp(key: String, fallback: String): String =
    tsLocalProps.getProperty(key)
        ?: providers.gradleProperty(key).orNull
        ?: fallback

// Deux backends : `prod` = serveur public, `beta` = serveur de TEST separe
// (backend + DB distincts, cf. docs/audit-roadmap-vs-code.md). Les cles
// `ts.api.base.url.prod` / `.beta` priment ; sinon fallback sur la cle
// legacy `ts.api.base.url` (le poste dogfood l'utilise encore) ; sinon
// defaut. Pas de gating user-level : l'appli beta est un APK separe
// (applicationId .beta) installe a cote de la prod.
val tsApiBaseUrlProd: String =
    tsProp("ts.api.base.url.prod",
        tsProp("ts.api.base.url", "https://api.torchonsetserviettes.app/api"))
val tsApiBaseUrlBeta: String =
    tsProp("ts.api.base.url.beta",
        tsProp("ts.api.base.url", "http://10.0.2.2:8002/api"))

// Email de contact dev — garde l'email reel hors du repo public.
val tsSupportEmail: String = tsProp("ts.support.email", "contact@torchonsetserviettes.app")

// Marqueur de build (beta uniquement) : SHA git court + date de build,
// affiche dans Reglages > "Note de version". Permet de verifier de visu
// QUEL build est installe (versionName seul ne bouge pas entre 2 builds
// dev sur la meme version). Fallback "nogit"/"" si git indisponible.
fun tsGitShortSha(): String = try {
    val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    p.inputStream.bufferedReader().readText().trim().ifEmpty { "nogit" }
} catch (e: Exception) {
    "nogit"
}
val tsBuildStamp: String =
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) +
        " · " + tsGitShortSha()

// Release signing (upload key Play). Lu depuis local.properties (gitignored) ;
// le .jks vit HORS du repo. Si absent (contributeurs / CI sans cle), les
// builds release sont NON signes (OK pour debug et builds non publies ;
// requis seulement pour uploader sur le Play Store).
val tsUploadKeystore: String = tsProp("ts.upload.keystore", "")
val tsUploadStorePw: String = tsProp("ts.upload.keystore.password", "")
val tsUploadKeyAlias: String = tsProp("ts.upload.key.alias", "upload")
val tsUploadKeyPw: String = tsProp("ts.upload.key.password", "")
val tsHasUploadKeystore: Boolean =
    tsUploadKeystore.isNotEmpty() && file(tsUploadKeystore).exists()

android {
    namespace = "com.torchons.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.torchons.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 91
        versionName = "0.35.17"

        // SUPPORT_EMAIL commun aux flavors. API_BASE_URL + TAILSCALE_AUTOLOGIN
        // sont definis PAR FLAVOR (prod = public sans Tailscale, beta = test).
        buildConfigField("String", "SUPPORT_EMAIL", "\"$tsSupportEmail\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Deux canaux installables cote a cote (applicationId distinct).
    // `prod` : APK public unique (cf. design-onboarding-public.md §6) —
    //          Tailscale auto-login DESACTIVE.
    // `beta` : canal de test (.beta), backend de test separe, Tailscale
    //          auto-login ACTIF pour le dogfood. « Certains users » = ceux
    //          qui installent l'APK beta / sont dans le canal Closed Testing.
    flavorDimensions += "channel"
    productFlavors {
        create("prod") {
            dimension = "channel"
            resValue("string", "app_name", "Torchons & Serviettes")
            buildConfigField("String", "API_BASE_URL", "\"$tsApiBaseUrlProd\"")
            buildConfigField("boolean", "TAILSCALE_AUTOLOGIN", "false")
            // Pas de marqueur de build en prod public (release reproductible).
            buildConfigField("String", "BUILD_INFO", "\"\"")
        }
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            resValue("string", "app_name", "T&S Beta")
            buildConfigField("String", "API_BASE_URL", "\"$tsApiBaseUrlBeta\"")
            buildConfigField("boolean", "TAILSCALE_AUTOLOGIN", "true")
            buildConfigField("String", "BUILD_INFO", "\"$tsBuildStamp\"")
        }
    }

    signingConfigs {
        // Upload key Play : creds dans local.properties (gitignored), keystore
        // hors repo. Cree seulement si le keystore est present, pour ne pas
        // casser les builds des contributeurs / la CI sans la cle.
        if (tsHasUploadKeystore) {
            create("release") {
                storeFile = file(tsUploadKeystore)
                storePassword = tsUploadStorePw
                keyAlias = tsUploadKeyAlias
                keyPassword = tsUploadKeyPw
            }
        }
    }

    buildTypes {
        release {
            if (tsHasUploadKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // Hilt - Injection de dependances
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // Retrofit - Appels reseau
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Room - Base de donnees locale (pour sync offline futur)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    // DataStore - Stockage du token JWT
    implementation(libs.datastore.preferences)

    // Gson
    implementation(libs.gson)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Widget Glance
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // WorkManager pour notifications locales
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Konfetti — animation de confetti pour les felicitations apres validation
    // d'une tache. Lib populaire et legere (~50ko), bien integree avec Compose.
    implementation("nl.dionsegijn:konfetti-compose:2.0.5")

    // v0.30 — onboarding sans compte
    // Quickie : scan QR via CameraX + ML Kit, API Compose-friendly
    // (registerForActivityResult avec ScanQRCode contract).
    implementation(libs.quickie.bundled)
    // QRCode-Kotlin : generation de QR pure Kotlin pour l'ecran d'invitation.
    implementation(libs.qrcode.kotlin)

    // v0.30.x — Google Play Billing (plomberie premium, pas de paywall).
    // Usage : restoration silencieuse des achats existants au cold start +
    // hook pour lancer le flow d'achat plus tard.
    implementation(libs.billing.ktx)
}
