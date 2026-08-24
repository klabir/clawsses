package com.clawsses.shared

/** Voice commands that stop only the current spoken response. */
object TtsVoiceCommands {
    const val STOP_CURRENT_OUTPUT = "stop voice"

    private val stopPhrases = setOf(
        STOP_CURRENT_OUTPUT,
        "stop speaking",
        "stop reading",
        "stop voice output",
        "stop the voice output",
        "stop playback",
        "stop the playback",
        "stop tts",
        "stop tts output",
        "stop the tts output",
        "tts stoppen",
        "stopp tts",
        "sprachausgabe stoppen",
        "sprachausgabe beenden",
        "stoppe die sprachausgabe",
        "sprache stoppen",
        "vorlesen stoppen",
        "vorlesen beenden",
        "stopp vorlesen",
    )

    /** Returns the canonical command only for an unambiguous TTS-stop phrase. */
    fun match(text: String): String? {
        var normalized = text
            .lowercase()
            .trim()
            .trimEnd('.', ',', '!', '?', ';', ':')
            .trim()
        normalized = normalized
            .removePrefix("please ")
            .removePrefix("bitte ")
            .removeSuffix(" please")
            .removeSuffix(" bitte")
            .trim()
            .trimEnd('.', ',', '!', '?', ';', ':')
            .trim()
        return if (normalized in stopPhrases) STOP_CURRENT_OUTPUT else null
    }

    fun isStopCurrentOutput(command: String): Boolean = match(command) != null
}
