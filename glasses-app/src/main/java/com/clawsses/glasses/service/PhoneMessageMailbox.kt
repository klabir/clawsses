package com.clawsses.glasses.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Keeps a bounded set of phone messages across HUD Activity recreation. */
internal class PhoneMessageMailbox(
    capacity: Int = Channel.BUFFERED,
) {
    private val channel = Channel<String>(capacity)

    val messages: Flow<String> = channel.receiveAsFlow()

    fun publish(message: String): Boolean = channel.trySend(message).isSuccess
}
