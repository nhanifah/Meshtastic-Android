/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.webbridge

import co.touchlab.kermit.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.ByteString
import org.meshtastic.core.repository.ConfigDumpProvider
import org.meshtastic.core.repository.RadioTransportWriter

/** Outcome of a browser's `PUT /api/v1/toradio`. */
enum class SubmitResult {
    /** Forwarded to the radio, answered locally, or deliberately dropped. The browser sees success in all three. */
    ACCEPTED,

    /** The phone cannot serve this right now (no radio session, or no config to answer with yet). */
    UNAVAILABLE,
}

/**
 * Connects browsers to the radio the phone already owns.
 *
 * Each browser session gets its own queue of `FromRadio` frames to poll:
 * - **Config dumps** answered by [ConfigDumpProvider] go into an unbounded per-client list that is served first. A dump
 *   is bounded by the node count and must never lose frames, or the web client stalls waiting for `config_complete_id`.
 * - **Live frames** from [radioFrames] go into a bounded per-client buffer that drops the oldest frame when a browser
 *   stops polling. A slow tab can never stall the phone or the other tabs.
 *
 * A client that has not polled for [idleTimeoutMillis] is dropped. Its session stays valid, so its next poll starts a
 * fresh queue.
 */
class WebBridgeHub(
    private val radioFrames: Flow<ByteString>,
    private val radioWriter: RadioTransportWriter,
    private val configDumpProvider: ConfigDumpProvider,
    private val clockMillis: () -> Long,
    private val liveQueueCapacity: Int = DEFAULT_LIVE_QUEUE_CAPACITY,
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
) {
    private class Client(var lastPollMillis: Long) {
        val dump = ArrayDeque<ByteArray>()
        val live = ArrayDeque<ByteArray>()
        var droppedLive = 0L
    }

    private val lock = SynchronizedObject()
    private val clients = HashMap<String, Client>()
    private var jobs: List<Job> = emptyList()

    /** Starts fan-out and idle cleanup in [scope]. Calling it again restarts both. */
    fun start(scope: CoroutineScope) {
        stop()
        jobs =
            listOf(
                scope.launch { radioFrames.collect { fanOut(it.toByteArray()) } },
                scope.launch {
                    while (isActive) {
                        delay(idleTimeoutMillis / 2)
                        sweepIdle()
                    }
                },
            )
    }

    /** Stops fan-out and forgets every client queue. */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        synchronized(lock) { clients.clear() }
    }

    /** Returns the next frame for [sessionId], or null when it has nothing queued. Never blocks. */
    fun poll(sessionId: String): ByteArray? = synchronized(lock) {
        val client = clients.getOrPut(sessionId) { Client(clockMillis()) }
        client.lastPollMillis = clockMillis()
        client.dump.removeFirstOrNull() ?: client.live.removeFirstOrNull()
    }

    /** Handles one `ToRadio` frame from [sessionId]. */
    suspend fun submit(sessionId: String, bytes: ByteArray): SubmitResult =
        when (val decision = ToRadioFilter.decide(bytes)) {
            ToRadioDecision.Forward ->
                if (radioWriter.trySendToRadio(bytes)) SubmitResult.ACCEPTED else SubmitResult.UNAVAILABLE

            is ToRadioDecision.AnswerConfig -> {
                val frames = configDumpProvider.configDump(decision.nonce)
                if (frames == null) {
                    SubmitResult.UNAVAILABLE
                } else {
                    synchronized(lock) {
                        val client = clients.getOrPut(sessionId) { Client(clockMillis()) }
                        // A new handshake supersedes any half-read earlier dump, as it would on a real radio.
                        client.dump.clear()
                        frames.forEach { client.dump.addLast(it.encode()) }
                    }
                    SubmitResult.ACCEPTED
                }
            }

            is ToRadioDecision.Drop -> {
                Logger.d { "Web bridge dropped a browser frame (${decision.reason})" }
                SubmitResult.ACCEPTED
            }
        }

    /** Forgets [sessionId]'s queue, for example after the session is revoked. */
    fun forget(sessionId: String) {
        synchronized(lock) { clients.remove(sessionId) }
    }

    /** Number of browsers currently polling. */
    val activeClientCount: Int
        get() = synchronized(lock) { clients.size }

    private fun fanOut(frame: ByteArray) = synchronized(lock) {
        clients.values.forEach { client ->
            if (client.live.size >= liveQueueCapacity) {
                client.live.removeFirst()
                client.droppedLive++
                if (client.droppedLive == 1L || client.droppedLive % DROP_LOG_INTERVAL == 0L) {
                    Logger.w { "Web bridge client is not polling; dropped ${client.droppedLive} live frames" }
                }
            }
            client.live.addLast(frame)
        }
    }

    private fun sweepIdle() = synchronized(lock) {
        val cutoff = clockMillis() - idleTimeoutMillis
        clients.entries.removeAll { it.value.lastPollMillis < cutoff }
    }

    companion object {
        const val DEFAULT_LIVE_QUEUE_CAPACITY = 1024
        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 60_000L
        private const val DROP_LOG_INTERVAL = 256L
    }
}
