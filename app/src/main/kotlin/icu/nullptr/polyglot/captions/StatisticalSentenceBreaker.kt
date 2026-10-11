package icu.nullptr.polyglot.captions

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** Kotlin adaptation of kiss-translator's sentenceBreaker.js, with its default parameters. */
object StatisticalSentenceBreaker {
    private data class Word(val text: String, val start: Double, val end: Double, val event: Int) {
        val punctuation get() = text.trim().lastOrNull() in listOf('.', '?', '!')
        val comma get() = text.trim().endsWith(',')
        val capital get() = text.trim().firstOrNull()?.let { it.toString() == it.toString().uppercase() } == true
        val allCaps get() = text.trim().let { it.length >= 2 && it == it.uppercase() }
        val arrow get() = text.startsWith(">>")
    }
    private data class Gap(val value: Double, val prev: Word, val next: Word, val lineBreak: Boolean) {
        val sameEvent get() = prev.event == next.event
    }
    private fun percentile(values: List<Double>, percent: Int): Double {
        if (values.isEmpty()) return 0.0
        val index = (values.size - 1) * percent / 100.0
        val low = floor(index).toInt(); val high = ceil(index).toInt()
        return if (low == high) values[low] else values[low] * (high - index) + values[high] * (index - low)
    }

    fun segment(events: List<TimedTextEvent>): List<SubtitleCue> {
        val words = ArrayList<Word>()
        val lineBreaks = events.filter { it.append && it.segments.size == 1 && it.segments[0].text == "\n" }.map { it.startMs }
        events.forEachIndexed { eventIndex, event ->
            if (event.append && event.segments.size == 1 && event.segments[0].text == "\n") return@forEachIndexed
            event.segments.forEachIndexed { index, segment ->
                if (segment.text.isNotEmpty() && segment.text != "\n" && !SubtitleProcessing.isNonSpeech(segment.text)) {
                    words += Word(segment.text, event.startMs + segment.offsetMs,
                        event.startMs + (event.segments.getOrNull(index + 1)?.offsetMs ?: event.durationMs), eventIndex)
                }
            }
        }
        if (words.isEmpty()) return emptyList()
        val gaps = words.zipWithNext { a, b -> Gap((b.start - a.start).coerceAtLeast(0.0), a, b, lineBreaks.any { it in a.start..b.start }) }
        val same = gaps.filter { it.sameEvent }
        val cross = gaps.filter { !it.sameEvent }
        val fills = if (same.size < 10) emptySet() else same.groupingBy { it.value }.eachCount().filter { (value, count) ->
            count.toDouble() / same.size >= 0.08 && cross.count { it.value == value }.toDouble() / cross.size.coerceAtLeast(1) < 0.02
        }.keys
        val values = gaps.map { it.value }.filter { it !in fills }.sorted()
        val mean = if (values.isEmpty()) 0.0 else values.average()
        val median = percentile(values, 50)
        val std = if (values.size < 2) 0.0 else sqrt(values.sumOf { (it - mean).pow(2) } / (values.size - 1))
        val sigma = percentile(values.map { abs(it - median) }.sorted(), 50) * 1.4826
        val p75 = percentile(values, 75); val p90 = percentile(values, 90); val p95 = percentile(values, 95)
        val scores = gaps.map { gap ->
            if (gap.value <= 0) -100.0 else {
                var score = 0.0
                if (gap.prev.punctuation) score += 2.5
                if (gap.prev.comma) score += 1.0
                if (gap.next.capital && !gap.next.allCaps) score += 0.5
                if (gap.prev.punctuation && gap.next.capital && !gap.next.allCaps) score += 1.0
                val classical = if (std > 0) (gap.value - mean) / std else 0.0
                val robust = if (sigma > 0) (gap.value - median) / sigma else 0.0
                score += minOf(classical, robust).coerceAtLeast(0.0) * 2
                val dampen = if (gap.sameEvent && gap.value in fills) 0.4 else 1.0
                if (gap.value >= p75) score += 0.5 * dampen
                if (gap.value >= p90) score += dampen
                if (gap.value >= p95) score += 1.5 * dampen
                if (gap.value >= 500) score += 0.3
                if (gap.value >= 800) score += 0.5
                if (gap.value >= 1500) score += 1.0
                if (gap.value >= 3000) score += 1.5
                if (gap.lineBreak) score += 0.8
                score + if (gap.sameEvent) -1.0 else 0.3
            }
        }
        val boundaries = ArrayList<Int>()
        var start = 0; var best = -1; var bestScore = Double.NEGATIVE_INFINITY
        fun cut(index: Int) { boundaries += index; start = index; best = -1; bestScore = Double.NEGATIVE_INFINITY }
        gaps.forEachIndexed { index, gap ->
            val duration = gap.next.end - words[start].start
            val count = index + 2 - start
            if (gap.next.arrow && duration >= 100 && count >= 4) { cut(index + 1); return@forEachIndexed }
            if (gap.prev.punctuation && gap.next.capital && !gap.next.allCaps && count >= 6 && duration >= 2000 && words.size - index - 1 >= 4) {
                cut(index + 1); return@forEachIndexed
            }
            if (scores[index] > bestScore && index - start + 1 > 0 && words[index].end - words[start].start >= 500) {
                bestScore = scores[index]; best = index
            }
            if (duration >= 7000 || count >= 30) { cut((if (best >= 0) best else index) + 1); return@forEachIndexed }
            if (count >= 4 && scores[index] >= 1.2) {
                val threshold = if (gap.prev.punctuation && count < 6) Double.POSITIVE_INFINITY else if (gap.sameEvent) 1.2 * 1.5 else 1.2
                if (scores[index] >= threshold) cut(index + 1)
            }
        }
        val merged = ArrayList<Int>()
        var previous = 0
        for (boundary in boundaries.distinct().sorted()) {
            if (boundary - previous < 4 && merged.isNotEmpty() && !words[boundary].arrow) {
                val gap = if (previous > 0) words[boundary].start - words[previous - 1].end else 0.0
                val newStart = merged.getOrNull(merged.size - 2) ?: 0
                if (gap <= 3000 && words[boundary - 1].end - words[newStart].start <= 7000) {
                    merged.removeAt(merged.lastIndex); previous = merged.lastOrNull() ?: 0; continue
                }
            }
            merged += boundary; previous = boundary
        }
        if (words.size - previous < 4 && merged.isNotEmpty()) {
            val boundary = merged.last()
            val newStart = merged.getOrNull(merged.size - 2) ?: 0
            if (!words[boundary].arrow && words[boundary].start - words[boundary - 1].end <= 3000 && words.last().end - words[newStart].start <= 7000) merged.removeAt(merged.lastIndex)
        }
        val points = listOf(0) + merged + words.size
        val result = ArrayList<SubtitleCue>()
        points.zipWithNext().forEach { (a, b) ->
            val slice = words.subList(a, b)
            if (slice.isNotEmpty()) {
                val duration = slice.last().end - slice.first().start
                val parts = ceil(duration / 7000).toInt().coerceAtLeast(1)
                val size = if (slice.size <= 1) 1 else ceil(slice.size.toDouble() / parts).toInt().coerceAtLeast(1)
                slice.chunked(size).forEach { chunk ->
                    result += SubtitleCue(chunk.first().start, chunk.last().end, chunk.joinToString(" ") { it.text.trim() }.trim())
                }
            }
        }
        for (index in 0 until result.lastIndex) {
            if (result[index].end > result[index + 1].start) result[index] = result[index].copy(end = result[index + 1].start)
        }
        return result
    }
}
