import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.chesslens.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chesslens.android"
        minSdk = 31
        targetSdk = 36
        // 1000 + the CI run number, so CI builds install over hand-made builds (versionCode 1000).
        versionCode = 1000 + (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }

        // Meta Wearables: "0" = Developer Mode (no Wearables Developer Center app needed).
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"
    }

    signingConfigs {
        // A fixed key (the standard Android debug credentials) so each new build installs as an
        // update instead of requiring the old app to be removed first.
        getByName("debug") {
            storeFile = file("chesslens-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Stockfish ships as an executable named like a library so Android installs it into the
        // app's native library folder, from where it can be run.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    testImplementation(libs.junit)
}
