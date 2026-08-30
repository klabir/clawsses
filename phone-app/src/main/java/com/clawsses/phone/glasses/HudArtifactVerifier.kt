package com.clawsses.phone.glasses

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

internal data class HudArtifactManifest(
    val schemaVersion: Int,
    val fileName: String,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val signerPolicy: String,
)

internal data class HudArtifactObservation(
    val fileName: String,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val artifactSignerDigests: Set<String>,
    val hostSignerDigests: Set<String>,
)

internal object HudArtifactPreflight {
    const val MANIFEST_ASSET = "glasses-app-release-manifest.json"
    private const val SUPPORTED_SCHEMA = 1
    private const val MATCH_HOST_SIGNER = "match-host"

    fun parse(json: String): HudArtifactManifest {
        val value = JsonParser.parseString(json).asJsonObject
        return HudArtifactManifest(
            schemaVersion = value.get("schemaVersion").asInt,
            fileName = value.get("fileName").asString,
            applicationId = value.get("applicationId").asString,
            versionCode = value.get("versionCode").asLong,
            versionName = value.get("versionName").asString,
            sha256 = value.get("sha256").asString.lowercase(),
            signerPolicy = value.get("signerPolicy").asString,
        )
    }

    fun verify(manifest: HudArtifactManifest, observed: HudArtifactObservation) {
        check(manifest.schemaVersion == SUPPORTED_SCHEMA) {
            "Unsupported HUD artifact manifest schema ${manifest.schemaVersion}."
        }
        check(manifest.fileName == observed.fileName) { "Bundled HUD file name does not match its manifest." }
        check(manifest.applicationId == observed.applicationId) { "Bundled HUD package is not Clawsses." }
        check(manifest.versionCode == observed.versionCode) { "Bundled HUD build does not match the Phone build." }
        check(manifest.versionName == observed.versionName) { "Bundled HUD version does not match the Phone version." }
        check(manifest.sha256 == observed.sha256.lowercase()) { "Bundled HUD hash does not match its manifest." }
        check(manifest.signerPolicy == MATCH_HOST_SIGNER) { "Unsupported HUD signer policy." }
        check(observed.hostSignerDigests.isNotEmpty()) { "Phone signer could not be verified." }
        check(observed.artifactSignerDigests.isNotEmpty()) { "Bundled HUD is unsigned or its signer could not be read." }
        check(observed.artifactSignerDigests == observed.hostSignerDigests) {
            "Bundled HUD signer does not match the Phone signer."
        }
    }
}

internal class HudArtifactVerifier(context: Context) {
    private val appContext = context.applicationContext

    fun verify(apk: File): HudArtifactManifest {
        val manifest = appContext.assets.open(HudArtifactPreflight.MANIFEST_ASSET)
            .bufferedReader()
            .use { reader -> HudArtifactPreflight.parse(reader.readText()) }
        val packageManager = appContext.packageManager
        val artifactInfo = packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: error("Bundled HUD package metadata could not be read.")
        val hostInfo = packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val observation = HudArtifactObservation(
            fileName = apk.name,
            applicationId = artifactInfo.packageName,
            versionCode = artifactInfo.longVersionCode,
            versionName = artifactInfo.versionName.orEmpty(),
            sha256 = apk.inputStream().use(::sha256),
            artifactSignerDigests = artifactInfo.currentSignerDigests(),
            hostSignerDigests = hostInfo.currentSignerDigests(),
        )
        HudArtifactPreflight.verify(manifest, observation)
        return manifest
    }

    private fun PackageInfo.currentSignerDigests(): Set<String> =
        signingInfo?.apkContentsSigners.orEmpty()
            .mapTo(linkedSetOf()) { signature -> sha256(signature.toByteArray().inputStream()) }

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
