import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.security.MessageDigest
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
        val forbiddenClassPaths = listOf(
            "com/clawsses/phone/debug/DebugGlassesServer",
            "com/clawsses/phone/benchmark/",
        )
        ZipFile(releaseApk.get().asFile).use { apk ->
            val leaked = apk.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .mapNotNull { entry ->
                    apk.getInputStream(entry).use { input ->
                        val dex = input.readBytes().toString(Charsets.ISO_8859_1)
                        forbiddenClassPaths.firstOrNull(dex::contains)?.let { entry.name to it }
                    }
                }.firstOrNull()
            check(leaked == null) {
                "Release APK contains non-production class ${leaked?.second} in ${leaked?.first}"
            }
        }
    }
}

abstract class VerifyPublicReleaseCredentialsTask : DefaultTask() {
    @get:InputFile
    abstract val releaseApk: RegularFileProperty

    @get:Input
    abstract val credentialEmbeddingEnabled: Property<Boolean>

    @get:Internal
    abstract val forbiddenCredentialValues: ListProperty<String>

    @TaskAction
    fun verify() {
        check(!credentialEmbeddingEnabled.get()) {
            "Public release verification cannot run with embedded Rokid credentials enabled"
        }

        val forbiddenValues = forbiddenCredentialValues.get().filter(String::isNotBlank)
        if (forbiddenValues.isEmpty()) return

        ZipFile(releaseApk.get().asFile).use { apk ->
            val leakedValue = apk.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .flatMap { entry ->
                    val dex = apk.getInputStream(entry).use { it.readBytes() }
                        .toString(Charsets.ISO_8859_1)
                    forbiddenValues.asSequence().map { value -> value to dex.contains(value) }
                }
                .firstOrNull { (_, leaked) -> leaked }
            check(leakedValue == null) {
                "Public release APK contains a configured private Rokid credential"
            }
        }
    }
}

abstract class GeneratePairedReleaseEvidenceTask : DefaultTask() {
    @get:InputFile
    abstract val phoneApk: RegularFileProperty

    @get:InputFile
    abstract val glassesApk: RegularFileProperty

    @get:Input
    abstract val sourceCommit: Property<String>

    @get:Input
    abstract val sourceTreeDirty: Property<Boolean>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val versionName: Property<String>

    @get:OutputFile
    abstract val evidenceFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val phone = phoneApk.get().asFile
        val glasses = glassesApk.get().asFile
        val phoneHash = phone.inputStream().use(::sha256)
        val glassesHash = glasses.inputStream().use(::sha256)
        val embeddedHash = ZipFile(phone).use { apk ->
            val embedded = apk.getEntry("assets/glasses-app-release.apk")
                ?: error("Phone release does not contain the paired HUD APK")
            apk.getInputStream(embedded).use(::sha256)
        }
        check(embeddedHash == glassesHash) {
            "Phone release embeds HUD $embeddedHash but paired HUD is $glassesHash"
        }

        val output = evidenceFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            {
              "schemaVersion": 1,
              "sourceCommit": "${sourceCommit.get()}",
              "sourceTreeDirty": ${sourceTreeDirty.get()},
              "versionName": "${versionName.get()}",
              "versionCode": ${versionCode.get()},
              "artifactClass": "public-release",
              "phone": {
                "file": "${phone.name}",
                "sizeBytes": ${phone.length()},
                "sha256": "$phoneHash"
              },
              "hud": {
                "file": "${glasses.name}",
                "sizeBytes": ${glasses.length()},
                "sha256": "$glassesHash",
                "embeddedSha256": "$embeddedHash",
                "embeddedMatches": true
              }
            }
            """.trimIndent() + "\n"
        )
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
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
val embedRokidCredentials = providers
    .gradleProperty("clawsses.embedRokidCredentials")
    .map(String::toBooleanStrict)
    .orElse(useDebugSigningForHardwareTest)

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
require(!embedRokidCredentials.get() || hasRokidCredentials) {
    "Embedded Rokid credentials were requested, but local credentials are incomplete or missing"
}

val embeddedRokidClientSecret = if (embedRokidCredentials.get()) rokidClientSecret else ""
val embeddedRokidAccessKey = if (embedRokidCredentials.get()) rokidAccessKey else ""

android {
    namespace = "com.clawsses.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clawsses.phone"
        minSdk = 28  // Required by CXR-M SDK
        targetSdk = 35
        versionCode = clawssesVersionCode
        versionName = clawssesVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Public builds always receive empty values. Private hardware builds must opt in through
        // clawsses.hardwareTestSigning or clawsses.embedRokidCredentials.
        buildConfigField("String", "ROKID_CLIENT_SECRET", "\"$embeddedRokidClientSecret\"")
        buildConfigField("String", "ROKID_ACCESS_KEY", "\"$embeddedRokidAccessKey\"")
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
                "proguard-rules.pro",
                *if (useDebugSigningForHardwareTest.get()) {
                    emptyArray()
                } else {
                    arrayOf("proguard-public-release.pro")
                },
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
        extension.sourceSets.maybeCreate("benchmarkRelease").manifest.srcFile(
            "src/benchmarkRelease/AndroidManifest.xml",
        )
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
                if (useDebugSigningForHardwareTest.get()) {
                    "glasses-app-release.apk"
                } else {
                    "glasses-app-release-unsigned.apk"
                }
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
    // Official Hi Rokid bridge used as an installer fallback when CXR-M Wi-Fi is unavailable.
    implementation("com.rokid.cxr:client-l:1.1.1") {
        // client-m already supplies the hardware-verified bridge classes and JNI libraries.
        // CXR-L only needs its Hi Rokid Binder/client layer for the installer path.
        exclude(group = "com.rokid.cxr", module = "cxr-service-bridge")
    }

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
            layout.buildDirectory.file(
                useDebugSigningForHardwareTest.map { usesHardwareTestSigner ->
                    val fileName = if (usesHardwareTestSigner) {
                        "phone-app-release.apk"
                    } else {
                        "phone-app-release-unsigned.apk"
                    }
                    "outputs/apk/release/$fileName"
                }
            )
        )
    }

val verifyPublicReleaseHasNoRokidCredentials =
    tasks.register<VerifyPublicReleaseCredentialsTask>("verifyPublicReleaseHasNoRokidCredentials") {
        group = "verification"
        description = "Fails if a public phone release embeds configured private Rokid credentials."
        dependsOn("assembleRelease")
        releaseApk.set(
            layout.buildDirectory.file("outputs/apk/release/phone-app-release-unsigned.apk")
        )
        credentialEmbeddingEnabled.set(embedRokidCredentials)
        forbiddenCredentialValues.set(listOf(rokidClientSecret, rokidAccessKey))
    }

tasks.named("check").configure {
    dependsOn(verifyReleaseExcludesDebugTransport)
    if (!useDebugSigningForHardwareTest.get()) {
        dependsOn(verifyPublicReleaseHasNoRokidCredentials)
    }
}

val generatePairedReleaseEvidence = tasks.register<GeneratePairedReleaseEvidenceTask>(
    "generatePairedReleaseEvidence"
) {
    group = "verification"
    description = "Writes public paired Phone/HUD hashes and verifies the embedded HUD identity."
    dependsOn(
        verifyReleaseExcludesDebugTransport,
        verifyPublicReleaseHasNoRokidCredentials,
        ":glasses-app:verifyGlassesReleaseIsolation",
    )
    phoneApk.set(
        layout.buildDirectory.file("outputs/apk/release/phone-app-release-unsigned.apk")
    )
    glassesApk.set(
        project(":glasses-app").layout.buildDirectory.file(
            "outputs/apk/release/glasses-app-release-unsigned.apk"
        )
    )
    sourceCommit.set(
        providers.exec {
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.map(String::trim)
    )
    sourceTreeDirty.set(
        providers.exec {
            commandLine("git", "status", "--porcelain", "--untracked-files=normal")
        }.standardOutput.asText.map { it.isNotBlank() }
    )
    versionCode.set(clawssesVersionCode)
    versionName.set(clawssesVersionName)
    evidenceFile.set(layout.buildDirectory.file("reports/release/paired-release-evidence.json"))
}

tasks.register("verifyPairedRelease") {
    group = "verification"
    description = "Runs the full source gate and emits paired public-release evidence."
    dependsOn("check", generatePairedReleaseEvidence)
}
