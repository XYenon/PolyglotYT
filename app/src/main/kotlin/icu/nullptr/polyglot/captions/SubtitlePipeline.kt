package icu.nullptr.polyglot.captions

import icu.nullptr.polyglot.module
import icu.nullptr.polyglot.translate.TranslationManager
import icu.nullptr.polyglot.translate.VideoContext
import icu.nullptr.polyglot.translate.providers.OpenAICompatibleTranslator
import java.util.concurrent.atomic.AtomicLong

/** Track-scoped subtitle processing and lazy AI scheduling, independent of native view classes. */
class SubtitlePipeline(private val onChanged: () -> Unit) {
    private val version = AtomicLong()
    private val lock = Any()
    private var raw = emptyList<TimedTextEvent>()
    private var prepared = PreparedSubtitles(emptyList(), emptyList())
    private var language = "auto"
    @Volatile private var timeline = emptyList<SubtitleCue>()
    private var chunks = emptyList<List<SubtitleWord>>()
    private var done = HashSet<Int>()
    private val aiReadyUntil = HashMap<Double, Double>()
    private var active = false
    @Volatile private var summaryPending = false
    private val scheduler = SubtitleScheduler()
    fun cues(): List<SubtitleCue> = timeline
    fun current(timeMs: Double = scheduler.position): SubtitleCue? = scheduler.cueAt(timeline, timeMs)
    fun reset() {
        synchronized(lock) {
            version.incrementAndGet(); raw = emptyList(); prepared = PreparedSubtitles(emptyList(), emptyList())
            timeline = emptyList(); chunks = emptyList(); done.clear(); aiReadyUntil.clear(); active = false; summaryPending = false
            scheduler.reset()
        }
    }
    fun reprocess() {
        val source = synchronized(lock) { raw }
        if (source.isNotEmpty()) load(source, language, force = true)
    }
    fun load(events: List<TimedTextEvent>, sourceLanguage: String, force: Boolean = false): List<SubtitleCue> {
        val currentVersion: Long
        val transcript: String
        val summaryRequest: icu.nullptr.polyglot.translate.TranslationRequest?
        synchronized(lock) {
            if (!force && raw == events && language == sourceLanguage) return timeline
            currentVersion = version.incrementAndGet()
            raw = events; language = sourceLanguage
            prepared = SubtitleProcessing.prepare(events, sourceLanguage)
            chunks = if (module.config.aiSegmentation) SubtitleProcessing.chunks(prepared.words, module.config.chunkLength) else emptyList()
            timeline = if (chunks.isNotEmpty()) chunks.flatMap { SubtitleProcessing.rule(it, language, module.config.longSentenceThreshold.coerceAtLeast(1)) }
                else SubtitleProcessing.segment(prepared, language, module.config.segmentationMode, module.config.longSentenceThreshold.coerceAtLeast(1))
            done = HashSet(); aiReadyUntil.clear(); active = false
            val position = scheduler.position
            scheduler.reset()
            if (position.isFinite()) scheduler.shouldPrefetch(position, 0, force = true)
            summaryPending = module.config.enabled && module.config.autoTranslate && !sameLanguage() && module.config.aiSummary && prepared.words.joinToString(" ") { it.text }.length >= 200
            transcript = prepared.words.joinToString(" ") { it.text }.take(8000)
            summaryRequest = if (summaryPending) TranslationManager.request(emptyList(), language) else null
        }
        onChanged()
        if (summaryRequest != null) {
            val request = summaryRequest
            TranslationManager.specialAsync(request, onFinished = {
                if (version.get() == currentVersion) {
                    summaryPending = false
                    scheduleAi(first = true); onChanged()
                }
            }) {
                val key = "video-summary:" + TranslationManager.signature(request) + ":" + transcript
                val summary = TranslationManager.cached(key) ?: TranslationManager.withRetry(request.cancellation) {
                    OpenAICompatibleTranslator.summarize(request, transcript)
                }.also { TranslationManager.cache(key, it) }
                synchronized(lock) {
                    if (version.get() == currentVersion) TranslationManager.updateDocument(request.document.copy(summary = summary))
                }
            }
        } else scheduleAi(first = true)
        return timeline
    }
    private fun sameLanguage(): Boolean {
        fun normalized(value: String) = value.lowercase().replace("zh-cn", "zh-hans").replace("zh-tw", "zh-hant")
        val from = normalized(language); val to = normalized(module.config.targetLanguage)
        return if (module.config.translateVariants) from == to else from.substringBefore('-') == to.substringBefore('-')
    }
    fun playback(timeMs: Double, force: Boolean = false): List<String> {
        val due = scheduler.shouldPrefetch(timeMs, module.config.translationThrottleSeconds, force)
        if (!module.config.enabled || !module.config.autoTranslate || summaryPending || sameLanguage() || !due) return emptyList()
        scheduleAi()
        val available = synchronized(lock) {
            if (chunks.isEmpty()) timeline else timeline.filter { cue -> aiReadyUntil.any { (start, end) -> cue.start >= start && cue.end <= end } }
        }
        return scheduler.upcoming(available, timeMs, module.config.preTranslateSeconds)
    }
    fun updateDocument(document: VideoContext) {
        TranslationManager.updateDocument(document)
        reprocess()
    }
    private fun scheduleAi(first: Boolean = false) {
        if (!module.config.enabled || !module.config.autoTranslate || !module.config.aiSegmentation || summaryPending || sameLanguage()) return
        val currentVersion: Long
        val index: Int
        val words: List<SubtitleWord>
        val sourceLanguage: String
        synchronized(lock) {
            if (active || chunks.isEmpty()) return
            currentVersion = version.get()
            sourceLanguage = language
            val now = scheduler.position.takeIf { it.isFinite() } ?: chunks.first().first().start
            val end = now + module.config.preTranslateSeconds.coerceAtLeast(0) * 1000
            index = if (first && 0 !in done) 0 else chunks.indices.firstOrNull { it !in done && chunks[it].last().end >= now && chunks[it].first().start <= end } ?: return
            words = chunks[index]
            active = true
        }
        val request = TranslationManager.request(emptyList(), sourceLanguage)
        val draft = module.config.forceSubtitleRetranslate && module.config.provider != "openai"
        val threshold = module.config.longSentenceThreshold.coerceAtLeast(1)
        TranslationManager.specialAsync(request, onFinished = {
            synchronized(lock) {
                if (version.get() == currentVersion) {
                    if (!request.cancellation.cancelled) done += index
                    active = false
                }
            }
            if (version.get() == currentVersion && !request.cancellation.cancelled) scheduleAi()
        }) {
            val result = AiSubtitleProcessor.process(request, words, sourceLanguage, draft, threshold) { streamed ->
                replace(currentVersion, words.first().start, streamed.last().end, streamed)
            }
            replace(currentVersion, words.first().start, words.last().end, result)
        }
    }
    private fun replace(expectedVersion: Long, start: Double, end: Double, cues: List<SubtitleCue>) {
        synchronized(lock) {
            if (version.get() != expectedVersion) return
            val chunk = chunks.firstOrNull { it.first().start == start }
            val chunkEnd = chunk?.last()?.end ?: end
            val tail = chunk?.filter { it.start >= end }.orEmpty()
            val fallback = if (tail.isEmpty()) emptyList() else SubtitleProcessing.rule(tail, language, module.config.longSentenceThreshold.coerceAtLeast(1))
            timeline = (timeline.filter { it.end <= start || it.start >= chunkEnd } + cues + fallback).sortedBy { it.start }
            aiReadyUntil[start] = maxOf(aiReadyUntil[start] ?: start, end)
        }
        onChanged()
    }
}
