package icu.nullptr.polyglot.captions

/** Playback position and monotonic time have separate roles; pausing does not advance captions. */
class SubtitleScheduler {
    @Volatile var position = Double.NaN
        private set
    private var lastPrefetchNanos = 0L
    private var lastPlaybackNanos = 0L
    @Synchronized fun reset() { position = Double.NaN; lastPrefetchNanos = 0; lastPlaybackNanos = 0 }
    @Synchronized fun shouldPrefetch(timeMs: Double, throttleSeconds: Int, force: Boolean = false, now: Long = System.nanoTime()): Boolean {
        if (!timeMs.isFinite() || timeMs < 0) return false
        val elapsedMs = if (lastPlaybackNanos == 0L) 0.0 else (now - lastPlaybackNanos) / 1_000_000.0
        val movement = timeMs - position
        val seek = position.isFinite() && (movement < -250 || movement > elapsedMs * 2 + 2500)
        position = timeMs; lastPlaybackNanos = now
        if (!force && !seek && lastPrefetchNanos != 0L && now - lastPrefetchNanos < throttleSeconds.coerceAtLeast(0) * 1_000_000_000L) return false
        lastPrefetchNanos = now
        return true
    }
    fun cueAt(cues: List<SubtitleCue>, timeMs: Double = position): SubtitleCue? {
        if (!timeMs.isFinite()) return null
        var low = 0; var high = cues.lastIndex
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (cues[mid].start <= timeMs) low = mid + 1 else high = mid - 1
        }
        return cues.getOrNull(high)?.takeIf { timeMs < it.end }
    }
    fun upcoming(cues: List<SubtitleCue>, timeMs: Double, aheadSeconds: Int): List<String> {
        val end = timeMs + aheadSeconds.coerceAtLeast(0) * 1000.0
        return cues.asSequence().filter { it.end >= timeMs && it.start <= end && (it.translation.isEmpty() || it.draft) }.map { it.text }.distinct().toList()
    }
}
