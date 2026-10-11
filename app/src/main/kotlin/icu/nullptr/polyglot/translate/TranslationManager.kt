package icu.nullptr.polyglot.translate

import icu.nullptr.polyglot.captions.CaptionCue
import icu.nullptr.polyglot.module
import icu.nullptr.polyglot.translate.providers.MicrosoftTranslator
import icu.nullptr.polyglot.translate.providers.OpenAICompatibleTranslator
import icu.nullptr.polyglot.translate.providers.YouTubeCommentTranslator
import icu.nullptr.polyglot.util.logW
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

object TranslationManager {
    const val TAG = "TranslationManager"
    const val MIN_TIMEOUT_MS = 5_000
    const val BASE_RETRY_DELAY_MS = 750L

    private val inFlight = ConcurrentHashMap.newKeySet<RequestKey>()
    private val executor = Executors.newFixedThreadPool(4, ThreadFactory)
    private val settingsRevision = AtomicInteger(0)

    fun onSettingsChanged() {
        settingsRevision.incrementAndGet()
    }

    fun translateAsync(
        text: String,
        context: String,
        sourceLanguage: String,
        onTranslated: (original: String, translated: String) -> Unit,
    ): Boolean =
        translateBatchAsync(
            texts = listOf(text),
            context = context,
            sourceLanguage = sourceLanguage,
            onTranslated = onTranslated,
        ) > 0

    fun translateBatchAsync(
        texts: Iterable<String>,
        context: String,
        sourceLanguage: String,
        onTranslated: (original: String, translated: String) -> Unit,
    ): Int {
        val settings = requestSettings(sourceLanguage)
        val pending = texts
            .map { text -> CaptionCue.normalize(text) }
            .filter { text -> text.isNotEmpty() }
            .distinct()
            .mapNotNull { text ->
                val key = requestKey(text, settings.identity)
                if (inFlight.add(key)) text to key else null
            }
        if (pending.isEmpty()) return 0
        val originals = pending.map { (text, _) -> text }
        val requestKeys = pending.map { (_, key) -> key }

        return try {
            executor.execute {
                try {
                    val translations = translateWithRetry(originals, context, settings)
                    for (index in originals.indices) {
                        if (settings.identity != requestIdentity(sourceLanguage)) break

                        val translated = translations[index]
                        if (translated.isNotBlank()) {
                            onTranslated(originals[index], translated)
                        }
                    }
                } catch (e: Throwable) {
                    logW(
                        TAG,
                        "Caption translation failed, count=${originals.size}, " +
                            "totalLength=${originals.sumOf { it.length }}",
                        e,
                    )
                } finally {
                    requestKeys.forEach { key -> inFlight.remove(key) }
                }
            }
            originals.size
        } catch (_: RejectedExecutionException) {
            requestKeys.forEach { key -> inFlight.remove(key) }
            0
        }
    }

    private fun translateWithRetry(
        texts: List<String>,
        context: String,
        settings: RequestSettings,
    ): List<String> {
        var attempt = 0
        var lastError: Throwable? = null

        while (attempt <= settings.maxRetries) {
            runCatching {
                return translateOnce(
                    texts = texts,
                    context = context,
                    identity = settings.identity,
                    timeoutMs = settings.timeoutMs,
                )
            }.onFailure { error ->
                lastError = error
                if (attempt < settings.maxRetries) {
                    Thread.sleep(retryDelayMs(attempt))
                }
            }
            attempt++
        }

        throw lastError ?: IllegalStateException("Translation cancelled")
    }

    fun translateForConnectivityTest(
        text: String,
        context: String,
        sourceLanguage: String,
        timeoutMs: Int,
    ): String {
        val identity = requestIdentity(sourceLanguage)
        return translateOnce(
            texts = listOf(CaptionCue.normalize(text)),
            context = context,
            identity = identity,
            timeoutMs = timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS),
        ).singleOrNull().orEmpty()
    }

    private fun translateOnce(
        texts: List<String>,
        context: String,
        identity: RequestIdentity,
        timeoutMs: Int,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()

        val request = TranslationRequest(
            texts = texts,
            sourceLanguage = identity.sourceLanguage,
            targetLanguage = identity.targetLanguage,
            context = context,
            timeoutMs = timeoutMs,
            providerSettings = identity.providerSettings,
        )
        val translations = translator(identity.provider).translate(request).texts
        check(translations.size == texts.size) {
            "Translator returned ${translations.size} results for ${texts.size} texts"
        }
        return translations
    }

    private fun translator(provider: String): Translator =
        when (provider.lowercase(Locale.ROOT)) {
            "openai", "openai-compatible", "custom" -> OpenAICompatibleTranslator
            "microsoft" -> MicrosoftTranslator
            else -> YouTubeCommentTranslator
        }

    private fun retryDelayMs(attempt: Int): Long =
        BASE_RETRY_DELAY_MS * (attempt + 1)

    private fun requestSettings(sourceLanguage: String): RequestSettings =
        RequestSettings(
            identity = requestIdentity(sourceLanguage),
            timeoutMs = module.config.requestTimeoutMs.coerceAtLeast(MIN_TIMEOUT_MS),
            maxRetries = module.config.maxRetries.coerceAtLeast(0),
        )

    private fun requestIdentity(sourceLanguage: String): RequestIdentity =
        RequestIdentity(
            provider = module.config.provider,
            sourceLanguage = normalizedSourceLanguage(sourceLanguage),
            targetLanguage = module.config.targetLanguage,
            settingsRevision = settingsRevision.get(),
            providerSettings = TranslationProviderSettings(
                microsoftEndpoint = module.config.microsoftEndpoint,
                microsoftApiKey = module.config.microsoftApiKey,
                microsoftRegion = module.config.microsoftRegion,
                openAiEndpoint = module.config.openAiEndpoint,
                openAiApiKey = module.config.openAiApiKey,
                openAiModel = module.config.openAiModel,
                openAiSystemPrompt = module.config.openAiSystemPrompt,
                openAiUserPrompt = module.config.openAiUserPrompt,
            ),
        )

    private fun requestKey(text: String, identity: RequestIdentity): RequestKey =
        RequestKey(text, identity)

    private fun normalizedSourceLanguage(language: String): String =
        language.trim().ifEmpty { "auto" }

    private data class RequestIdentity(
        val provider: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val settingsRevision: Int,
        val providerSettings: TranslationProviderSettings,
    )

    private data class RequestKey(
        val text: String,
        val identity: RequestIdentity,
    )

    private data class RequestSettings(
        val identity: RequestIdentity,
        val timeoutMs: Int,
        val maxRetries: Int,
    )

    private object ThreadFactory : java.util.concurrent.ThreadFactory {
        private val counter = AtomicInteger(0)

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "PolyglotYT-Translator-${counter.incrementAndGet()}").apply {
                isDaemon = true
            }
    }
}
