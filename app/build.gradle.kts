import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.dzekdrix.openaccustats"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.dzekdrix.openaccustats"
        minSdk = 26
        targetSdk = 35
        // -------------------------------------------------------------------
        // VERSIONNAGE : ne pas modifier à la main, utiliser
        //   ./scripts/release.sh <major|minor|patch>
        // Les valeurs restent écrites en clair (pas calculées depuis git) car le
        // robot de mise à jour de F-Droid (checkupdates) les lit dans ce fichier.
        // Règle : versionCode = MAJOR * 10000 + MINOR * 100 + PATCH
        // -------------------------------------------------------------------
        versionCode = 800
        versionName = "0.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signature locale optionnelle (keystore.properties n'est jamais commité).
    // F-Droid compile et signe lui-même : sans ce fichier, le build release reste non signé.
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // F-Droid : pas de bloc de métadonnées de dépendances chiffré par Google dans l'APK
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core) // Menu, Accueil, Paramètres (Apache 2.0)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

// Garde-fou : refuse de compiler si versionCode et versionName sont incohérents.
val appVersionCode = android.defaultConfig.versionCode!!
val appVersionName = android.defaultConfig.versionName!!
val verifyVersion by tasks.registering {
    doLast {
        val m = Regex("""^(\d+)\.(\d+)\.(\d+)$""").find(appVersionName)
            ?: throw GradleException("versionName '$appVersionName' n'est pas au format MAJOR.MINOR.PATCH")
        val (maj, min, pat) = m.destructured.toList().map { it.toInt() }
        if (min > 99 || pat > 99) throw GradleException("MINOR et PATCH doivent rester ≤ 99")
        val expected = maj * 10000 + min * 100 + pat
        if (expected != appVersionCode) {
            throw GradleException("versionCode=$appVersionCode attendu $expected pour $appVersionName")
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyVersion) }
