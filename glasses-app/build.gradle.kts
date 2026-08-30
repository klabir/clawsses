import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

abstract class VerifyGlassesReleaseIsolationTask : DefaultTask() {
    @get:InputFile
    abstract val releaseApk: RegularFileProperty

    @TaskAction
    fun verify() {
        val forbiddenClassPath = "com/clawsses/glasses/debug/DebugPhoneClient"
        val forbiddenPermission = "android.permission.INTERNET"

        ZipFile(releaseApk.get().asFile).use { apk ->
            val leakedFrom = apk.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .firstOrNull { entry ->
                    apk.getInputStream(entry).use { input ->
                        input.readBytes().toString(Charsets.ISO_8859_1)
                            .contains(forbiddenClassPath)
                    }
                }
            check(leakedFrom == null) {
                "Release APK contains emulator-only HUD transport in ${leakedFrom?.name}"
            }

            val manifest = apk.getEntry("AndroidManifest.xml")
                ?: error("Release APK has no AndroidManifest.xml")
            val manifestBytes = apk.getInputStream(manifest).use { it.readBytes() }
            val manifestUtf8 = manifestBytes.toString(Charsets.UTF_8)
            val manifestUtf16 = manifestBytes.toString(Charsets.UTF_16LE)
            check(forbiddenPermission !in manifestUtf8 && forbiddenPermission !in manifestUtf16) {
                "Release APK declares emulator-only $forbiddenPermission"
            }
        }
    }
}

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

val glassesReleaseApkName = if (useDebugSigningForHardwareTest.get()) {
    "glasses-app-release.apk"
} else {
    "glasses-app-release-unsigned.apk"
}

val verifyGlassesReleaseIsolation = tasks.register<VerifyGlassesReleaseIsolationTask>(
    "verifyGlassesReleaseIsolation"
) {
    group = "verification"
    description = "Fails if the HUD release contains debug socket transport or INTERNET permission."
    dependsOn("assembleRelease")
    releaseApk.set(
        layout.buildDirectory.file("outputs/apk/release/$glassesReleaseApkName")
    )
}

tasks.named("check").configure {
    dependsOn(verifyGlassesReleaseIsolation)
}

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
