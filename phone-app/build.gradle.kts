import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.zip.ZipFile

abstract class BundleGlassesApkTask : DefaultTask() {
    @get:InputFile
    abstract val sourceApk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val outputFile = outputDirectory.file("glasses-app-release.apk").get().asFile
        outputFile.parentFile.mkdirs()
        sourceApk.get().asFile.copyTo(outputFile, overwrite = true)
    }
}

abstract class VerifyReleaseExcludesDebugTransportTask : DefaultTask() {
    @get:InputFile
    abstract val releaseApk: RegularFileProperty

    @TaskAction
    fun verify() {
        val forbiddenClassPath = "com/clawsses/phone/debug/DebugGlassesServer"
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
                "Release APK contains emulator-only debug transport in ${leakedFrom?.name}"
            }
        }
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

val clawssesVersionCode = providers.gradleProperty("clawsses.versionCode").get().toInt()
val clawssesVersionName = providers.gradleProperty("clawsses.versionName").get()
val useDebugSigningForHardwareTest = providers
    .gradleProperty("clawsses.hardwareTestSigning")
    .map(String::toBooleanStrict)
    .orElse(false)

// Load Rokid credentials from local.properties (needed for SN verification)
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}
val rokidClientSecret = providers.gradleProperty("rokid.clientSecret").orNull?.trim()
    ?: localProperties.getProperty("rokid.clientSecret", "").trim()
val rokidAccessKey = providers.gradleProperty("rokid.accessKey").orNull?.trim()
    ?: localProperties.getProperty("rokid.accessKey", "").trim()
val hasRokidCredentials = rokidClientSecret.isNotEmpty() || rokidAccessKey.isNotEmpty()
require(!hasRokidCredentials || (rokidClientSecret.isNotEmpty() && rokidAccessKey.isNotEmpty())) {
    "Rokid credentials must provide both rokid.clientSecret and rokid.accessKey"
}
require(rokidClientSecret.isEmpty() || rokidClientSecret.replace("-", "").length == 32) {
    "rokid.clientSecret must contain 32 key characters after removing separators"
}

tasks.register("verifyPublicReleaseHasNoRokidCredentials") {
    group = "verification"
    description = "Fails when a public build environment embeds private Rokid credentials."
    doLast {
        check(!hasRokidCredentials) {
            "Public release verification failed: the phone APK would embed private Rokid credentials"
        }
    }
}

android {
    namespace = "com.clawsses.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clawsses.phone"
        minSdk = 28  // Required by CXR-M SDK
        targetSdk = 34
        versionCode = clawssesVersionCode
        versionName = clawssesVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Rokid credentials for SN verification during Bluetooth connection
        // clientSecret = AES key used to decrypt snEncryptContent (from .lc file)
        // accessKey = rokidAccount identifier
        buildConfigField("String", "ROKID_CLIENT_SECRET", "\"$rokidClientSecret\"")
        buildConfigField("String", "ROKID_ACCESS_KEY", "\"$rokidAccessKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (useDebugSigningForHardwareTest.get()) {
                // Explicit opt-in for a local, data-preserving hardware gate. Public release
                // builds remain unsigned unless the publishing environment supplies a signer.
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

    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

// Bundle the matching glasses APK through a generated variant asset directory. Builds no longer
// mutate src/main/assets, and release-derived phone variants cannot silently ship the debug HUD.
androidComponents {
    finalizeDsl { extension ->
        // The Baseline Profile plugin creates these build types during its own finalizeDsl
        // callback. Apply the suffix afterward so profiling can never replace production data.
        extension.buildTypes.configureEach {
            if (name.startsWith("nonMinified") || name.startsWith("benchmark")) {
                applicationIdSuffix = ".benchmark"
            }
        }
    }

    onVariants(selector().all()) { variant ->
        val glassesBuildType = if (variant.name.contains("release", ignoreCase = true)) {
            "release"
        } else {
            "debug"
        }
        val taskName = "bundle${variant.name.replaceFirstChar(Char::uppercaseChar)}GlassesApk"
        val bundleTask = tasks.register<BundleGlassesApkTask>(taskName) {
            dependsOn(":glasses-app:assemble${glassesBuildType.replaceFirstChar(Char::uppercaseChar)}")
            val glassesApkName = if (glassesBuildType == "release") {
                "glasses-app-release-unsigned.apk"
            } else {
                "glasses-app-debug.apk"
            }
            sourceApk.set(
                project(":glasses-app").layout.buildDirectory.file(
                    "outputs/apk/$glassesBuildType/$glassesApkName"
                )
            )
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            bundleTask,
            BundleGlassesApkTask::outputDirectory,
        )
    }
}

dependencies {
    implementation(project(":shared"))

    // Rokid CXR-M SDK (Phone side)
    implementation("com.rokid.cxr:client-m:1.2.2")

    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    compileOnly("com.google.errorprone:error_prone_annotations:2.30.0")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Networking for WebSocket/SSH
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ADB over WiFi for APK installation on glasses
    implementation("dev.mobile:dadb:1.2.10")

    // Ed25519 signing (Android's bundled BouncyCastle doesn't include EdDSA)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    baselineProfile(project(":benchmark"))
}

baselineProfile {
    automaticGenerationDuringBuild = false
}

tasks.withType<KotlinCompile>().configureEach {
    // Compose encodes the Kotlin module name in generated singleton accessors. Keep it stable
    // across release, nonMinifiedRelease, and benchmarkRelease so one generated profile is valid.
    compilerOptions.moduleName.set("phone_app")
}

val normalizeReleaseBaselineProfiles = tasks.register("normalizeReleaseBaselineProfiles") {
    group = "verification"
    description = "Removes D8-generated synthetic lambda rules that are unstable across variants."
    doLast {
        fileTree("src/release/generated/baselineProfiles") {
            include("*.txt")
        }.files.forEach { profile ->
            val stableRules = profile.readLines().filterNot { rule ->
                rule.contains("\$\$ExternalSyntheticLambda")
            }
            profile.writeText(stableRules.joinToString(separator = "\n", postfix = "\n"))
        }
    }
}

tasks.matching { it.name == "copyReleaseBaselineProfileIntoSrc" }.configureEach {
    finalizedBy(normalizeReleaseBaselineProfiles)
}

val verifyReleaseExcludesDebugTransport =
    tasks.register<VerifyReleaseExcludesDebugTransportTask>("verifyReleaseExcludesDebugTransport") {
        group = "verification"
        description = "Fails if the unauthenticated emulator transport is packaged in the phone release APK."
        dependsOn("assembleRelease")
        releaseApk.set(
            layout.buildDirectory.file("outputs/apk/release/phone-app-release-unsigned.apk")
        )
    }

tasks.named("check").configure {
    dependsOn(verifyReleaseExcludesDebugTransport)
}
