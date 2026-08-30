package com.clawsses.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HudArtifactPreflightTest {
    private val manifest = HudArtifactManifest(
        schemaVersion = 1,
        fileName = "glasses-app-release.apk",
        applicationId = "com.clawsses.glasses",
        versionCode = 110,
        versionName = "1.3.101",
        sha256 = "artifact-hash",
        signerPolicy = "match-host",
    )
    private val observation = HudArtifactObservation(
        fileName = manifest.fileName,
        applicationId = manifest.applicationId,
        versionCode = manifest.versionCode,
        versionName = manifest.versionName,
        sha256 = manifest.sha256,
        artifactSignerDigests = setOf("signer"),
        hostSignerDigests = setOf("signer"),
    )

    @Test
    fun `generated manifest fields parse without reflection`() {
        val parsed = HudArtifactPreflight.parse(
            """
            {
              "schemaVersion": 1,
              "fileName": "glasses-app-release.apk",
              "applicationId": "com.clawsses.glasses",
              "versionCode": 110,
              "versionName": "1.3.101",
              "sha256": "ARTIFACT-HASH",
              "signerPolicy": "match-host"
            }
            """.trimIndent(),
        )

        assertEquals(manifest, parsed)
    }

    @Test
    fun `matching artifact passes all preflight checks`() {
        HudArtifactPreflight.verify(manifest, observation)
    }

    @Test
    fun `hash mismatch is rejected`() {
        assertThrows(IllegalStateException::class.java) {
            HudArtifactPreflight.verify(manifest, observation.copy(sha256 = "tampered"))
        }
    }

    @Test
    fun `package and version mismatches are rejected`() {
        assertThrows(IllegalStateException::class.java) {
            HudArtifactPreflight.verify(manifest, observation.copy(applicationId = "other.app"))
        }
        assertThrows(IllegalStateException::class.java) {
            HudArtifactPreflight.verify(manifest, observation.copy(versionCode = 109))
        }
        assertThrows(IllegalStateException::class.java) {
            HudArtifactPreflight.verify(manifest, observation.copy(versionName = "1.3.100"))
        }
    }

    @Test
    fun `unsigned or differently signed artifact is rejected`() {
        assertThrows(IllegalStateException::class.java) {
            HudArtifactPreflight.verify(manifest, observation.copy(artifactSignerDigests = emptySet()))
        }
        assertThrows(IllegalStateException::class.java) {
            HudArtifactPreflight.verify(manifest, observation.copy(artifactSignerDigests = setOf("other")))
        }
    }
}
