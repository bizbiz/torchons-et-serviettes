import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
}

// URL de base de l'API : lue depuis local.properties (gitignore) via la cle
// `ts.api.base.url`. Fallback sur l'emulateur Android (10.0.2.2 = host machine)
// pour les contributeurs sans Tailscale.
val tsApiBaseUrl: String = run {
    val props = Properties()
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) {
        localProps.inputStream().use { props.load(it) }
    }
    (props.getProperty("ts.api.base.url")
        ?: providers.gradleProperty("ts.api.base.url").orNull
        ?: "http://10.0.2.2:8002/api")
}

android {
    namespace = "com.torchons.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.torchons.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 43
        versionName = "0.16.0"

        // URL de base de l'API — voir tsApiBaseUrl ci-dessus.
        buildConfigField("String", "API_BASE_URL", "\"$tsApiBaseUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
}
