package com.clawsses.phone.tts

import android.content.Context
import com.clawsses.phone.util.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TtsProvider {
    ELEVENLABS,
    OPENAI,
}

/**
 * Manages TTS settings persistence and reactive state.
 */
class TtsSettingsManager(context: Context) {

    private val prefs = SecurePreferences.create(context, PREFS_NAME)

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "") ?: "")
    /** ElevenLabs API key, retained for backwards compatibility. */
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _openAiApiKey = MutableStateFlow(prefs.getString(KEY_OPENAI_API_KEY, "") ?: "")
    val openAiApiKey: StateFlow<String> = _openAiApiKey.asStateFlow()

    private val _provider = MutableStateFlow(
        runCatching {
            TtsProvider.valueOf(prefs.getString(KEY_PROVIDER, TtsProvider.ELEVENLABS.name)!!)
        }.getOrDefault(TtsProvider.ELEVENLABS)
    )
    val provider: StateFlow<TtsProvider> = _provider.asStateFlow()

    private val _selectedVoiceId = MutableStateFlow(activeVoiceId(_provider.value))
    val selectedVoiceId: StateFlow<String?> = _selectedVoiceId.asStateFlow()

    private val _selectedVoiceName = MutableStateFlow(activeVoiceName(_provider.value))
    val selectedVoiceName: StateFlow<String?> = _selectedVoiceName.asStateFlow()

    private val _isEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _speed = MutableStateFlow(prefs.getFloat(KEY_SPEED, DEFAULT_SPEED))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    fun setApiKey(key: String) {
        _apiKey.value = key
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    fun setOpenAiApiKey(key: String) {
        _openAiApiKey.value = key
        // This is intentionally the same encrypted key used by OpenAI transcription.
        prefs.edit().putString(KEY_OPENAI_API_KEY, key).apply()
    }

    fun setProvider(provider: TtsProvider) {
        _provider.value = provider
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
        _selectedVoiceId.value = activeVoiceId(provider)
        _selectedVoiceName.value = activeVoiceName(provider)
        if (!isConfigured()) setEnabled(false)
    }

    fun setSelectedVoice(id: String, name: String) {
        _selectedVoiceId.value = id
        _selectedVoiceName.value = name
        val editor = prefs.edit()
        if (_provider.value == TtsProvider.OPENAI) {
            editor.putString(KEY_OPENAI_VOICE, id)
        } else {
            editor.putString(KEY_VOICE_ID, id).putString(KEY_VOICE_NAME, name)
        }
        editor.apply()
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setSpeed(speed: Float) {
        _speed.value = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        prefs.edit().putFloat(KEY_SPEED, _speed.value).apply()
    }

    /**
     * Check if TTS is properly configured (has API key and voice selected).
     */
    fun isConfigured(): Boolean {
        val hasKey = when (_provider.value) {
            TtsProvider.ELEVENLABS -> _apiKey.value.isNotBlank()
            TtsProvider.OPENAI -> _openAiApiKey.value.isNotBlank()
        }
        return hasKey && _selectedVoiceId.value != null
    }

    private fun activeVoiceId(provider: TtsProvider): String? = when (provider) {
        TtsProvider.ELEVENLABS -> prefs.getString(KEY_VOICE_ID, null)
        TtsProvider.OPENAI -> prefs.getString(KEY_OPENAI_VOICE, DEFAULT_OPENAI_VOICE)
    }

    private fun activeVoiceName(provider: TtsProvider): String? = when (provider) {
        TtsProvider.ELEVENLABS -> prefs.getString(KEY_VOICE_NAME, null)
        TtsProvider.OPENAI -> activeVoiceId(provider)?.replaceFirstChar { it.uppercase() }
    }

    companion object {
        private const val PREFS_NAME = "clawsses"
        private const val KEY_API_KEY = "tts_api_key"
        private const val KEY_VOICE_ID = "tts_voice_id"
        private const val KEY_VOICE_NAME = "tts_voice_name"
        private const val KEY_ENABLED = "tts_enabled"
        private const val KEY_SPEED = "tts_speed"
        private const val KEY_PROVIDER = "tts_provider"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_VOICE = "tts_openai_voice"
        const val DEFAULT_OPENAI_VOICE = "coral"
        const val DEFAULT_SPEED = 1.0f
        const val MIN_SPEED = 0.7f
        const val MAX_SPEED = 1.2f
    }
}
