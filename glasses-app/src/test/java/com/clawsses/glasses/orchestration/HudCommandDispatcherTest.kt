package com.clawsses.glasses.orchestration

import com.clawsses.shared.GlassesCommand
import com.clawsses.shared.GlassesCommandCodec
import com.clawsses.shared.GlassesCommandDecodeResult
import org.junit.Assert.assertEquals
import org.junit.Test

class HudCommandDispatcherTest {
    @Test
    fun `dispatcher encodes typed commands at the transport boundary`() {
        var payload: String? = null
        val command = GlassesCommand.RequestState(
            versionCode = 103,
            versionName = "1.3.94",
            protocolVersion = 4,
            capabilities = setOf("history_snapshot", "peer_state"),
        )

        HudCommandDispatcher { payload = it }.send(command)

        assertEquals(
            GlassesCommandDecodeResult.Success(command),
            GlassesCommandCodec.decode(requireNotNull(payload)),
        )
    }
}
