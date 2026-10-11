package icu.nullptr.polyglot.captions

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource

/** JSON3 and srv3 expose equivalent timing data; Android commonly supplies srv3. */
object TimedTextDecoder {
    fun decode(text: String): List<TimedTextEvent>? = runCatching {
        when {
            text.trimStart().startsWith('{') -> SubtitleProcessing.parseJson3(text)
            text.trimStart().startsWith('<') && text.contains("<timedtext") -> srv3(text)
            else -> null
        }
    }.getOrNull()
    private fun srv3(text: String): List<TimedTextEvent> {
        val result = ArrayList<TimedTextEvent>()
        val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        // Captions have no external entities. Disable resolution before processing network input.
        parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val handler = object : DefaultHandler() {
            var start = 0.0; var duration = 0.0; var append = false; var inParagraph = false; var inSegment = false
            var offset = 0.0; var asr = false
            val segments = ArrayList<TimedTextSegment>()
            val buffer = StringBuilder()
            fun flush() {
                if (buffer.isNotEmpty()) segments += TimedTextSegment(buffer.toString(), offset, asr)
                buffer.setLength(0)
            }
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (qName) {
                    "p" -> {
                        start = attributes.getValue("t")?.toDoubleOrNull() ?: 0.0
                        duration = attributes.getValue("d")?.toDoubleOrNull() ?: 0.0
                        append = attributes.getValue("a") == "1"
                        segments.clear(); buffer.setLength(0); inParagraph = true; offset = 0.0; asr = false
                    }
                    "s" -> if (inParagraph) { flush(); offset = attributes.getValue("t")?.toDoubleOrNull() ?: 0.0; asr = attributes.getValue("ac") != null; inSegment = true }
                    "br" -> if (inParagraph) buffer.append('\n')
                }
            }
            override fun characters(chars: CharArray, start: Int, length: Int) { if (inParagraph) buffer.append(chars, start, length) }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (qName) {
                    "s" -> { flush(); inSegment = false }
                    "p" -> { flush(); result += TimedTextEvent(start, duration, segments.toList(), append); inParagraph = false }
                }
            }
        }
        parser.newSAXParser().parse(InputSource(StringReader(text)), handler)
        return result
    }

    /** Recover incremental words before native cumulative text is turned into display windows. */
    fun fromNative(starts: List<Long>, ends: List<Long>, texts: List<String>): List<TimedTextEvent> {
        val result = ArrayList<TimedTextEvent>()
        var previous = ""; var previousEnd = Long.MIN_VALUE
        var groupStart = 0L; var groupEnd = 0L
        val segments = ArrayList<TimedTextSegment>()
        fun flush() {
            if (segments.isNotEmpty()) result += TimedTextEvent(groupStart.toDouble(), (groupEnd - groupStart).toDouble(), segments.toList())
            segments.clear()
        }
        for (index in texts.indices) {
            val start = starts[index]; val end = ends[index]; val text = texts[index]
            val same = end == previousEnd && text.startsWith(previous)
            val delta = if (same) text.substring(previous.length) else text
            if (!same) { flush(); groupStart = start; groupEnd = end }
            if (delta == "\n") {
                result += TimedTextEvent(start.toDouble(), 0.0, listOf(TimedTextSegment("\n")), true)
            } else if (delta.isNotEmpty()) segments += TimedTextSegment(delta, (start - groupStart).toDouble())
            previous = text; previousEnd = end
        }
        flush()
        return result.sortedBy { it.startMs }
    }
}
