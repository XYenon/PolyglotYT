package icu.nullptr.polyglot.captions

import android.text.SpannableStringBuilder
import android.text.Spanned
import icu.nullptr.polyglot.core.ConfigManager
import icu.nullptr.polyglot.module

object BilingualFormatter {
    fun format(original: CharSequence?, translated: String?): CharSequence =
        format(original, translated, module.config.subtitleMode)

    internal fun format(original: CharSequence?, translated: String?, mode: String): CharSequence {
        val source = original ?: ""
        val translatedText = CaptionCue.normalize(translated.orEmpty())
        if (translatedText.isBlank() || translatedText == CaptionCue.normalize(source.toString()) || source.isBlank()) {
            return source
        }
        val originalFirst = when (mode) {
            ConfigManager.SUBTITLE_ORIGINAL_FIRST -> true
            ConfigManager.SUBTITLE_TRANSLATION_FIRST -> false
            else -> return translatedText
        }
        // Preserve native line breaks, spaces and style spans. Normalize only cache keys and
        // translated text, never the original renderer input.
        return if (source is Spanned) {
            if (originalFirst) SpannableStringBuilder(source).append('\n').append(translatedText)
            else SpannableStringBuilder(translatedText).append('\n').append(source)
        } else if (originalFirst) "$source\n$translatedText" else "$translatedText\n$source"
    }
}
