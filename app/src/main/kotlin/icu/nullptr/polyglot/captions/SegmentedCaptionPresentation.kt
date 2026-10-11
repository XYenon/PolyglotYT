package icu.nullptr.polyglot.captions

import kotlin.math.ceil

/** Feed complete kiss segments to native caption windows, including later AI boundaries. */
object SegmentedCaptionPresentation {
    data class Interval(val startMs: Long, val endMs: Long, val text: String)

    fun intervals(words: List<SubtitleWord>, cues: List<SubtitleCue>): List<Interval> {
        val boundaries = (words.flatMap { listOf(it.start, it.end) } + cues.flatMap { listOf(it.start, it.end) })
            .filter { it.isFinite() && it >= 0 }
            // Native events use integer milliseconds. Ceil preserves half-open cue membership
            // at those times, including fractional timestamps from expanded ASR words.
            .map { ceil(it).toLong() }.distinct().sorted()
        val scheduler = SubtitleScheduler()
        return boundaries.zipWithNext { start, end ->
            Interval(start, end, scheduler.cueAt(cues, start.toDouble())?.text.orEmpty())
        }
    }

    fun source(nativeText: CharSequence, cue: SubtitleCue?, hasTimeline: Boolean): CharSequence =
        if (hasTimeline) cue?.text.orEmpty() else nativeText
}
