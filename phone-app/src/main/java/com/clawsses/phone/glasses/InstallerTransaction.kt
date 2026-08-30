package com.clawsses.phone.glasses

import android.content.Context

internal enum class InstallerRoute(val label: String) {
    HI_ROKID("Hi Rokid"),
    CXR_M("CXR-M"),
}

internal enum class InstallerPhase {
    AWAITING_AUTHORIZATION,
    PREPARING_APK,
    CONNECTING,
    UPLOADING,
    INSTALLING,
    VERIFYING,
}

internal data class InstallerAvailability(
    val hiRokidAvailable: Boolean,
    val authorizationLauncherAvailable: Boolean,
    val cxrReady: Boolean,
    val cxrConnected: Boolean,
)

internal object InstallerTransportPolicy {
    fun select(availability: InstallerAvailability): InstallerRoute? = when {
        availability.hiRokidAvailable && availability.authorizationLauncherAvailable ->
            InstallerRoute.HI_ROKID

        availability.cxrReady && availability.cxrConnected -> InstallerRoute.CXR_M
        else -> null
    }
}

internal data class InstallerTransaction(
    val route: InstallerRoute,
    val phase: InstallerPhase,
    val expectedBuild: Int,
)

internal class InstallerTransactionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun start(route: InstallerRoute, phase: InstallerPhase, expectedBuild: Int) {
        preferences.edit()
            .putString(KEY_ROUTE, route.name)
            .putString(KEY_PHASE, phase.name)
            .putInt(KEY_EXPECTED_BUILD, expectedBuild)
            .apply()
    }

    fun advance(phase: InstallerPhase) {
        if (!preferences.contains(KEY_ROUTE)) return
        preferences.edit().putString(KEY_PHASE, phase.name).apply()
    }

    fun active(): InstallerTransaction? {
        val route = preferences.getString(KEY_ROUTE, null)
            ?.let { runCatching { InstallerRoute.valueOf(it) }.getOrNull() }
            ?: return null
        val phase = preferences.getString(KEY_PHASE, null)
            ?.let { runCatching { InstallerPhase.valueOf(it) }.getOrNull() }
            ?: return null
        val expectedBuild = preferences.getInt(KEY_EXPECTED_BUILD, 0).takeIf { it > 0 }
            ?: return null
        return InstallerTransaction(route, phase, expectedBuild)
    }

    fun clear() {
        preferences.edit()
            .remove(KEY_ROUTE)
            .remove(KEY_PHASE)
            .remove(KEY_EXPECTED_BUILD)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "clawsses_installer_transaction"
        const val KEY_ROUTE = "route"
        const val KEY_PHASE = "phase"
        const val KEY_EXPECTED_BUILD = "expected_build"
    }
}
