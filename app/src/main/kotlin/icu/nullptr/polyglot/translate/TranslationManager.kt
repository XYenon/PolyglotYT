package icu.nullptr.polyglot.translate

import com.google.gson.Gson
import icu.nullptr.polyglot.captions.CaptionCue
import icu.nullptr.polyglot.module
import icu.nullptr.polyglot.translate.providers.MicrosoftTranslator
import icu.nullptr.polyglot.translate.providers.OpenAICompatibleTranslator
import icu.nullptr.polyglot.translate.providers.YouTubeCommentTranslator
import icu.nullptr.polyglot.util.logW
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object TranslationManager {
    const val TAG = "TranslationManager"
    const val MIN_TIMEOUT_MS = 5_000
    private val sequence = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(64) { runnable -> Thread(runnable, "PolyglotYT-Translator-${sequence.incrementAndGet()}").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "PolyglotYT-TranslationQueue").apply { isDaemon = true } }
    private val contextExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "PolyglotYT-ContextTranslations").apply { isDaemon = true } }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val queue = ArrayList<Task>()
    private val lock = Any()
    private var scheduledFlush: ScheduledFuture<*>? = null
    private var lastRequestStart = 0L
    private var cancellation = TranslationCancellation()
    private var slots = Semaphore(10)
    private var batchSlots = Semaphore(10)
    private val contextSlot = Semaphore(1)
    private val revision = AtomicInteger()
    private val cache by lazy { TranslationCache(module.translationCacheDirectory) }
    private val gson = Gson()
    @Volatile var document = VideoContext()
        private set
    private data class Task(val text: String, val key: String, val provider: String, val request: TranslationRequest,
        val callback: (String, String) -> Unit, val partial: ((String, String) -> Unit)?)

    fun onSettingsChanged() = cancelPending()
    fun onTrackChanged() {
        cancelPending()
        document = VideoContext()
    }
    fun updateDocument(value: VideoContext) {
        if (value == document) return
        cancelPending()
        document = value
    }
    fun cancelPending() {
        synchronized(lock) {
            revision.incrementAndGet()
            cancellation.cancel(); cancellation = TranslationCancellation()
            queue.clear(); scheduledFlush?.cancel(false); scheduledFlush = null
            inFlight.clear()
            slots = Semaphore(module.config.fetchLimit.coerceIn(1, 64))
            batchSlots = Semaphore(module.config.batchConcurrency.coerceIn(1, 64))
            lastRequestStart = 0
        }
        OpenAICompatibleTranslator.clearHistory()
    }

    fun providerSettings(): TranslationProviderSettings = module.config.let { config ->
        TranslationProviderSettings(config.microsoftEndpoint, config.microsoftApiKey, config.microsoftRegion,
            config.openAiEndpoint, config.openAiApiKey, config.openAiModel,
            config.useBatch, config.useStream, config.streamRenderMode, config.useContext, config.contextSize,
            config.singlePromptMode, config.batchPromptMode, config.singleSystemPrompt, config.singleUserPrompt,
            config.batchSystemPrompt, config.batchUserPrompt, config.subtitleSystemPrompt, config.summarySystemPrompt,
            config.tone, config.glossary)
    }
    fun request(texts: List<String>, sourceLanguage: String, context: String = "", partial: ((Int, String) -> Unit)? = null): TranslationRequest =
        TranslationRequest(texts, sourceLanguage.trim().ifBlank { "auto" }, module.config.targetLanguage, context,
            module.config.requestTimeoutMs.coerceAtLeast(MIN_TIMEOUT_MS), providerSettings(), document, partial, cancellation)

    fun translateAsync(text: String, context: String, sourceLanguage: String, onTranslated: (String, String) -> Unit): Boolean =
        translateBatchAsync(listOf(text), context, sourceLanguage, onTranslated = onTranslated) > 0

    fun translateBatchAsync(texts: Iterable<String>, context: String, sourceLanguage: String,
        onPartial: ((String, String) -> Unit)? = null, onTranslated: (String, String) -> Unit): Int {
        val prototype = request(emptyList(), sourceLanguage, context)
        val provider = module.config.provider
        val signature = gson.toJson(listOf("kiss-df3e1e3-v1", provider, prototype.sourceLanguage, prototype.targetLanguage,
            prototype.providerSettings, prototype.document))
        var count = 0
        synchronized(lock) {
            for (text in texts.map { CaptionCue.normalize(it) }.filter { it.isNotBlank() }.distinct()) {
                val key = cache.digest(signature + "\u0000" + text) + ":" + revision.get()
                if (!inFlight.add(key)) continue
                queue += Task(text, key, provider, prototype, onTranslated, onPartial); count++
            }
            if (queue.isNotEmpty() && scheduledFlush == null) scheduledFlush = scheduler.schedule(::flush,
                module.config.batchIntervalMs.coerceAtLeast(0).toLong(), TimeUnit.MILLISECONDS)
        }
        return count
    }
    private fun cacheKey(task: Task) = task.key.substringBefore(':')
    private fun flush() {
        val tasks = synchronized(lock) { queue.toList().also { queue.clear(); scheduledFlush = null } }
        val active = ArrayList<Task>()
        for (task in tasks) {
            if (task.request.cancellation.cancelled) { inFlight.remove(task.key); continue }
            val stored = cache.read(cacheKey(task))
            if (stored != null) {
                task.callback(task.text, stored); inFlight.remove(task.key)
            } else active += task
        }
        val size = module.config.batchSize.coerceAtLeast(1)
        val length = module.config.batchLength.coerceAtLeast(1)
        val batch = ArrayList<Task>()
        var characters = 0
        fun submit() {
            if (batch.isNotEmpty()) {
                val copy = batch.toList()
                val worker = if (copy.first().request.providerSettings.useContext) contextExecutor else executor
                worker.execute { translate(copy) }
                batch.clear(); characters = 0
            }
        }
        for (task in active) {
            val previous = batch.firstOrNull()
            if (previous != null && (task.provider != previous.provider || task.request != previous.request || batch.size >= size || characters + task.text.length > length || !task.request.providerSettings.useBatch)) submit()
            batch += task; characters += task.text.length
        }
        submit()
    }
    private fun translate(tasks: List<Task>) {
        val first = tasks.first()
        val token = first.request.cancellation
        val global = slots; val batches = batchSlots
        var globalAcquired = false; var batchAcquired = false; var contextAcquired = false
        try {
            global.acquire(); globalAcquired = true
            batches.acquire(); batchAcquired = true
            if (first.request.providerSettings.useContext) { contextSlot.acquire(); contextAcquired = true }
            token.checkActive()
            pace(token)
            val completed = HashSet<Int>()
            fun publish(index: Int, text: String) {
                token.checkActive()
                if (text.isNotBlank() && completed.add(index)) {
                    cache.write(cacheKey(tasks[index]), text)
                    tasks[index].callback(tasks[index].text, text)
                }
            }
            val request = first.request.copy(texts = tasks.map { it.text }, onPartial = { index, text ->
                if (!token.cancelled && index !in completed) tasks.getOrNull(index)?.let { it.partial?.invoke(it.text, text) }
            }, onCompleted = { index, text -> if (index in tasks.indices) publish(index, text) })
            val result = withRetry(token) { translator(first.provider).translate(request).texts }
            check(result.size == tasks.size) { "Translator returned an unexpected number of results" }
            result.forEachIndexed { index, translated -> publish(index, translated) }
        } catch (_: CancellationException) {
        } catch (error: Exception) {
            logW(TAG, "Caption translation failed, count=${tasks.size}, totalLength=${tasks.sumOf { it.text.length }}", error)
        } finally {
            tasks.forEach { inFlight.remove(it.key) }
            if (contextAcquired) contextSlot.release()
            if (batchAcquired) batches.release()
            if (globalAcquired) global.release()
        }
    }
    private fun pace(token: TranslationCancellation) {
        val wait = synchronized(lock) {
            val now = System.nanoTime() / 1_000_000
            val start = maxOf(now, lastRequestStart + module.config.fetchIntervalMs.coerceAtLeast(0))
            lastRequestStart = start
            start - now
        }
        if (wait > 0) Thread.sleep(wait)
        token.checkActive()
    }
    fun <T> withRetry(token: TranslationCancellation, operation: () -> T): T {
        var last: Exception? = null
        for (attempt in 0..module.config.maxRetries.coerceAtLeast(0)) {
            token.checkActive()
            try { return operation() } catch (error: Exception) {
                token.checkActive(); last = error
                if (attempt < module.config.maxRetries) Thread.sleep(1000)
            }
        }
        throw checkNotNull(last)
    }
    fun specialAsync(request: TranslationRequest, onFinished: () -> Unit = {}, operation: () -> Unit) {
        val global = slots
        executor.execute {
            var acquired = false
            try {
                global.acquire(); acquired = true
                request.cancellation.checkActive(); pace(request.cancellation); operation()
            }
            catch (_: CancellationException) { }
            catch (error: Exception) { logW(TAG, "Subtitle processing request failed", error) }
            finally {
                if (acquired) global.release()
                onFinished()
            }
        }
    }
    fun cached(key: String) = cache.read(key)
    fun cache(key: String, value: String) = cache.write(key, value)
    fun signature(request: TranslationRequest) = cache.digest(gson.toJson(listOf(request.sourceLanguage, request.targetLanguage, request.providerSettings, request.document)))
    fun translateForConnectivityTest(text: String, context: String, sourceLanguage: String, timeoutMs: Int): String {
        val request = request(listOf(CaptionCue.normalize(text)), sourceLanguage, context).copy(timeoutMs = timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS),
            providerSettings = providerSettings().copy(useStream = false, useContext = false), cancellation = TranslationCancellation())
        return translator(module.config.provider).translate(request).texts.singleOrNull().orEmpty()
    }
    private fun translator(provider: String): Translator = when (provider.lowercase(Locale.ROOT)) {
        "openai", "openai-compatible", "custom" -> OpenAICompatibleTranslator
        "microsoft" -> MicrosoftTranslator
        else -> YouTubeCommentTranslator
    }
}
