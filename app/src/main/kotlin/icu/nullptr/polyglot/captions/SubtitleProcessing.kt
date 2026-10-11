package icu.nullptr.polyglot.captions

import com.google.gson.JsonParser

/** Milliseconds, preserving fractional word times when a coarse ASR segment is expanded. */
data class SubtitleWord(val text: String, val start: Double, val end: Double)
data class SubtitleCue(val start: Double, val end: Double, val text: String, val translation: String = "", val draft: Boolean = false)
data class TimedTextSegment(val text: String, val offsetMs: Double = 0.0, val asr: Boolean = false)
data class TimedTextEvent(val startMs: Double, val durationMs: Double, val segments: List<TimedTextSegment>, val append: Boolean = false)
data class PreparedSubtitles(val events: List<TimedTextEvent>, val words: List<SubtitleWord>)

/** Kotlin adaptation of kiss-translator's youtubeSubtitleProcessing.js, df3e1e3. */
object SubtitleProcessing {
    private val nonSpeech = Regex("^(?:>>\\s*)?(?:\\[[^\\]\\r\\n]+\\]\\s*)+$")
    private val noSpaceLanguages = listOf("zh", "ja", "ko", "th", "lo", "km", "my")
    private val pauseWords = setOf("actually", "also", "although", "and", "anyway", "as", "basically", "because", "but", "eventually", "frankly", "honestly", "hopefully", "however", "if", "instead", "it's", "just", "let's", "like", "literally", "maybe", "meanwhile", "nevertheless", "nonetheless", "now", "okay", "or", "otherwise", "perhaps", "personally", "probably", "right", "since", "so", "suddenly", "that's", "then", "there's", "therefore", "though", "thus", "unless", "until", "well", "while")
    fun isNonSpeech(text: String) = nonSpeech.matches(text.trim())
    fun isNoSpace(language: String) = noSpaceLanguages.any { language.startsWith(it) }
    fun clean(text: String) = text.replace(Regex("<[^>]+>"), "").replace("\u200B", "").trim().replace(Regex("\\s+"), " ")

    fun parseJson3(json: String): List<TimedTextEvent> = JsonParser.parseString(json).asJsonObject["events"].asJsonArray.map { value ->
        val event = value.asJsonObject
        TimedTextEvent(
            event["tStartMs"]?.asDouble ?: 0.0,
            event["dDurationMs"]?.asDouble ?: 0.0,
            event["segs"]?.asJsonArray?.map { item ->
                val segment = item.asJsonObject
                TimedTextSegment(segment["utf8"]?.asString.orEmpty(), segment["tOffsetMs"]?.asDouble ?: 0.0, segment.has("acAsrConf"))
            }.orEmpty(),
            event["aAppend"]?.asInt == 1,
        )
    }

    fun prepare(raw: List<TimedTextEvent>, language: String): PreparedSubtitles {
        val events = ArrayList<TimedTextEvent>()
        val words = ArrayList<SubtitleWord>()
        var buffer: SubtitleWord? = null
        var lastKey = ""
        fun key(event: TimedTextEvent): String {
            val text = clean(event.segments.joinToString(" ") { it.text })
            return if (text.isEmpty()) "" else "${event.startMs}|${event.durationMs}|$text"
        }
        fun flush(endAt: Double) {
            buffer?.let {
                val end = if (!it.end.isFinite() || it.end == 0.0 || it.end > endAt) endAt else it.end
                if (end.isFinite() && end > it.start) words += it.copy(end = end)
            }
            buffer = null
        }
        raw.forEachIndexed { eventIndex, source ->
            val eventKey = key(source)
            if (eventKey.isNotEmpty() && eventKey == lastKey) return@forEachIndexed
            val lineBreak = source.append && source.segments.size == 1 && source.segments[0].text == "\n"
            val event = source.copy(segments = source.segments.map { it.copy(text = if (lineBreak) "\n" else clean(it.text)) })
            events += event
            lastKey = eventKey
            event.segments.forEachIndexed { index, segment ->
                val text = clean(segment.text)
                val start = event.startMs + segment.offsetMs
                if (text.isEmpty()) {
                    if (buffer == null || start > buffer!!.start) flush(start)
                } else if (isNonSpeech(text)) {
                    flush(start)
                } else {
                    flush(start)
                    val last = index == event.segments.lastIndex
                    val declaredEnd = event.startMs + if (last) event.durationMs else event.segments[index + 1].offsetMs
                    var nextStart = Double.NaN
                    var nextKey = lastKey
                    for (nextIndex in eventIndex + 1 until raw.size) {
                        val next = raw[nextIndex]
                        val candidateKey = key(next)
                        if (candidateKey.isNotEmpty() && candidateKey == nextKey) continue
                        nextKey = candidateKey
                        if (next.startMs > start) { nextStart = next.startMs; break }
                    }
                    val effectiveEnd = if (last && nextStart.isFinite()) minOf(declaredEnd, nextStart) else declaredEnd
                    val split = text.split(' ').filter { it.isNotEmpty() }
                    if (segment.asr && !isNoSpace(language) && split.size > 1 && effectiveEnd > start) {
                        val total = split.sumOf { it.length }.toDouble()
                        var weight = 0
                        split.forEachIndexed { wordIndex, word ->
                            val wordStart = start + (effectiveEnd - start) * weight / total
                            weight += word.length
                            val wordEnd = if (wordIndex == split.lastIndex) effectiveEnd else start + (effectiveEnd - start) * weight / total
                            val expanded = SubtitleWord(word, wordStart, wordEnd)
                            if (wordIndex == split.lastIndex) buffer = expanded else words += expanded
                        }
                    } else {
                        buffer = SubtitleWord(text, start, if (last) event.startMs + event.durationMs else Double.NaN)
                    }
                }
            }
        }
        flush(buffer?.end ?: Double.NaN)
        return PreparedSubtitles(events, words.filter { it.start.isFinite() && it.end.isFinite() && it.end > it.start })
    }

    fun segment(prepared: PreparedSubtitles, language: String, mode: String = "rule", longThreshold: Int = 100): List<SubtitleCue> {
        if (mode == "statistical") {
            val result = StatisticalSentenceBreaker.segment(prepared.events)
            if (result.isNotEmpty()) return result
        }
        return rule(prepared.words, language, longThreshold)
    }

    fun rule(words: List<SubtitleWord>, language: String, longThreshold: Int = 100): List<SubtitleCue> {
        if (words.isEmpty()) return emptyList()
        if (isNoSpace(language)) {
            if (words.count { it.text.length > 5 }.toDouble() / words.size > 0.5) return words.map { SubtitleCue(it.start, it.end, it.text) }
            val result = ArrayList<SubtitleCue>()
            var current: SubtitleCue? = null
            for (word in words) {
                if (current != null && word.start - current.end > 1000) { result += current; current = null }
                current = current?.copy(text = current.text + word.text, end = word.end) ?: SubtitleCue(word.start, word.end, word.text)
                if (Regex("[。！？.!?…][”’\"'」』】）》）\\]]*$").containsMatchIn(word.text) || current.text.length >= 30) {
                    result += current; current = null
                }
            }
            current?.let { result += it }
            return result
        }
        return process(words).flatMap { cue ->
            val subWords = words.filter { it.start >= cue.start && it.start < cue.end }
            if (cue.text.length > longThreshold && subWords.size > 1) process(subWords, true) else listOf(cue)
        }
    }

    private fun process(words: List<SubtitleWord>, usePause: Boolean = false): List<SubtitleCue> {
        val result = ArrayList<SubtitleCue>()
        val buffer = ArrayList<SubtitleWord>()
        var count = 0
        fun flush() {
            if (buffer.isNotEmpty()) result += SubtitleCue(buffer.first().start, buffer.last().end, buffer.joinToString(" ") { it.text }.trim())
            buffer.clear(); count = 0
        }
        for (word in words) {
            if (word.text.isEmpty()) continue
            buffer.lastOrNull()?.let { last ->
                if (Regex("[.?!…\\])]$").containsMatchIn(last.text) || word.start - last.end > 1000 ||
                    word.start - buffer.first().start >= 10000 ||
                    ((usePause || last.text.endsWith(',')) && count >= 15) ||
                    Regex("^[\\[(♪]").containsMatchIn(word.text) ||
                    (usePause && word.text.lowercase().substringBefore(' ') in pauseWords && buffer.size > 1)) flush()
            }
            buffer += word; count += word.text.split(Regex("\\s+")).size
        }
        flush()
        return result
    }

    fun chunks(words: List<SubtitleWord>, length: Int = 1000): List<List<SubtitleWord>> {
        val result = ArrayList<List<SubtitleWord>>()
        val buffer = ArrayList<SubtitleWord>()
        val maximum = length.coerceAtLeast(1)
        var count = 0
        fun flush() { if (buffer.isNotEmpty()) result += buffer.toList(); buffer.clear(); count = 0 }
        words.forEachIndexed { index, word ->
            if (buffer.isNotEmpty() && count + word.text.length > maximum) flush()
            buffer += word; count += word.text.length
            if (index < words.lastIndex && count >= (maximum * 0.8).toInt() &&
                (Regex("[.?!…\\])]$").containsMatchIn(word.text) || words[index + 1].start - word.end > 1000)) flush()
        }
        flush()
        return result
    }
}
