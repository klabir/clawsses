@file:Suppress("DEPRECATION")

package com.clawsses.phone.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Creates encrypted preferences backed by Android Keystore and migrates any
 * values from the former plaintext preferences file on first use.
 */
object SecurePreferences {
    private const val MIGRATION_COMPLETE = "__encrypted_migration_complete"

    fun create(context: Context, legacyName: String): SharedPreferences {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encrypted = EncryptedSharedPreferences.create(
            appContext,
            "${legacyName}_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        synchronized(this) {
            if (!encrypted.getBoolean(MIGRATION_COMPLETE, false)) {
                migratePlaintextPreferences(appContext, legacyName, encrypted)
            }
        }
        return encrypted
    }

    private fun migratePlaintextPreferences(
        context: Context,
        legacyName: String,
        encrypted: SharedPreferences,
    ) {
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val editor = encrypted.edit()
        legacy.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.putBoolean(MIGRATION_COMPLETE, true)
        check(editor.commit()) { "Failed to migrate $legacyName to encrypted preferences" }
        check(legacy.edit().clear().commit()) { "Failed to clear plaintext preferences $legacyName" }
    }
}
