package icu.nullptr.polyglot.translate

import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/** Only a digest of request settings is stored; keys and authorization headers never reach disk. */
class TranslationCache(private val directory: File) {
    private val gson = Gson()
    fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized fun read(key: String): String? = runCatching {
        val file = File(directory, digest(key) + ".json")
        if (!file.isFile) null else gson.fromJson(file.readText(), String::class.java)
    }.getOrNull()
    @Synchronized fun write(key: String, text: String) {
        if (text.isBlank()) return
        runCatching {
            directory.mkdirs()
            val file = File(directory, digest(key) + ".json")
            val temporary = File(directory, file.name + ".tmp")
            temporary.writeText(gson.toJson(text))
            if (!temporary.renameTo(file)) temporary.delete()
        }
    }
}
