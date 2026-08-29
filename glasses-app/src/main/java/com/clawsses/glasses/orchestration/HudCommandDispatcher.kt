package com.clawsses.glasses.orchestration

import com.clawsses.shared.GlassesCommand
import com.clawsses.shared.GlassesCommandCodec

/** Keeps transport encoding out of the Activity's UI and lifecycle orchestration. */
class HudCommandDispatcher(
    private val sendEncoded: (String) -> Unit,
) {
    fun send(command: GlassesCommand) {
        sendEncoded(GlassesCommandCodec.encode(command))
    }
}
