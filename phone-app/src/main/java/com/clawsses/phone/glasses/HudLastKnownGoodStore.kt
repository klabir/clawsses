package com.clawsses.phone.glasses

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class RetainedHudArtifact(
    val apk: File,
    val manifest: HudArtifactManifest,
)

internal enum class HudRollbackAvailability {
    AVAILABLE,
    NO_VERIFIED_ARTIFACT,
    NOT_OLDER_THAN_CURRENT,
    INSTALLER_DOWNGRADE_UNSUPPORTED,
}

internal object HudRollbackPolicy {
    fun evaluate(
        route: InstallerRoute,
        currentBuild: Long,
        retainedBuild: Long?,
    ): HudRollbackAvailability = when {
        retainedBuild == null -> HudRollbackAvailability.NO_VERIFIED_ARTIFACT
        retainedBuild >= currentBuild -> HudRollbackAvailability.NOT_OLDER_THAN_CURRENT
        else -> when (route) {
            InstallerRoute.HI_ROKID,
            InstallerRoute.CXR_M -> HudRollbackAvailability.INSTALLER_DOWNGRADE_UNSUPPORTED
        }
    }
}

/** Retains one previously handshake-verified HUD artifact. Candidate state is bounded and replaced. */
internal class HudLastKnownGoodStore(private val root: File) {
    private val candidateApk = File(root, "candidate.apk")
    private val candidateManifest = File(root, "candidate.json")
    private val retainedApk = File(root, "last-known-good.apk")
    private val retainedManifest = File(root, "last-known-good.json")

    fun stage(apk: File, manifest: HudArtifactManifest) {
        require(apk.isFile && apk.length() > 0L) { "HUD recovery candidate is missing." }
        require(sha256(apk) == manifest.sha256) { "HUD recovery candidate hash mismatch." }
        check(root.mkdirs() || root.isDirectory) { "HUD recovery directory is unavailable." }
        replaceWithCopy(apk, candidateApk)
        replaceWithText(HudArtifactPreflight.format(manifest), candidateManifest)
    }

    fun promote(expectedBuild: Long): RetainedHudArtifact? {
        val candidate = read(candidateApk, candidateManifest) ?: return null
        if (candidate.manifest.versionCode != expectedBuild) return null
        replaceWithCopy(candidate.apk, retainedApk)
        replaceWithText(HudArtifactPreflight.format(candidate.manifest), retainedManifest)
        Files.deleteIfExists(candidateApk.toPath())
        Files.deleteIfExists(candidateManifest.toPath())
        return readLastKnownGood()
    }

    fun readLastKnownGood(): RetainedHudArtifact? = read(retainedApk, retainedManifest)

    private fun read(apk: File, manifestFile: File): RetainedHudArtifact? {
        if (!apk.isFile || !manifestFile.isFile) return null
        val manifest = runCatching {
            HudArtifactPreflight.parse(manifestFile.readText())
        }.getOrNull() ?: return null
        if (sha256(apk) != manifest.sha256) return null
        return RetainedHudArtifact(apk, manifest)
    }

    private fun replaceWithCopy(source: File, target: File) {
        val temporary = File(root, "${target.name}.tmp")
        source.copyTo(temporary, overwrite = true)
        replace(temporary, target)
    }

    private fun replaceWithText(value: String, target: File) {
        val temporary = File(root, "${target.name}.tmp")
        temporary.writeText(value)
        replace(temporary, target)
    }

    private fun replace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
