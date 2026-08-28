plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val clawssesVersionCode = providers.gradleProperty("clawsses.versionCode").get().toInt()
val clawssesVersionName = providers.gradleProperty("clawsses.versionName").get()
val useDebugSigningForHardwareTest = providers
    .gradleProperty("clawsses.hardwareTestSigning")
    .map(String::toBooleanStrict)
    .orElse(false)

android {
    namespace = "com.clawsses.glasses"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clawsses.glasses"
        minSdk = 28  // Required for CXR-S SDK
        // Sprite firmware 1.24 returns immediately to its launcher when a
        // custom HUD targets API 35. Keep compiling against API 35, but retain
        // the hardware-verified API 34 runtime contract on the glasses.
        targetSdk = 34
        versionCode = clawssesVersionCode
        versionName = clawssesVersionName
    }

    buildTypes {
        release {
            // The Sprite 1.24 custom-app runtime returns the optimized HUD to
            // its launcher immediately. Keep the phone optimized, but ship the
            // glasses process unminified until the vendor/reflection boundary
            // can be hardened with device-visible crash diagnostics.
            isMinifyEnabled = false
            if (useDebugSigningForHardwareTest.get()) {
                // Explicit local paired-device gate. Public release builds remain unsigned.
                signingConfig = signingConfigs.getByName("debug")
            }
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
    implementation(project(":shared"))

    // Rokid CXR-S SDK (Glasses side)
    implementation("com.rokid.cxr:cxr-service-bridge:1.0")

    // Android Core (minimal for glasses)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose (lightweight)
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
}
