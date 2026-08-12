package icu.nullptr.polyglot.translate

data class TranslationRequest(
    val texts: List<String>,
    val sourceLanguage: String,
    val targetLanguage: String,
    val context: String,
    val timeoutMs: Int,
    val providerSettings: TranslationProviderSettings,
)

data class TranslationProviderSettings(
    val microsoftEndpoint: String,
    val microsoftApiKey: String,
    val microsoftRegion: String,
    val openAiEndpoint: String,
    val openAiApiKey: String,
    val openAiModel: String,
    val openAiSystemPrompt: String,
    val openAiUserPrompt: String,
)
