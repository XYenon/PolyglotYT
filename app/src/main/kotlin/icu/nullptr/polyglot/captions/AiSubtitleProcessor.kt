package icu.nullptr.polyglot.captions

import com.google.gson.Gson
import icu.nullptr.polyglot.translate.BatchResponseParser
import icu.nullptr.polyglot.translate.TranslationManager
import icu.nullptr.polyglot.translate.TranslationRequest
import icu.nullptr.polyglot.translate.providers.OpenAICompatibleTranslator

object AiSubtitleProcessor {
    data class Prefix(val cues: List<SubtitleCue>, val nextIndex: Int)
    fun prefix(content: String, words: List<SubtitleWord>, language: String, draft: Boolean = false): Prefix {
        var next = 0
        val cues = ArrayList<SubtitleCue>()
        for (item in BatchResponseParser.objects(BatchResponseParser.strip(content))) {
            val end = runCatching { (item["e"] ?: item["end_id"])?.asString?.toDouble()?.let { if (it % 1.0 == 0.0) it.toInt() else null } }.getOrNull()
            if (end == null || end < next || end >= words.size) break
            val source = words.subList(next, end + 1).joinToString(if (SubtitleProcessing.isNoSpace(language)) "" else " ") { it.text.trim() }.trim()
            val translation = (item["t"] ?: item["translation"])?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
            cues += SubtitleCue(words[next].start, words[end].end, source, BatchResponseParser.decode(translation), draft)
            next = end + 1
        }
        return Prefix(cues, next)
    }
    fun valid(cues: List<SubtitleCue>, words: List<SubtitleWord>): Boolean {
        fun normalize(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        return cues.isNotEmpty() && cues.all { it.text.isNotBlank() && it.start.isFinite() && it.end.isFinite() && it.end > it.start } &&
            cues.zipWithNext().all { (a, b) -> b.start >= a.end } && normalize(cues.joinToString("") { it.text }) == normalize(words.joinToString("") { it.text })
    }
    fun process(request: TranslationRequest, words: List<SubtitleWord>, language: String, draft: Boolean, threshold: Int,
        onStream: (List<SubtitleCue>) -> Unit): List<SubtitleCue> {
        val gson = Gson()
        val key = "subtitle-boundary-v3:" + TranslationManager.signature(request) + ":" + threshold + ":" + gson.toJson(words)
        TranslationManager.cached(key)?.let { stored ->
            runCatching { gson.fromJson(stored, Array<SubtitleCue>::class.java).toList() }.getOrNull()?.let {
                if (valid(it, words)) return it.map { cue -> cue.copy(draft = draft) }
            }
        }
        val accepted = ArrayList<SubtitleCue>()
        try {
            val response = TranslationManager.withRetry(request.cancellation) {
                OpenAICompatibleTranslator.subtitle(request, words) { content ->
                    val prefix = prefix(content, words, language, draft)
                    // Publish only closed, continuous sentences. Partial model objects do not change the timeline.
                    if (prefix.cues.size > accepted.size) {
                        accepted.clear(); accepted += prefix.cues
                        onStream(prefix.cues)
                    }
                }
            }
            val first = prefix(response, words, language, draft)
            accepted.clear(); accepted += first.cues
            var next = first.nextIndex
            if (next < words.size) {
                val tail = words.drop(next)
                val retry = runCatching {
                    OpenAICompatibleTranslator.subtitle(request, tail)
                }.getOrElse { request.cancellation.checkActive(); "" }
                val finalPrefix = prefix(retry, tail, language, draft)
                accepted += finalPrefix.cues; next += finalPrefix.nextIndex
            }
            if (next < words.size) accepted += SubtitleProcessing.rule(words.drop(next), language, threshold)
            if (valid(accepted, words)) {
                TranslationManager.cache(key, gson.toJson(accepted))
                return accepted
            }
        } catch (error: Exception) { request.cancellation.checkActive() }
        return SubtitleProcessing.rule(words, language, threshold)
    }
}
