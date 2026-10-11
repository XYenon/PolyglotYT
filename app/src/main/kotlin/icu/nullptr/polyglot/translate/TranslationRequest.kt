package icu.nullptr.polyglot.translate

data class TranslationRequest(
    val texts: List<String>,
    val sourceLanguage: String,
    val targetLanguage: String,
    val context: String,
    val timeoutMs: Int,
    val providerSettings: TranslationProviderSettings,
    val document: VideoContext = VideoContext(),
    val onPartial: ((Int, String) -> Unit)? = null,
    val cancellation: TranslationCancellation = TranslationCancellation(),
    val onCompleted: ((Int, String) -> Unit)? = null,
)

data class TranslationProviderSettings(
    val microsoftEndpoint: String,
    val microsoftApiKey: String,
    val microsoftRegion: String,
    val openAiEndpoint: String,
    val openAiApiKey: String,
    val openAiModel: String,
    val useBatch: Boolean = true,
    val useStream: Boolean = true,
    val streamRenderMode: String = "realtime",
    val useContext: Boolean = false,
    val contextSize: Int = 3,
    val singlePromptMode: String = "concise",
    val batchPromptMode: String = "json",
    val singleSystemPrompt: String = KissPrompts.SINGLE_CONCISE_SYSTEM,
    val singleUserPrompt: String = KissPrompts.SINGLE_CONCISE_USER,
    val batchSystemPrompt: String = KissPrompts.BATCH_JSON_SYSTEM,
    val batchUserPrompt: String = KissPrompts.BATCH_JSON_USER,
    val subtitleSystemPrompt: String = KissPrompts.SUBTITLE_SYSTEM,
    val summarySystemPrompt: String = KissPrompts.SUMMARY_SYSTEM,
    val tone: String = "formal",
    val glossary: String = "",
)

data class VideoContext(
    val videoId: String = "",
    val title: String = "",
    val description: String = "",
    val summary: String = "",
    val context: String = "",
)

class TranslationCancellation {
    @Volatile var cancelled = false
        private set
    private val connections = java.util.concurrent.CopyOnWriteArraySet<java.net.HttpURLConnection>()
    fun cancel() {
        cancelled = true
        connections.forEach { it.disconnect() }
        connections.clear()
    }
    fun register(connection: java.net.HttpURLConnection) {
        connections.add(connection)
        if (cancelled) {
            connections.remove(connection)
            connection.disconnect()
            checkActive()
        }
    }
    fun unregister(connection: java.net.HttpURLConnection) { connections.remove(connection) }
    fun checkActive() {
        if (cancelled) throw java.util.concurrent.CancellationException("Translation session cancelled")
    }
}
