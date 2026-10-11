package icu.nullptr.polyglot.translate

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object PromptRenderer {
    fun protocol(settings: TranslationProviderSettings) = settings.batchPromptMode.removePrefix("custom-")
    fun single(settings: TranslationProviderSettings): Pair<String, String> = when (settings.singlePromptMode) {
        "legacy" -> KissPrompts.SINGLE_LEGACY_SYSTEM to KissPrompts.SINGLE_LEGACY_USER
        "custom" -> settings.singleSystemPrompt to settings.singleUserPrompt
        else -> KissPrompts.SINGLE_CONCISE_SYSTEM to KissPrompts.SINGLE_CONCISE_USER
    }
    fun batch(settings: TranslationProviderSettings): Pair<String, String> = when (settings.batchPromptMode) {
        "xml" -> KissPrompts.BATCH_XML_SYSTEM to KissPrompts.BATCH_XML_USER
        "line" -> KissPrompts.BATCH_LINE_SYSTEM to KissPrompts.BATCH_LINE_USER
        "custom-json", "custom-xml", "custom-line" -> settings.batchSystemPrompt to settings.batchUserPrompt
        else -> KissPrompts.BATCH_JSON_SYSTEM to KissPrompts.BATCH_JSON_USER
    }
    fun glossary(text: String): Map<String, String> {
        if (text.trim().startsWith('{')) return runCatching {
            JsonParser.parseString(text).asJsonObject.entrySet().associate { it.key to it.value.asString }
        }.getOrDefault(emptyMap())
        return text.split(Regex("\\n|;")).mapNotNull { line ->
            val fields = line.split(',').map { it.trim() }
            val key = fields.firstOrNull().orEmpty()
            if (key.isEmpty()) null else key to fields.getOrNull(1).orEmpty()
        }.toMap()
    }
    fun segments(texts: List<String>, protocol: String): String = when (protocol) {
        "xml" -> "<root>\n" + texts.mapIndexed { index, text -> "  <t id=\"$index\">$text</t>" }.joinToString("\n") + "\n</root>"
        "line" -> texts.mapIndexed { index, text -> "$index | ${text.replace(Regex("\\r?\\n"), "<br>")}" }.joinToString("\n")
        else -> JsonArray().apply { texts.forEachIndexed { index, text -> add(JsonObject().apply { addProperty("id", index); addProperty("text", text) }) } }.toString()
    }
    fun render(template: String, request: TranslationRequest, input: String, system: Boolean = false, batch: Boolean = false, subtitle: Boolean = false): String {
        val settings = request.providerSettings
        val document = request.document
        val terms = glossary(settings.glossary)
        val inlineGlossary = terms.entries.joinToString("\n") { "- ${it.key}: ${if (subtitle) it.value else it.value.ifBlank { it.key }}" }
        val glossaryBlock = if (terms.isEmpty()) "" else "Reference the following translations:\n" + terms.entries.joinToString("\n") { "${it.key} translates to ${it.value.ifBlank { it.key }}" }
        val toneBlock = settings.tone.trim().let { if (it.isEmpty()) "" else "Note that the translation style must strictly conform to [$it]." }
        val fields = mapOf("title" to document.title, "description" to document.description, "summary" to document.summary, "context" to document.context,
            "tone" to settings.tone, "glossary" to inlineGlossary,
            "to" to if (subtitle) PromptLanguages.code(request.targetLanguage) else PromptLanguages.name(request.targetLanguage),
            "from" to if (subtitle) PromptLanguages.code(request.sourceLanguage) else PromptLanguages.name(request.sourceLanguage),
            "toLang" to PromptLanguages.code(request.targetLanguage), "fromLang" to PromptLanguages.code(request.sourceLanguage))
        var result = template
        if (!system) {
            for (field in listOf("title", "description", "summary", "context", "glossary", "tone")) {
                val raw = fields.getValue(field).trim()
                val block = when (field) {
                    "glossary" -> glossaryBlock
                    "tone" -> toneBlock
                    else -> if (raw.isEmpty()) "" else "${field.replaceFirstChar { it.uppercase() }}: $raw"
                }
                result = Regex("(?m)^[ \\t]*" + Regex.escape("{{$field}}") + "[ \\t]*(?:\\r?\\n|$)").replace(result) { if (block.isEmpty()) "" else "$block\n\n" }
            }
            val constraints = ArrayList<String>()
            if (batch || template.contains("{{text}}") || template.contains("{{origin}}")) {
                if (!template.contains("{{glossary}}") && glossaryBlock.isNotEmpty()) constraints += glossaryBlock
                if (!template.contains("{{tone}}") && toneBlock.isNotEmpty()) constraints += toneBlock
            }
            if (constraints.isNotEmpty()) result = constraints.joinToString("\n\n") + "\n\n" + result
        }
        fields.forEach { (key, value) -> result = result.replace("{{$key}}", value) }
        if (batch && !result.contains("{{segments}}")) result += "\n\n$input"
        result = result.replace("{{segments}}", input).replace("{{text}}", if (system && !subtitle) request.texts.firstOrNull().orEmpty() else input).replace("{{origin}}", input)
        return if (system) result else result.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
