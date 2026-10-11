package icu.nullptr.polyglot.youtube.settings

import android.text.InputType
import icu.nullptr.polyglot.R
import icu.nullptr.polyglot.module
import icu.nullptr.polyglot.settings.SettingsOption
import icu.nullptr.polyglot.settings.SettingsOptions
import icu.nullptr.polyglot.translate.KissPrompts
import icu.nullptr.polyglot.youtube.CaptionHook
import kotlin.reflect.KMutableProperty0

internal object SubtitleSettings {
    private fun text(id: Int) = module.res.getString(id)
    private fun changed() = CaptionHook.onTranslationSettingsChanged()
    private fun toggle(key: String, title: Int, property: KMutableProperty0<Boolean>, visible: () -> Boolean = { true }) =
        SwitchSettingsNode("$ENTRY_KEY.$key", text(title), SettingsIcon.Subtitle, visible, { property.get() },
            { SettingsOptions.enabledSummary(property.get()) }, { property.set(it); changed() })
    private fun number(key: String, title: Int, property: KMutableProperty0<Int>, minimum: Int = 0, maximum: Int = 1_000_000, scale: Int = 1) =
        TextSettingsNode("$ENTRY_KEY.$key", text(title), SettingsIcon.Subtitle, inputType = InputType.TYPE_CLASS_NUMBER,
            value = { (property.get() / scale).toString() }, summary = { (property.get() / scale).toString() }, onSubmitted = { input ->
                input.toIntOrNull()?.takeIf { it in minimum..maximum }?.let { property.set(it * scale); changed() }
            })
    private fun editable(key: String, title: Int, property: KMutableProperty0<String>, visible: () -> Boolean = { true }) =
        TextSettingsNode("$ENTRY_KEY.$key", text(title), SettingsIcon.Endpoint, visible,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, { property.get() },
            { SettingsOptions.textOrNotSet(property.get()).take(120) }, { property.set(it); changed() })
    private fun selection(key: String, title: Int, property: KMutableProperty0<String>, options: List<SettingsOption>, onSelected: ((String) -> Unit)? = null) =
        SelectionSettingsNode("$ENTRY_KEY.$key", text(title), SettingsIcon.Subtitle, options = options,
            selectedValue = { property.get() }, selectedLabel = { options.firstOrNull { it.value == property.get() }?.label ?: property.get() },
            onSelected = { value -> onSelected?.invoke(value); property.set(value); changed() })
    private fun option(value: String, title: Int) = SettingsOption(value, text(title))

    fun screens(): List<SettingsNode> {
        val config = module.config
        return listOf(
            SettingsScreenNode("$ENTRY_KEY.processing", text(R.string.processing_settings), SettingsIcon.Subtitle, children = listOf(
                toggle("auto_translate", R.string.auto_translate, config::autoTranslate),
                selection("segmentation", R.string.segmentation_mode, config::segmentationMode, listOf(option("rule", R.string.segmentation_rule), option("statistical", R.string.segmentation_statistical))),
                toggle("ai_segmentation", R.string.ai_segmentation, config::aiSegmentation),
                toggle("force_retranslate", R.string.force_retranslate, config::forceSubtitleRetranslate, { config.aiSegmentation }),
                number("chunk_length", R.string.chunk_length, config::chunkLength, 1),
                number("long_threshold", R.string.long_threshold, config::longSentenceThreshold, 1),
                number("pre_translate", R.string.pre_translate, config::preTranslateSeconds, 0, 86400),
                number("translation_throttle", R.string.translation_throttle, config::translationThrottleSeconds, 0, 86400),
                toggle("ai_summary", R.string.ai_summary, config::aiSummary),
                toggle("translate_variants", R.string.translate_variants, config::translateVariants),
            )),
            SettingsScreenNode("$ENTRY_KEY.requests", text(R.string.translation_settings), SettingsIcon.Service, children = listOf(
                number("timeout", R.string.request_timeout, config::requestTimeoutMs, 5, 3600, 1000),
                number("retries", R.string.max_retries, config::maxRetries, 0, 20),
                number("fetch_limit", R.string.fetch_limit, config::fetchLimit, 1, 64),
                number("fetch_interval", R.string.fetch_interval, config::fetchIntervalMs),
                toggle("use_batch", R.string.use_batch, config::useBatch),
                number("batch_interval", R.string.batch_interval, config::batchIntervalMs),
                number("batch_size", R.string.batch_size, config::batchSize, 1),
                number("batch_length", R.string.batch_length, config::batchLength, 1),
                number("batch_concurrency", R.string.batch_concurrency, config::batchConcurrency, 1, 64),
                toggle("use_stream", R.string.use_stream, config::useStream),
                selection("stream_render", R.string.stream_render, config::streamRenderMode, listOf(option("disabled", R.string.stream_disabled), option("realtime", R.string.stream_realtime), option("segment", R.string.stream_segment))),
                toggle("use_context", R.string.use_context, config::useContext),
                number("context_size", R.string.context_size, config::contextSize, 0, 1000),
            )),
            SettingsScreenNode("$ENTRY_KEY.prompts", text(R.string.prompt_settings), SettingsIcon.Endpoint, children = listOf(
                selection("single_prompt_mode", R.string.single_prompt_mode, config::singlePromptMode, listOf(option("concise", R.string.single_concise), option("legacy", R.string.single_legacy), option("custom", R.string.custom_prompt)), { value ->
                    val saved = config.singleSystemPrompt to config.singleUserPrompt
                    val presets = listOf(KissPrompts.SINGLE_CONCISE_SYSTEM to KissPrompts.SINGLE_CONCISE_USER, KissPrompts.SINGLE_LEGACY_SYSTEM to KissPrompts.SINGLE_LEGACY_USER)
                    if (value == "custom" && config.singlePromptMode != "custom" && saved in presets) {
                        if (config.singlePromptMode == "legacy") {
                            config.singleSystemPrompt = KissPrompts.SINGLE_LEGACY_SYSTEM; config.singleUserPrompt = KissPrompts.SINGLE_LEGACY_USER
                        } else {
                            config.singleSystemPrompt = KissPrompts.SINGLE_CONCISE_SYSTEM; config.singleUserPrompt = KissPrompts.SINGLE_CONCISE_USER
                        }
                    }
                }),
                editable("single_system_prompt", R.string.single_system_prompt, config::singleSystemPrompt, { config.singlePromptMode == "custom" }),
                editable("single_user_prompt", R.string.single_user_prompt, config::singleUserPrompt, { config.singlePromptMode == "custom" }),
                selection("batch_prompt_mode", R.string.batch_prompt_mode, config::batchPromptMode, listOf(SettingsOption("json", "JSON"), SettingsOption("xml", "XML"), SettingsOption("line", "LINE"), option("custom-json", R.string.custom_json), option("custom-xml", R.string.custom_xml), option("custom-line", R.string.custom_line)), { value ->
                    val saved = config.batchSystemPrompt to config.batchUserPrompt
                    val presets = listOf(KissPrompts.BATCH_JSON_SYSTEM to KissPrompts.BATCH_JSON_USER, KissPrompts.BATCH_XML_SYSTEM to KissPrompts.BATCH_XML_USER, KissPrompts.BATCH_LINE_SYSTEM to KissPrompts.BATCH_LINE_USER)
                    if (value.startsWith("custom-") && !config.batchPromptMode.startsWith("custom-") && saved in presets) {
                        val prompts = when (value) {
                            "custom-xml" -> KissPrompts.BATCH_XML_SYSTEM to KissPrompts.BATCH_XML_USER
                            "custom-line" -> KissPrompts.BATCH_LINE_SYSTEM to KissPrompts.BATCH_LINE_USER
                            else -> KissPrompts.BATCH_JSON_SYSTEM to KissPrompts.BATCH_JSON_USER
                        }
                        config.batchSystemPrompt = prompts.first; config.batchUserPrompt = prompts.second
                    }
                }),
                editable("batch_system_prompt", R.string.batch_system_prompt, config::batchSystemPrompt, { config.batchPromptMode.startsWith("custom-") }),
                editable("batch_user_prompt", R.string.batch_user_prompt, config::batchUserPrompt, { config.batchPromptMode.startsWith("custom-") }),
                editable("subtitle_system_prompt", R.string.subtitle_system_prompt, config::subtitleSystemPrompt),
                editable("summary_system_prompt", R.string.summary_system_prompt, config::summarySystemPrompt),
                editable("glossary", R.string.glossary, config::glossary),
                selection("tone", R.string.tone, config::tone, listOf("formal", "casual", "neutral", "technical", "marketing", "Literary", "academic", "legal", "literal", "idiomatic", "transcreation", "machine-like", "concise").map { SettingsOption(it, it) }),
            )),
        )
    }
}
