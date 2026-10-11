package icu.nullptr.polyglot.translate.providers

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import icu.nullptr.polyglot.captions.SubtitleWord
import icu.nullptr.polyglot.translate.BatchResponseParser
import icu.nullptr.polyglot.translate.PromptRenderer
import icu.nullptr.polyglot.translate.TranslationRequest
import icu.nullptr.polyglot.translate.TranslationResult
import icu.nullptr.polyglot.translate.Translator
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

object OpenAICompatibleTranslator : Translator {
    const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
    const val DEFAULT_MODEL = "gpt-4o-mini"
    val THINK_BLOCK = Regex("^<think>[\\s\\S]*?</think>\\s*")
    private data class Message(val role: String, val content: String)
    private val histories = ConcurrentHashMap<String, List<Message>>()
    fun clearHistory() = histories.clear()
    private fun historyKey(request: TranslationRequest) = listOf(request.providerSettings.openAiEndpoint, request.providerSettings.openAiModel,
        request.providerSettings.openAiApiKey, request.sourceLanguage, request.targetLanguage, request.document.videoId).joinToString("\u0000")

    override fun translate(request: TranslationRequest): TranslationResult {
        request.cancellation.checkActive()
        val settings = request.providerSettings
        val batch = settings.useBatch
        val templates = if (batch) PromptRenderer.batch(settings) else PromptRenderer.single(settings)
        if (!batch && request.texts.size > 1) return TranslationResult(request.texts.mapIndexed { index, text ->
            translate(request.copy(texts = listOf(text), onPartial = { _, partial -> request.onPartial?.invoke(index, partial) },
                onCompleted = { _, result -> request.onCompleted?.invoke(index, result) })).texts.single()
        })
        val protocol = PromptRenderer.protocol(settings)
        val input = if (batch) PromptRenderer.segments(request.texts, protocol) else request.texts.firstOrNull().orEmpty()
        val system = PromptRenderer.render(templates.first, request, input, system = true)
        val user = PromptRenderer.render(templates.second, request, input, batch = batch)
        val prior = if (settings.useContext) histories[historyKey(request)].orEmpty() else emptyList()
        var response: String
        val delivered = HashMap<Int, String>()
        val completed = HashMap<Int, String>()
        fun consume(content: String) {
            if (content.startsWith("<think>") && !content.contains("</think>")) return
            val completeContent = if (protocol == "line") content.substringBeforeLast('\n', "") else content
            if (batch) {
                val closed = runCatching { BatchResponseParser.parse(completeContent, protocol, request.texts.size) }.getOrDefault(emptyMap())
                closed.forEach { (id, text) -> if (id !in completed) { completed[id] = text; request.onCompleted?.invoke(id, text) } }
            }
            if (settings.streamRenderMode == "disabled") return
            val parseContent = if (settings.streamRenderMode == "segment" && protocol == "line") content.substringBeforeLast('\n', "") else content
            val entries = if (!batch) {
                if (settings.streamRenderMode == "segment") emptyMap() else mapOf(0 to content)
            } else if (settings.streamRenderMode == "realtime")
                BatchResponseParser.partial(content, protocol, request.texts.size) else
                runCatching { BatchResponseParser.parse(parseContent, protocol, request.texts.size) }.getOrDefault(emptyMap())
            entries.forEach { (id, text) ->
                if (text.isNotBlank() && id !in completed && delivered[id] != text) {
                    delivered[id] = text
                    request.onPartial?.invoke(id, text)
                }
            }
        }
        if (settings.useStream) {
            response = try {
                chat(request, system, user, prior, true, ::consume).also { content ->
                    if (batch) check(BatchResponseParser.parse(content, protocol, request.texts.size).size == request.texts.size) { "Incomplete stream translation" }
                }
            } catch (error: Exception) {
                request.cancellation.checkActive()
                chat(request, system, user, prior, false)
            }
        } else response = chat(request, system, user, prior, false)
        val texts = if (batch) {
            val parsed = BatchResponseParser.parse(response, protocol, request.texts.size)
            List(request.texts.size) { index -> completed[index] ?: checkNotNull(parsed[index]) { "Batch response missing ID $index" } }
        } else listOf(BatchResponseParser.decode(response).also { check(it.isNotBlank()) { "Empty translation" } })
        if (settings.useContext) {
            // contextSize counts messages, preserving complete user/assistant turns as kiss does.
            val count = (settings.contextSize.coerceAtLeast(0) / 2) * 2
            histories[historyKey(request)] = (prior + Message("user", user) + Message("assistant", response)).takeLast(count)
        }
        return TranslationResult(texts)
    }

    fun subtitle(request: TranslationRequest, words: List<SubtitleWord>, onContent: ((String) -> Unit)? = null): String {
        val input = JsonArray().apply {
            words.forEachIndexed { index, word -> add(JsonObject().apply {
                addProperty("id", index); addProperty("text", word.text)
                if (index < words.lastIndex) {
                    val pause = (words[index + 1].start - word.end).roundToLong()
                    if (pause > 0) addProperty("pauseMs", pause)
                }
            }) }
        }.toString()
        val system = PromptRenderer.render(request.providerSettings.subtitleSystemPrompt, request, input, system = true, subtitle = true)
        if (request.providerSettings.useStream && onContent != null) {
            try {
                return chat(request, system, input, emptyList(), true, onContent).also { check(it.isNotBlank()) { "Empty subtitle stream" } }
            } catch (error: Exception) { request.cancellation.checkActive() }
        }
        return chat(request, system, input, emptyList(), false)
    }

    fun summarize(request: TranslationRequest, transcript: String): String {
        val user = listOf(
            request.document.title.takeIf { it.isNotBlank() }?.let { "Title: $it" },
            request.document.description.takeIf { it.isNotBlank() }?.let { "Description: $it" },
            "\nTranscript:\n${transcript.take(8000)}",
        ).filterNotNull().joinToString("\n")
        return chat(request, request.providerSettings.summarySystemPrompt, user, emptyList(), false)
    }

    internal fun buildRequestBody(request: TranslationRequest, system: String, user: String, stream: Boolean): String =
        body(request, system, user, emptyList(), stream)

    private fun body(request: TranslationRequest, system: String, user: String, history: List<Message>, stream: Boolean): String {
        val messages = JsonArray().apply {
            if (system.isNotBlank()) add(JsonObject().apply { addProperty("role", "system"); addProperty("content", system) })
            history.forEach { message -> add(JsonObject().apply { addProperty("role", message.role); addProperty("content", message.content) }) }
            add(JsonObject().apply { addProperty("role", "user"); addProperty("content", user) })
        }
        return JsonObject().apply {
            addProperty("model", request.providerSettings.openAiModel.ifBlank { DEFAULT_MODEL })
            addProperty("stream", stream)
            add("messages", messages)
        }.toString()
    }

    private fun chat(request: TranslationRequest, system: String, user: String, history: List<Message>, stream: Boolean, onContent: ((String) -> Unit)? = null): String {
        val settings = request.providerSettings
        require(settings.openAiApiKey.isNotBlank()) { "OpenAI-compatible API key is not configured" }
        request.cancellation.checkActive()
        val connection = URL(settings.openAiEndpoint.ifBlank { DEFAULT_ENDPOINT }).openConnection() as HttpURLConnection
        request.cancellation.register(connection)
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = request.timeoutMs; connection.readTimeout = request.timeoutMs
            connection.setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${settings.openAiApiKey}")
            connection.outputStream.use { it.write(body(request, system, user, history, stream).toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299) { "OpenAI-compatible request failed: HTTP ${connection.responseCode} ${connection.responseMessage}" }
            if (!stream || !connection.contentType.orEmpty().contains("text/event-stream")) {
                val content = responseContent(connection.inputStream.bufferedReader().use { it.readText() })
                request.cancellation.checkActive()
                return content
            }
            val content = StringBuilder()
            connection.inputStream.bufferedReader().use { reader ->
                val data = StringBuilder()
                fun consumeEvent() {
                    val text = data.toString().trim()
                    data.setLength(0)
                    if (text.isEmpty() || text == "[DONE]") return
                    val event = JsonParser.parseString(text).asJsonObject
                    check(!event.has("error")) { "AI stream returned an error" }
                    val choices = event["choices"]?.asJsonArray
                    val choice = choices?.firstOrNull()?.asJsonObject ?: return
                    check(choice["finish_reason"]?.takeUnless { it.isJsonNull }?.asString != "length") { "AI stream was truncated" }
                    val delta = choice["delta"]?.asJsonObject?.get("content")
                    if (delta != null && !delta.isJsonNull) {
                        content.append(contentText(delta))
                        onContent?.invoke(content.toString().replace(THINK_BLOCK, "").trimStart())
                    }
                }
                while (true) {
                    request.cancellation.checkActive()
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) consumeEvent() else if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).trimStart())
                    }
                }
                consumeEvent()
            }
            request.cancellation.checkActive()
            return content.toString().replace(THINK_BLOCK, "").trim()
        } finally {
            request.cancellation.unregister(connection); connection.disconnect()
        }
    }
    private fun contentText(value: com.google.gson.JsonElement): String = when {
        value.isJsonPrimitive -> value.asString
        value.isJsonArray -> value.asJsonArray.joinToString("") { it.asJsonObject["text"]?.asString.orEmpty() }
        else -> ""
    }
    private fun responseContent(body: String): String {
        val message = JsonParser.parseString(body).asJsonObject["choices"]?.asJsonArray?.firstOrNull()?.asJsonObject?.get("message")?.asJsonObject
        val content = message?.get("content")?.takeUnless { it.isJsonNull }?.let { contentText(it) }.orEmpty().replace(THINK_BLOCK, "").trim()
        check(content.isNotBlank()) { "OpenAI-compatible response contained no content" }
        return content
    }
}
