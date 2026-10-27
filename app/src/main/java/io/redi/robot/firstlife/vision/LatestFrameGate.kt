package io.redi.robot.firstlife.vision

/** Holds at most one in-flight and one latest pending frame; every frame is released exactly once. */
class LatestFrameGate<T : Any>(
    private val process: (T, () -> Unit) -> Unit,
    private val release: (T) -> Unit,
) {
    private class Entry<T : Any>(val value: T, var released: Boolean = false)
    private var current: Entry<T>? = null
    private var pending: Entry<T>? = null
    private var closed = false

    fun offer(value: T) {
        val entry = Entry(value)
        var start: Entry<T>? = null
        var displaced: Entry<T>? = null
        synchronized(this) {
            if (closed) displaced = entry
            else if (current == null) {
                current = entry
                start = entry
            } else {
                displaced = pending
                pending = entry
            }
        }
        displaced?.let(::releaseOnce)
        start?.let(::start)
    }

    fun close() {
        val entries = synchronized(this) {
            if (closed) return
            closed = true
            listOfNotNull(current, pending).also {
                current = null
                pending = null
            }
        }
        entries.forEach(::releaseOnce)
    }

    private fun start(entry: Entry<T>) {
        process(entry.value) { complete(entry) }
    }

    private fun complete(entry: Entry<T>) {
        var next: Entry<T>? = null
        synchronized(this) {
            if (current === entry) {
                current = null
                if (!closed) {
                    next = pending
                    pending = null
                    current = next
                }
            }
        }
        releaseOnce(entry)
        next?.let(::start)
    }

    private fun releaseOnce(entry: Entry<T>) {
        val shouldRelease = synchronized(entry) {
            if (entry.released) false else true.also { entry.released = true }
        }
        if (shouldRelease) release(entry.value)
    }
}
