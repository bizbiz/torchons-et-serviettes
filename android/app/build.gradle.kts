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
// (backend + DB distincts). Les cles
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

// Donation page (Buy Me a Coffee). Empty by default: the button stays hidden until the account
// exists, so an announced but unclaimed name cannot be taken by someone else. Set
// `ts.donate.url=...` in local.properties (gitignored) once the account is created.
val tsDonateUrl: String = tsProp("ts.donate.url", "")

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

// Hoisted out of defaultConfig so `archiveR8Mapping` can name its output after
// the build it belongs to: a mapping file without its versionCode is useless.
// 96: first minified build installed on a phone. 95 names two different APKs
// (non-minified on 09-02, minified on 09-03), which only their size told apart.
val tsVersionCode = 121
val tsVersionName = "0.47.0"

android {
    namespace = "com.torchons.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.torchons.app"
        minSdk = 26
        targetSdk = 36
        versionCode = tsVersionCode
        versionName = tsVersionName

        // SUPPORT_EMAIL commun aux flavors. API_BASE_URL est defini PAR FLAVOR
        // (prod = public, beta = test).
        buildConfigField("String", "SUPPORT_EMAIL", "\"$tsSupportEmail\"")
        buildConfigField("String", "DONATE_URL", "\"$tsDonateUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Deux canaux installables cote a cote (applicationId distinct).
    // `prod` : APK public unique.
    // `beta` : canal de test (.beta), backend de test separe. « Certains
    //          users » = ceux qui installent l'APK beta / sont dans le canal
    //          Closed Testing. L'auto-login par IP Tailscale, propre a la beta,
    //          a ete retire le 2026-10-05.
    flavorDimensions += "channel"
    productFlavors {
        create("prod") {
            dimension = "channel"
            resValue("string", "app_name", "Torchons & Serviettes")
            buildConfigField("String", "API_BASE_URL", "\"$tsApiBaseUrlProd\"")
            // Pas de marqueur de build en prod public (release reproductible).
            buildConfigField("String", "BUILD_INFO", "\"\"")
        }
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            resValue("string", "app_name", "T&S Beta")
            buildConfigField("String", "API_BASE_URL", "\"$tsApiBaseUrlBeta\"")
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
                // Schemas de signature declares explicitement. Les builds
                // precedents ne portaient que v2 : v3 est le schema attendu
                // depuis Android 9, et son absence est un signal de moins pour
                // qui verifie l'origine d'un APK installe hors store.
                // v1 reste desactive — deprecie, et inutile au-dela d'API 24.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (tsHasUploadKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            // R8 enabled on 2026-09-03. Measured on two builds of the same
            // commit: 37.41 MB without, 25.13 MB with — 12.28 MB saved (32.8 %),
            // 1005 classes removed. Keep rules come almost entirely from the
            // libraries themselves (1123 lines in the merged configuration:
            // Room keeps RoomDatabase, Gson keeps TypeToken subclasses); see
            // proguard-rules.pro for what this project adds on top.
            //
            // Shipping a minified APK means crash traces are obfuscated: the
            // matching mapping.txt MUST be archived, see `archiveR8Mapping`.
            //
            // Way back if a minified build breaks a screen: forward, never down.
            // Android refuses to install a lower versionCode without uninstalling
            // first, which wipes local data. So: flip this to false, raise
            // tsVersionCode, assembleProdRelease, adb install -r.
            isMinifyEnabled = true
            isShrinkResources = true
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

    testOptions {
        // Robolectric needs the merged resources/manifest on the unit-test classpath.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// R8 renames every class, so a crash trace coming back from a release APK is
// unreadable without the mapping.txt of THAT exact build. Gradle writes it to
// build/outputs/mapping/, which `clean` deletes — and the APKs here are
// sideloaded, so there is no store to hand the mapping to either.
//
// Runs automatically after each release build. Output is keyed by versionCode:
// rebuilding the same version overwrites, bumping it keeps the old one. The
// directory is gitignored — a mapping weighs ~72 MB.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val mapping = variant.artifacts.get(
            com.android.build.api.artifact.SingleArtifact.OBFUSCATION_MAPPING_FILE
        )
        val target = layout.projectDirectory
            .dir("../mapping-archive")
            .file("${variant.name}-v$tsVersionCode-$tsVersionName-mapping.txt")
        val archive = tasks.register(
            "archiveR8Mapping${variant.name.replaceFirstChar { it.uppercase() }}"
        ) {
            description = "Copies the R8 mapping out of build/ so it survives clean"
            inputs.file(mapping)
            outputs.file(target)
            doLast {
                val src = mapping.get().asFile
                val dst = target.asFile
                dst.parentFile.mkdirs()
                src.copyTo(dst, overwrite = true)
                logger.lifecycle("R8 mapping archived -> ${dst.path} (${src.length() / 1024 / 1024} MB)")
            }
        }
        // Lazy on purpose: inside onVariants, AGP has not created the variant's
        // assemble task yet, so tasks.named() would fail at configuration time.
        val assembleName = "assemble${variant.name.replaceFirstChar { it.uppercase() }}"
        tasks.configureEach { if (name == assembleName) finalizedBy(archive) }
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

    // Firebase. 25.1+ registers with the Firebase Installation ID (onRegistered) instead of the
    // deprecated FCM token (getToken / onNewToken) — backend services/push.py sends by `fids`.
    // Only messaging is used: pinned directly, the BoM and the -ktx artifact are gone.
    implementation("com.google.firebase:firebase-messaging:25.1.1")

    // Widget Glance
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // WorkManager pour notifications locales
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Konfetti — animation de confetti pour les felicitations apres validation
    // d'une tache. Lib populaire et legere (~50ko), bien integree avec Compose.
    implementation("nl.dionsegijn:konfetti-compose:2.0.5")

    // v0.30 — onboarding sans compte
    // Google code scanner : scan QR delegue aux services Google Play, sans permission CAMERA.
    implementation(libs.play.services.code.scanner)
    // QRCode-Kotlin : generation de QR pure Kotlin pour l'ecran d'invitation.
    implementation(libs.qrcode.kotlin)


    // JVM unit tests. Pure logic runs on fakes; DAO / transaction behaviour runs on a REAL Room
    // database (in-memory) under Robolectric (src/test/resources/robolectric.properties: sdk=35).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    testImplementation("androidx.room:room-testing:2.6.1")
}
