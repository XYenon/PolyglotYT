package icu.nullptr.polyglot.translate.providers

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import icu.nullptr.polyglot.module
import icu.nullptr.polyglot.translate.TranslationRequest
import icu.nullptr.polyglot.translate.TranslationResult
import icu.nullptr.polyglot.translate.Translator
import java.net.HttpURLConnection
import java.net.URL

object OpenAICompatibleTranslator : Translator {
    const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
    const val DEFAULT_MODEL = "gpt-4o-mini"
    val THINK_BLOCK = Regex("^<think>[\\s\\S]*?</think>")

    override fun translate(request: TranslationRequest): TranslationResult {
        val nonBlankTexts = request.texts.filter { text -> text.isNotBlank() }
        if (nonBlankTexts.size <= 1) {
            return TranslationResult(
                texts = request.texts.map { text ->
                    if (text.isBlank()) text else translateOne(text, request)
                },
            )
        }

        val translations = translateBatch(nonBlankTexts, request)
        var translatedIndex = 0
        return TranslationResult(
            texts = request.texts.map { text ->
                if (text.isBlank()) text else translations[translatedIndex++]
            },
        )
    }

    private fun translateOne(text: String, request: TranslationRequest): String {
        val response = request(text, request, batch = false)
        return parseTranslation(response)
    }

    private fun translateBatch(texts: List<String>, request: TranslationRequest): List<String> {
        val input = JsonArray().apply {
            texts.forEach { text -> add(text) }
        }.toString()
        val response = request(input, request, batch = true)
        return parseBatchTranslations(response, texts.size)
    }

    private fun request(text: String, request: TranslationRequest, batch: Boolean): String {
        val apiKey = module.config.openAiApiKey
        require(apiKey.isNotBlank()) { "OpenAI-compatible API key is not configured" }

        val connection = URL(module.config.openAiEndpoint.ifBlank { DEFAULT_ENDPOINT })
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = request.timeoutMs
        connection.readTimeout = request.timeoutMs
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")

        val body = buildRequestBody(text, request, batch)
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        return connection.use {
            it.readBodyOrThrow()
        }
    }

    private fun buildRequestBody(
        text: String,
        request: TranslationRequest,
        batch: Boolean,
    ): String {
        val systemPrompt = module.config.openAiSystemPrompt
        var userPrompt = module.config.openAiUserPrompt
            .replace("{{to}}", request.targetLanguage)
            .replace("{{origin}}", text)
        if (batch) {
            userPrompt += BATCH_RESPONSE_INSTRUCTION
        }

        val messages = JsonArray().apply {
            add(
                JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                },
            )
            add(
                JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userPrompt)
                },
            )
        }

        return JsonObject().apply {
            addProperty("model", module.config.openAiModel.ifBlank { DEFAULT_MODEL })
            addProperty("temperature", 1.0)
            add("messages", messages)
        }.toString()
    }

    private fun parseTranslation(body: String): String {
        return responseContent(body).trim()
    }

    private fun parseBatchTranslations(body: String, expectedCount: Int): List<String> {
        val content = responseContent(body)
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val arrayStart = content.indexOf('[')
        val arrayEnd = content.lastIndexOf(']')
        check(arrayStart >= 0 && arrayEnd > arrayStart) {
            "OpenAI-compatible batch response contained no JSON array"
        }
        val translations = JsonParser.parseString(content.substring(arrayStart, arrayEnd + 1))
            .asJsonArray
            .map { element -> element.asString }
        check(translations.size == expectedCount) {
            "OpenAI-compatible batch response returned ${translations.size} results " +
                "for $expectedCount texts"
        }
        return translations
    }

    private fun responseContent(body: String): String {
        val content = JsonParser.parseString(body)
            .asJsonObject["choices"]
            .asJsonArray[0]
            .asJsonObject["message"]
            .asJsonObject["content"]
            .asString
        return content.replace(THINK_BLOCK, "").trim()
    }

    private fun HttpURLConnection.readBodyOrThrow(): String {
        if (responseCode in 200..299) {
            return inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        val errorBody = errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        throw IllegalStateException("OpenAI-compatible translate failed: HTTP $responseCode $responseMessage $errorBody")
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private const val BATCH_RESPONSE_INSTRUCTION =
        "\n\nThe source above is a JSON array. Translate every element independently and " +
            "return only a JSON array of translated strings in the same order. " +
            "Do not add markdown, labels, explanations, or change the number of elements."
}
