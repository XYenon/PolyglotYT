package icu.nullptr.polyglot.translate

/** kiss uses language names in translation prompts and language codes in subtitle segmentation. */
object PromptLanguages {
    fun code(value: String): String = when (value.lowercase()) {
        "zh-hans", "zh-cn" -> "zh-CN"
        "zh-hant", "zh-tw", "zh-hk" -> "zh-TW"
        else -> value
    }
    private val names = mapOf("auto" to "AutoDetect", "en" to "English", "zh-CN" to "Simplified Chinese", "zh-TW" to "Traditional Chinese",
        "ar" to "Arabic", "bg" to "Bulgarian", "ca" to "Catalan", "hr" to "Croatian", "cs" to "Czech", "da" to "Danish", "nl" to "Dutch", "fa" to "Persian", "fi" to "Finnish", "fr" to "French", "de" to "German", "el" to "Greek", "hi" to "Hindi", "hu" to "Hungarian", "id" to "Indonesian", "it" to "Italian", "ja" to "Japanese", "ko" to "Korean", "ms" to "Malay", "mt" to "Maltese", "nb" to "Norwegian", "pl" to "Polish", "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian", "sk" to "Slovak", "sl" to "Slovenian", "es" to "Spanish", "sv" to "Swedish", "ta" to "Tamil", "te" to "Telugu", "th" to "Thai", "tr" to "Turkish", "uk" to "Ukrainian", "vi" to "Vietnamese")
    fun name(value: String) = names[code(value)] ?: names[value.substringBefore('-')] ?: value
}
