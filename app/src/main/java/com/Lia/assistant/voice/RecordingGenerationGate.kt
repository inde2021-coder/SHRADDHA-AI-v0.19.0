package com.Lia.assistant.voice

import java.util.concurrent.atomic.AtomicLong

/**
 * Prevents a stale AudioRecord thread from mutating the active state of a newer capture session.
 * A token becomes invalid as soon as stop/restart advances the generation.
 */
internal class RecordingGenerationGate {
    private val current = AtomicLong(0)

    fun begin(): Long = current.incrementAndGet()

    fun invalidate(): Long = current.incrementAndGet()

    fun isCurrent(token: Long): Boolean = token != 0L && current.get() == token

    /** Only the current recording thread may finish its own generation. */
    fun finish(token: Long): Boolean = token != 0L && current.compareAndSet(token, token + 1)
}
