package icu.nullptr.polyglot.translate

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Shared complete/partial response parsing; IDs are never inferred from unstructured prose. */
object BatchResponseParser {
    fun strip(content: String) = content.trim().removePrefix("```json").removePrefix("```xml").removePrefix("```text").removePrefix("```").removeSuffix("```").trim()
    fun objects(content: String): List<com.google.gson.JsonObject> {
        val result = ArrayList<com.google.gson.JsonObject>()
        var start = -1; var depth = 0; var quoted = false; var escaped = false
        for ((index, char) in content.withIndex()) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> { if (depth == 0) start = index; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        runCatching { JsonParser.parseString(content.substring(start, index + 1)).asJsonObject }.getOrNull()?.let { result += it }
                        start = -1
                    }
                }
            }
        }
        return result
    }
    private fun text(value: JsonElement?): String? = when {
        value == null || value.isJsonNull -> null
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
        value.isJsonArray -> value.asJsonArray.firstOrNull()?.let { text(it) }
        else -> null
    }
    fun parse(raw: String, protocol: String, count: Int): Map<Int, String> {
        val content = strip(raw)
        val result = LinkedHashMap<Int, String>()
        fun put(id: Int?, value: String?) {
            check(id != null && id in 0 until count) { "Invalid translation ID $id" }
            check(!result.containsKey(id)) { "Duplicate translation ID $id" }
            if (!value.isNullOrBlank()) result[id] = decode(value)
        }
        when (protocol) {
            "xml" -> Regex("<t\\s+id\\s*=\\s*[\"'](\\d+)[\"'][^>]*>([\\s\\S]*?)</t>").findAll(content).forEach { put(it.groupValues[1].toIntOrNull(), it.groupValues[2]) }
            "line" -> content.lines().forEach { line ->
                Regex("^\\s*(\\d+)\\s*\\|\\s*(.*)$").matchEntire(line)?.let { put(it.groupValues[1].toIntOrNull(), it.groupValues[2]) }
            }
            else -> objects(content).forEach { item ->
                val id = item["id"]?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()
                put(id, text(item["text"] ?: item["translation"] ?: item["result"]))
            }
        }
        return result
    }
    fun partial(raw: String, protocol: String, count: Int): Map<Int, String> {
        val result = runCatching { parse(raw, protocol, count) }.getOrDefault(emptyMap()).toMutableMap()
        if (protocol == "xml") {
            Regex("<t\\s+id\\s*=\\s*[\"'](\\d+)[\"'][^>]*>([^<]*(?:<(?!/t>)[^<]*)*)$").find(raw)?.let {
                val id = it.groupValues[1].toIntOrNull()
                if (id != null && id in 0 until count) result[id] = decode(it.groupValues[2])
            }
        } else if (protocol == "json") {
            val unfinished = raw.substringAfterLast("},").substringAfterLast("{", "")
            val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(unfinished)?.groupValues?.get(1)?.toIntOrNull()
            val fragment = Regex("\"(?:text|translation)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)$").find(unfinished)?.groupValues?.get(1)
            if (id != null && id in 0 until count && fragment != null) {
                val safe = if (fragment.endsWith('\\')) fragment.dropLast(1) else fragment
                runCatching { JsonParser.parseString("\"$safe\"").asString }.getOrNull()?.let { result[id] = decode(it) }
            }
        }
        return result
    }
    fun decode(raw: String): String {
        var text = raw.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("<[^>]+>"), "")
        text = Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(text) { match ->
            runCatching {
                val token = match.groupValues[1]
                val code = if (token.startsWith('x')) token.drop(1).toInt(16) else token.toInt()
                String(Character.toChars(code))
            }.getOrDefault(match.value)
        }
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&").trim()
    }
}
