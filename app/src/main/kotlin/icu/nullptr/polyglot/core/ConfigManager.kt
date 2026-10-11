package icu.nullptr.polyglot.core

import android.content.Context
import com.tencent.mmkv.MMKV
import icu.nullptr.polyglot.translate.KissPrompts
import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class ConfigManager(context: Context, directory: File) {

    var enabled: Boolean by Pref("enabled", true)

    var provider: String by Pref("translator_provider", PROVIDER_MICROSOFT)

    var targetLanguage: String by Pref("target_language", "zh-Hans")

    var subtitleMode: String by Pref("bilingual_order", SUBTITLE_ORIGINAL_FIRST)

    var requestTimeoutMs: Int by Pref("request_timeout_ms", 30000)

    var maxRetries: Int by Pref("max_retries", 2)

    var microsoftEndpoint: String by Pref(
        "microsoft_endpoint",
        "https://api.cognitive.microsofttranslator.com/translate",
    )

    var microsoftApiKey: String by Pref("microsoft_api_key", "")

    var microsoftRegion: String by Pref("microsoft_region", "")

    var openAiEndpoint: String by Pref(
        "openai_endpoint",
        "https://api.openai.com/v1/chat/completions",
    )

    var openAiApiKey: String by Pref("openai_api_key", "")

    var openAiModel: String by Pref("openai_model", "gpt-4o-mini")

    var autoTranslate: Boolean by Pref("auto_translate", true)
    var segmentationMode: String by Pref("segmentation_mode", "rule")
    var aiSegmentation: Boolean by Pref("ai_segmentation", false)
    var forceSubtitleRetranslate: Boolean by Pref("force_subtitle_retranslate", false)
    var chunkLength: Int by Pref("chunk_length", 1000)
    var longSentenceThreshold: Int by Pref("long_sentence_threshold", 60)
    var preTranslateSeconds: Int by Pref("pre_translate_seconds", 90)
    var translationThrottleSeconds: Int by Pref("translation_throttle_seconds", 30)
    var fetchLimit: Int by Pref("fetch_limit", 10)
    var fetchIntervalMs: Int by Pref("fetch_interval_ms", 100)
    var batchIntervalMs: Int by Pref("batch_interval_ms", 400)
    var batchSize: Int by Pref("batch_size", 20)
    var batchLength: Int by Pref("batch_length", 10000)
    var batchConcurrency: Int by Pref("batch_concurrency", 10)
    var useBatch: Boolean by Pref("use_batch", true)
    var useStream: Boolean by Pref("use_stream", true)
    var streamRenderMode: String by Pref("stream_render_mode", "realtime")
    var useContext: Boolean by Pref("use_context", false)
    var contextSize: Int by Pref("context_size", 3)
    var aiSummary: Boolean by Pref("ai_summary", false)
    var translateVariants: Boolean by Pref("translate_variants", true)
    var tone: String by Pref("translation_tone", "formal")
    var glossary: String by Pref("translation_glossary", "")
    var singlePromptMode: String by Pref("single_prompt_mode", "concise")
    var batchPromptMode: String by Pref("batch_prompt_mode", "json")
    var singleSystemPrompt: String by Pref("single_system_prompt", KissPrompts.SINGLE_CONCISE_SYSTEM)
    var singleUserPrompt: String by Pref("single_user_prompt", KissPrompts.SINGLE_CONCISE_USER)
    var batchSystemPrompt: String by Pref("batch_system_prompt", KissPrompts.BATCH_JSON_SYSTEM)
    var batchUserPrompt: String by Pref("batch_user_prompt", KissPrompts.BATCH_JSON_USER)
    var subtitleSystemPrompt: String by Pref("subtitle_system_prompt", KissPrompts.SUBTITLE_SYSTEM)
    var summarySystemPrompt: String by Pref("summary_system_prompt", KissPrompts.SUMMARY_SYSTEM)

    private inner class Pref<T>(
        private val key: String,
        private val defaultValue: T,
    ) : ReadWriteProperty<ConfigManager, T> {
        @Suppress("UNCHECKED_CAST")
        override fun getValue(thisRef: ConfigManager, property: KProperty<*>): T {
            return when (defaultValue) {
                is String -> kv.getString(key, defaultValue) as T
                is Int -> kv.getInt(key, defaultValue) as T
                is Long -> kv.getLong(key, defaultValue) as T
                is Float -> kv.getFloat(key, defaultValue) as T
                is Boolean -> kv.getBoolean(key, defaultValue) as T
                else -> throw IllegalArgumentException("Unsupported type")
            }
        }

        override fun setValue(thisRef: ConfigManager, property: KProperty<*>, value: T) {
            when (value) {
                is String -> kv.putString(key, value)
                is Int -> kv.putInt(key, value)
                is Long -> kv.putLong(key, value)
                is Float -> kv.putFloat(key, value)
                is Boolean -> kv.putBoolean(key, value)
                else -> throw IllegalArgumentException("Unsupported type")
            }
        }
    }

    private val kv: MMKV

    init {
        MMKV.initialize(context, directory.absolutePath)
        kv = MMKV.defaultMMKV()
        val previousSystem = kv.getString("openai_system_prompt", null)
        val previousUser = kv.getString("openai_user_prompt", null)
        val oldUserDefault = "Translate the following subtitle text into {{to}}. " +
            "If translation is unnecessary, return the original text. NO explanations. NO notes:\n\n{{origin}}"
        if (kv.getString("single_prompt_mode", null) == null &&
            ((previousSystem != null && previousSystem != KissPrompts.SINGLE_LEGACY_SYSTEM) ||
                (previousUser != null && previousUser != oldUserDefault))) {
            singlePromptMode = "custom"
            singleSystemPrompt = previousSystem ?: KissPrompts.SINGLE_CONCISE_SYSTEM
            singleUserPrompt = previousUser?.replace("{{origin}}", "{{text}}") ?: KissPrompts.SINGLE_CONCISE_USER
        }
    }

    companion object {
        const val PROVIDER_GOOGLE = "google"
        const val PROVIDER_MICROSOFT = "microsoft"
        const val PROVIDER_OPENAI = "openai"

        const val SUBTITLE_ORIGINAL_FIRST = "original_first"
        const val SUBTITLE_TRANSLATION_FIRST = "translation_first"
        const val SUBTITLE_TRANSLATION_ONLY = "translation_only"
    }
}
