package icu.nullptr.polyglot.youtube

import icu.nullptr.polyglot.translate.TranslationManager
import icu.nullptr.polyglot.translate.VideoContext
import icu.nullptr.polyglot.util.logW
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

/** Fetch the same video title/description context used by kiss's watch-page caption provider. */
internal object VideoContextLoader {
    fun videoId(track: Any?): String? {
        if (track == null) return null
        var clazz: Class<*>? = track.javaClass
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                if (field.type != String::class.java || java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val text = runCatching { field.isAccessible = true; field.get(track) as? String }.getOrNull() ?: continue
                if (!text.contains("timedtext")) continue
                Regex("[?&]v=([A-Za-z0-9_-]{11})(?:&|$)").find(text)?.let { return it.groupValues[1] }
            }
            clazz = clazz.superclass
        }
        return null
    }
    fun load(videoId: String, callback: (VideoContext) -> Unit) {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return
        val request = TranslationManager.request(emptyList(), "auto")
        TranslationManager.specialAsync(request) {
            try {
                val key = "video-context:$videoId"
                val cached = TranslationManager.cached(key)
                if (cached != null) {
                    val details = JsonParser.parseString(cached).asJsonObject
                    request.cancellation.checkActive()
                    callback(VideoContext(videoId, details["title"]?.asString.orEmpty(), details["shortDescription"]?.asString.orEmpty()))
                    return@specialAsync
                }
                val connection = URL("https://www.youtube.com/watch?v=$videoId&hl=en").openConnection() as HttpURLConnection
                request.cancellation.register(connection)
                try {
                    connection.connectTimeout = request.timeoutMs; connection.readTimeout = request.timeoutMs
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    check(connection.responseCode in 200..299) { "Video context HTTP ${connection.responseCode}" }
                    val html = connection.inputStream.bufferedReader().use { it.readText() }
                    val marker = html.indexOf("ytInitialPlayerResponse")
                    if (marker < 0) return@specialAsync
                    val start = html.indexOf('{', marker)
                    if (start < 0) return@specialAsync
                    var depth = 0; var quoted = false; var escaped = false; var end = -1
                    for (index in start until html.length) {
                        val char = html[index]
                        if (quoted) {
                            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                        } else when (char) {
                            '"' -> quoted = true
                            '{' -> depth++
                            '}' -> { depth--; if (depth == 0) { end = index + 1; break } }
                        }
                    }
                    if (end < 0) return@specialAsync
                    val details = JsonParser.parseString(html.substring(start, end)).asJsonObject["videoDetails"]?.asJsonObject ?: return@specialAsync
                    request.cancellation.checkActive()
                    TranslationManager.cache(key, details.toString())
                    callback(VideoContext(videoId, details["title"]?.asString.orEmpty(), details["shortDescription"]?.asString.orEmpty()))
                } finally { request.cancellation.unregister(connection); connection.disconnect() }
            } catch (error: Exception) {
                request.cancellation.checkActive()
                logW("VideoContextLoader", "Unable to load video context", error)
            }
        }
    }
}
