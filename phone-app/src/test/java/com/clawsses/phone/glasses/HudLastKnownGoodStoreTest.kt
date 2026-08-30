package com.clawsses.phone.glasses

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class HudLastKnownGoodStoreTest {
    private val root = Files.createTempDirectory("clawsses-hud-lkg").toFile()
    private val store = HudLastKnownGoodStore(root)

    @After
    fun cleanup() {
        root.walkBottomUp().forEach(File::delete)
    }

    @Test
    fun `candidate is promoted only after matching peer build`() {
        val apk = artifact("build-111")
        val manifest = manifest(apk, build = 111)
        store.stage(apk, manifest)

        assertNull(store.promote(110))
        assertNull(store.readLastKnownGood())

        val retained = store.promote(111)
        assertEquals(111L, retained?.manifest?.versionCode)
        assertEquals(apk.readBytes().toList(), retained?.apk?.readBytes()?.toList())
    }

    @Test
    fun `new verified artifact replaces the only retained entry`() {
        val first = artifact("first")
        store.stage(first, manifest(first, build = 110))
        store.promote(110)

        val second = artifact("second")
        store.stage(second, manifest(second, build = 111))
        store.promote(111)

        val retained = store.readLastKnownGood()
        assertEquals(111L, retained?.manifest?.versionCode)
        assertEquals(second.readBytes().toList(), retained?.apk?.readBytes()?.toList())
    }

    @Test
    fun `tampered candidate and retained artifact are rejected`() {
        val apk = artifact("verified")
        val manifest = manifest(apk, build = 111)
        store.stage(apk, manifest)
        File(root, "candidate.apk").appendText("tampered")
        assertNull(store.promote(111))

        store.stage(apk, manifest)
        store.promote(111)
        File(root, "last-known-good.apk").appendText("tampered")
        assertNull(store.readLastKnownGood())
    }

    @Test
    fun `candidate with mismatched manifest hash is never staged`() {
        val apk = artifact("candidate")
        assertThrows(IllegalArgumentException::class.java) {
            store.stage(apk, manifest(apk, build = 111).copy(sha256 = "wrong"))
        }
    }

    @Test
    fun `production installer routes explicitly reject downgrade`() {
        assertEquals(
            HudRollbackAvailability.INSTALLER_DOWNGRADE_UNSUPPORTED,
            HudRollbackPolicy.evaluate(InstallerRoute.CXR_M, currentBuild = 112, retainedBuild = 111),
        )
        assertEquals(
            HudRollbackAvailability.INSTALLER_DOWNGRADE_UNSUPPORTED,
            HudRollbackPolicy.evaluate(InstallerRoute.HI_ROKID, currentBuild = 112, retainedBuild = 111),
        )
    }

    private fun artifact(value: String): File = File(root, "$value.apk").apply {
        writeText(value)
    }

    private fun manifest(apk: File, build: Long) = HudArtifactManifest(
        schemaVersion = 1,
        fileName = "glasses-app-release.apk",
        applicationId = "com.clawsses.glasses",
        versionCode = build,
        versionName = "1.3.${build - 9}",
        sha256 = sha256(apk),
        signerPolicy = "match-host",
    )

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte) }
}
