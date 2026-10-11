package icu.nullptr.polyglot.captions

import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class CaptionSession {
    private val translations = ConcurrentHashMap<String, String>()
    private val observedCues = ConcurrentHashMap<String, CaptionCue>()
    private val renderedTexts = ConcurrentHashMap<String, Long>()
    private val captionEventBatches = ArrayDeque<CaptionEventBatch>()
    private var captionTimeline = emptyList<TimedCaption>()
    private var captionTimelineTextByStartMs = emptyMap<Long, String>()
    private var captionTimelineCursor = -1
    private val observedCueCounter = AtomicInteger(0)

    fun translationFor(original: String?): String? {
        if (original == null) return null
        return translations[normalize(original)]
    }

    fun putTranslation(original: String?, translated: String?) {
        if (original == null || translated == null) return
        translations[normalize(original)] = translated
    }

    fun clearTranslations() {
        translations.clear()
    }

    fun clear() {
        clearTranslations()
        observedCues.clear()
        renderedTexts.clear()
        synchronized(captionEventBatches) {
            captionEventBatches.clear()
            captionTimeline = emptyList()
            captionTimelineTextByStartMs = emptyMap()
            captionTimelineCursor = -1
        }
        observedCueCounter.set(0)
    }

    fun replaceCaptionTimeline(entries: Iterable<Pair<Long, String>>): Int {
        val normalizedEntries = ArrayList<TimedCaption>()
        for ((startMs, text) in entries) {
            val normalized = normalize(text)
            if (normalized.isEmpty()) continue
            normalizedEntries += TimedCaption(startMs, normalized)
        }

        synchronized(captionEventBatches) {
            if (captionTimeline == normalizedEntries &&
                (normalizedEntries.isNotEmpty() || captionEventBatches.isEmpty())
            ) {
                return UNCHANGED_TIMELINE
            }

            captionTimeline = normalizedEntries
            captionTimelineTextByStartMs = normalizedEntries.associate { entry ->
                entry.startMs to entry.text
            }
            captionTimelineCursor = -1
            captionEventBatches.clear()
        }
        return normalizedEntries.size
    }

    fun timelineCaptionTextAt(startMs: Long): String? {
        if (startMs == CaptionCue.UNKNOWN_TIME_MS) return null
        synchronized(captionEventBatches) {
            captionTimelineTextByStartMs[startMs]?.let { return it }

            var low = 0
            var high = captionTimeline.lastIndex
            while (low <= high) {
                val middle = (low + high).ushr(1)
                if (captionTimeline[middle].startMs <= startMs) {
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            if (high < 0) return null

            val floor = captionTimeline[high]
            return floor.text.takeIf {
                startMs - floor.startMs <= MAX_TIMELINE_LOOKUP_GAP_MS
            }
        }
    }

    fun observeCaptionEventBatch(texts: Iterable<String>): Int {
        val normalizedTexts = ArrayList<String>()
        for (text in texts) {
            val normalized = normalize(text)
            if (normalized.isEmpty() || normalizedTexts.lastOrNull() == normalized) continue
            normalizedTexts += normalized
        }
        if (normalizedTexts.isEmpty()) return 0

        synchronized(captionEventBatches) {
            captionEventBatches.addFirst(CaptionEventBatch(normalizedTexts))
            while (captionEventBatches.size > MAX_CAPTION_EVENT_BATCHES) {
                captionEventBatches.removeLast()
            }
        }
        return normalizedTexts.size
    }

    fun upcomingCaptionTexts(currentText: String?, currentStartMs: Long, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val normalizedCurrent = normalize(currentText.orEmpty())
        if (normalizedCurrent.isEmpty()) return emptyList()

        synchronized(captionEventBatches) {
            val matchingTimelineIndices = captionTimeline.indices.filter { index ->
                captionTimeline[index].text == normalizedCurrent
            }
            if (matchingTimelineIndices.isNotEmpty()) {
                val currentIndex = if (currentStartMs != CaptionCue.UNKNOWN_TIME_MS) {
                    matchingTimelineIndices.minBy { index ->
                        absoluteDifference(captionTimeline[index].startMs, currentStartMs)
                    }
                } else {
                    matchingTimelineIndices.firstOrNull { index -> index >= captionTimelineCursor }
                        ?: matchingTimelineIndices.first()
                }
                captionTimelineCursor = currentIndex
                return upcomingTexts(
                    texts = captionTimeline.map { entry -> entry.text },
                    currentIndex = currentIndex,
                    limit = limit,
                )
            }

            var bestMatch = emptyList<String>()
            for (batch in captionEventBatches) {
                val currentIndex = if (batch.lastQuery == normalizedCurrent && batch.cursor >= 0) {
                    batch.cursor
                } else {
                    batch.texts.indices.firstOrNull { index ->
                        index > batch.cursor && batch.texts[index] == normalizedCurrent
                    } ?: batch.texts.indexOf(normalizedCurrent)
                }
                if (currentIndex < 0) continue

                batch.cursor = currentIndex
                batch.lastQuery = normalizedCurrent
                val upcoming = upcomingTexts(batch.texts, currentIndex, limit)
                if (upcoming.size > bestMatch.size) {
                    bestMatch = upcoming
                    if (bestMatch.size >= limit) break
                }
            }
            return bestMatch
        }
    }

    private fun upcomingTexts(texts: List<String>, currentIndex: Int, limit: Int): List<String> {
        val upcoming = LinkedHashSet<String>(limit)
        for (index in currentIndex until texts.size) {
            upcoming += texts[index]
            if (upcoming.size >= limit) break
        }
        return upcoming.toList()
    }

    fun observeCue(cue: CaptionCue): Boolean {
        if (cue.normalizedText().isEmpty()) return false
        val previous = observedCues.putIfAbsent(cue.cacheKey(), cue)
        if (previous == null) {
            observedCueCounter.incrementAndGet()
            return true
        }
        return false
    }

    fun observeNewCues(cues: Iterable<CaptionCue>): List<CaptionCue> {
        val newCues = ArrayList<CaptionCue>()
        for (cue in cues) {
            if (observeCue(cue)) {
                newCues += cue
            }
        }
        return newCues
    }

    fun observeRenderedText(text: CharSequence?): Boolean {
        val normalized = normalize(text?.toString().orEmpty())
        if (normalized.isEmpty()) return false
        val now = System.currentTimeMillis()
        val previous = renderedTexts.put(normalized, now)
        return previous == null || now - previous > RENDERED_TEXT_LOG_INTERVAL_MS
    }

    fun observedCueCount(): Int = observedCueCounter.get()

    private fun normalize(text: String): String =
        CaptionCue.normalize(text)

    private fun absoluteDifference(first: Long, second: Long): Long =
        if (first >= second) first - second else second - first

    private data class CaptionEventBatch(
        val texts: List<String>,
        var cursor: Int = -1,
        var lastQuery: String? = null,
    )

    private data class TimedCaption(
        val startMs: Long,
        val text: String,
    )

    private companion object {
        const val UNCHANGED_TIMELINE = -1
        const val MAX_CAPTION_EVENT_BATCHES = 8
        const val MAX_TIMELINE_LOOKUP_GAP_MS = 5_000L
        const val RENDERED_TEXT_LOG_INTERVAL_MS = 30_000L
    }
}
