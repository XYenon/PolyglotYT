package icu.nullptr.polyglot

import com.google.gson.JsonParser
import icu.nullptr.polyglot.captions.*
import icu.nullptr.polyglot.translate.*
import icu.nullptr.polyglot.translate.providers.OpenAICompatibleTranslator
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer

class SubtitleAlignmentTest {
    private fun settings() = TranslationProviderSettings("", "", "", "http://localhost", "test-key", "keep-this-model")
    private fun request(settings: TranslationProviderSettings = settings()) = TranslationRequest(listOf("Hello", "World"), "en", "zh-Hans", "", 5000, settings)

    @Test fun segmentationMatchesPinnedKissSource() {
        val resource = javaClass.getResourceAsStream("/kiss-subtitles.json.gz")!!
        val fixtures = java.util.zip.GZIPInputStream(resource).reader().use { JsonParser.parseReader(it).asJsonArray }
        fixtures.forEachIndexed { index, fixture ->
            val data = fixture.asJsonObject
            val language = data["language"].asString
            val events = SubtitleProcessing.parseJson3("{\"events\":${data["events"]}}")
            val prepared = SubtitleProcessing.prepare(events, language)
            val expectedWords = data["words"].asJsonArray
            assertEquals("case $index words", expectedWords.size(), prepared.words.size)
            prepared.words.forEachIndexed { wordIndex, word ->
                val expected = expectedWords[wordIndex].asJsonObject
                assertEquals("case $index word $wordIndex", expected["text"].asString, word.text)
                assertEquals(expected["start"].asDouble, word.start, 0.00001)
                assertEquals(expected["end"].asDouble, word.end, 0.00001)
            }
            for (mode in listOf("rule", "statistical")) {
                val actual = SubtitleProcessing.segment(prepared, language, mode, 100)
                val expected = data[mode].asJsonArray
                assertEquals("case $index $mode count", expected.size(), actual.size)
                actual.forEachIndexed { cueIndex, cue ->
                    val item = expected[cueIndex].asJsonObject
                    assertEquals("case $index $mode cue $cueIndex", item["text"].asString, cue.text)
                    assertEquals(item["start"].asDouble, cue.start, 0.00001)
                    assertEquals(item["end"].asDouble, cue.end, 0.00001)
                }
            }
            val chunks = SubtitleProcessing.chunks(prepared.words)
            val expectedChunks = data["chunks"].asJsonArray
            assertEquals("case $index chunks", expectedChunks.size(), chunks.size)
            chunks.forEachIndexed { chunkIndex, chunk -> assertEquals(expectedChunks[chunkIndex].asJsonArray.size(), chunk.size) }
        }
    }

    @Test fun renderedPromptsMatchPinnedKissSourceExactly() {
        val fixtures = javaClass.getResourceAsStream("/kiss-prompts.json")!!.reader().use { JsonParser.parseReader(it).asJsonArray }
        fixtures.forEachIndexed { index, fixture ->
            val data = fixture.asJsonObject
            val kind = data["kind"].asString
            val mode = data["mode"].asString
            val settings = settings().copy(tone = data["tone"].asString, glossary = data["aiTerms"].asString, singlePromptMode = mode, batchPromptMode = mode)
            val doc = data["docInfo"].asJsonObject
            val document = VideoContext(title = doc["title"]?.asString.orEmpty(), description = doc["description"]?.asString.orEmpty(), summary = doc["summary"]?.asString.orEmpty(), context = doc["context"]?.asString.orEmpty())
            val request = request(settings).copy(texts = data["texts"].asJsonArray.map { it.asString }, document = document)
            if (kind == "subtitle") {
                assertEquals("subtitle prompt $index", data["system"].asString, PromptRenderer.render(KissPrompts.SUBTITLE_SYSTEM, request, "", system = true, subtitle = true))
            } else {
                val templates = if (kind == "batch") PromptRenderer.batch(settings) else PromptRenderer.single(settings)
                val input = if (kind == "batch") PromptRenderer.segments(request.texts, mode) else request.texts.first()
                assertEquals("system prompt $index", data["system"].asString, PromptRenderer.render(templates.first, request, input, system = true))
                assertEquals("user prompt $index", data["user"].asString, PromptRenderer.render(templates.second, request, input, batch = kind == "batch"))
            }
        }
    }
    @Test fun nativeCaptionFormattingPreservesWhitespaceAndProgressiveText() {
        val current = "  Hello\nworld "
        val full = "  Hello\nworld again. "
        val mode = icu.nullptr.polyglot.core.ConfigManager.SUBTITLE_ORIGINAL_FIRST
        assertEquals("$current\n你好，世界。", icu.nullptr.polyglot.captions.BilingualFormatter.format(current, "你好，世界。", mode).toString())
        assertEquals("$full\n你好，世界。", icu.nullptr.polyglot.captions.BilingualFormatter.format(full, "你好，世界。", mode).toString())
        assertEquals("你好，世界。\n$current", icu.nullptr.polyglot.captions.BilingualFormatter.format(current, "你好，世界。", icu.nullptr.polyglot.core.ConfigManager.SUBTITLE_TRANSLATION_FIRST).toString())
        assertEquals("你好，世界。", icu.nullptr.polyglot.captions.BilingualFormatter.format(current, "你好，世界。", icu.nullptr.polyglot.core.ConfigManager.SUBTITLE_TRANSLATION_ONLY).toString())
    }
    @Test fun untranslatedOrEmptyNativeFramesKeepTheirOriginalObject() {
        val original = StringBuilder("  original\nline ")
        val mode = icu.nullptr.polyglot.core.ConfigManager.SUBTITLE_ORIGINAL_FIRST
        assertSame(original, icu.nullptr.polyglot.captions.BilingualFormatter.format(original, null, mode))
        assertSame(original, icu.nullptr.polyglot.captions.BilingualFormatter.format(original, "original line", mode))
        val gap = StringBuilder("\n")
        assertSame(gap, icu.nullptr.polyglot.captions.BilingualFormatter.format(gap, "previous translation", mode))
    }
    @Test fun segmentedPresentationReplacesNativeFragmentsAndClearsGaps() {
        val cues = listOf(SubtitleCue(0.0, 1000.0, "Hello world again.", "你好，世界。"), SubtitleCue(2000.0, 3000.0, "Next sentence.", "下一句。"))
        val scheduler = SubtitleScheduler()
        val cue = scheduler.cueAt(cues, 500.0)!!
        val nativeFrame = StringBuilder("Hello\nwor")
        val source = icu.nullptr.polyglot.captions.SegmentedCaptionPresentation.source(nativeFrame, cue, hasTimeline = true)
        val rendered = icu.nullptr.polyglot.captions.BilingualFormatter.format(source, cue.translation, icu.nullptr.polyglot.core.ConfigManager.SUBTITLE_ORIGINAL_FIRST).toString()
        assertEquals("Hello world again.\n你好，世界。", rendered)
        assertFalse(rendered.contains("Hello\nwor"))
        val gap = scheduler.cueAt(cues, 1500.0)
        assertEquals("", icu.nullptr.polyglot.captions.SegmentedCaptionPresentation.source(nativeFrame, gap, true))
        assertSame(nativeFrame, icu.nullptr.polyglot.captions.SegmentedCaptionPresentation.source(nativeFrame, null, false))
        assertEquals("Next sentence.", scheduler.cueAt(cues, 2000.0)?.text)
    }
    @Test fun segmentedNativeIntervalsPreserveFractionalTimingAndCaptionGaps() {
        val words = listOf(SubtitleWord("Hello", 0.5, 300.5), SubtitleWord("world", 400.5, 700.5), SubtitleWord("Next", 1000.0, 1500.0))
        val cues = listOf(SubtitleCue(0.5, 700.5, "Hello world."), SubtitleCue(1000.0, 1500.0, "Next."))
        val intervals = icu.nullptr.polyglot.captions.SegmentedCaptionPresentation.intervals(words, cues)
        assertEquals(listOf(1L, 301L, 401L, 701L, 1000L), intervals.map { it.startMs })
        assertEquals("Hello world.", intervals.single { it.startMs == 301L }.text)
        assertEquals("", intervals.single { it.startMs == 701L }.text)
        assertEquals("Next.", intervals.last().text)
        assertEquals(1500L, intervals.last().endMs)
    }
    @Test fun nativeIntervalsCanSelectNewAiBoundariesWithoutRevertingToOriginalText() {
        val words = listOf(SubtitleWord("Hello", 0.5, 300.5), SubtitleWord("world", 400.5, 700.5))
        val fallback = listOf(SubtitleCue(0.5, 700.5, "Hello world."))
        val intervals = icu.nullptr.polyglot.captions.SegmentedCaptionPresentation.intervals(words, fallback)
        assertTrue(intervals.any { it.startMs == 401L })
        val aiCues = listOf(SubtitleCue(0.5, 300.5, "Hello.", "你好。"), SubtitleCue(400.5, 700.5, "World.", "世界。"))
        val newCue = SubtitleScheduler().cueAt(aiCues, 401.0)
        val source = icu.nullptr.polyglot.captions.SegmentedCaptionPresentation.source(intervals.first().text, newCue, true)
        assertEquals("World.", source)
        assertEquals("World.\n世界。", icu.nullptr.polyglot.captions.BilingualFormatter.format(source, newCue?.translation, icu.nullptr.polyglot.core.ConfigManager.SUBTITLE_ORIGINAL_FIRST).toString())
    }
    @Test fun batchProtocolsKeepIdsAndText() {
        assertEquals(mapOf(1 to "世界", 0 to "你好"), BatchResponseParser.parse("```json\n[{\"id\":1,\"text\":\"世界\"},{\"id\":0,\"text\":\"你好\"}]\n```", "json", 2))
        assertEquals(mapOf(0 to "你好\n世界"), BatchResponseParser.parse("<root><t id=\"0\">你好<br>世界</t></root>", "xml", 1))
        assertEquals(mapOf(0 to "你好", 1 to "世界"), BatchResponseParser.parse("0 | 你好\n1 | 世界", "line", 2))
        assertTrue(BatchResponseParser.parse("I cannot translate this", "json", 1).isEmpty())
        assertTrue(BatchResponseParser.parse("I cannot translate this", "line", 1).isEmpty())
    }
    @Test fun invalidIdsNeverShiftTranslations() {
        for (content in listOf("[{\"id\":0,\"text\":\"a\"},{\"id\":0,\"text\":\"b\"}]", "[{\"id\":2,\"text\":\"a\"}]")) {
            assertTrue(runCatching { BatchResponseParser.parse(content, "json", 2) }.isFailure)
        }
    }
    @Test fun truncatedJsonKeepsOnlyCompleteObjectsAndShowsDraftSeparately() {
        val content = "[{\"id\":0,\"text\":\"你好\"},{\"id\":1,\"text\":\"世"
        assertEquals(mapOf(0 to "你好"), BatchResponseParser.parse(content, "json", 2))
        assertEquals(mapOf(0 to "你好", 1 to "世"), BatchResponseParser.partial(content, "json", 2))
    }
    @Test fun promptsRenderContextConstraintsAndLiteralInput() {
        val request = request(settings().copy(tone = "formal", glossary = "React,React\ncomponent,组件")).copy(document = VideoContext(title = "My video", description = "Description", summary = "Summary"))
        val input = PromptRenderer.segments(request.texts, "json")
        val prompt = PromptRenderer.render(KissPrompts.BATCH_JSON_USER, request, input, batch = true)
        assertTrue(prompt.startsWith("Title: My video\n\nDescription: Description\n\nSummary: Summary"))
        assertTrue(prompt.contains("component translates to 组件"))
        assertTrue(prompt.contains("[formal]"))
        assertTrue(prompt.endsWith(input))
        val literal = "{{to}} $ dollar"
        assertTrue(PromptRenderer.render(KissPrompts.SINGLE_CONCISE_USER, request, literal).endsWith(literal))
    }
    @Test fun nativeCumulativeTextRecoversIncrementalWords() {
        val source = TimedTextDecoder.fromNative(listOf(0, 300, 700), listOf(1200, 1200, 1200), listOf("Hello", "Hello world", "Hello world again"))
        val prepared = SubtitleProcessing.prepare(source, "en")
        assertEquals(listOf("Hello", "world", "again"), prepared.words.map { it.text })
        assertEquals(listOf(0.0, 300.0, 700.0), prepared.words.map { it.start })
    }
    @Test fun srv3PreservesWordOffsetsAndLineBreaks() {
        val source = TimedTextDecoder.decode("<timedtext><body><p t=\"100\" d=\"1000\"><s t=\"0\" ac=\"0\">Hello</s><s t=\"400\" ac=\"0\">world</s></p><p t=\"1100\" a=\"1\">\n</p></body></timedtext>")!!
        assertEquals(400.0, source[0].segments[1].offsetMs, 0.0)
        assertTrue(source[1].append)
        assertEquals("\n", source[1].segments.single().text)
    }
    @Test fun aiBoundaryProtocolReconstructsSourceAndRejectsGaps() {
        val words = listOf(SubtitleWord("Hello", 0.0, 100.0), SubtitleWord("world", 100.0, 200.0), SubtitleWord("Again", 500.0, 900.0))
        val prefix = AiSubtitleProcessor.prefix("[{\"e\":1,\"o\":\"hallucinated\",\"t\":\"你好\"},{\"e\":2,\"t\":\"再次\"}]", words, "en")
        assertEquals(3, prefix.nextIndex)
        assertEquals("Hello world", prefix.cues.first().text)
        assertTrue(AiSubtitleProcessor.valid(prefix.cues, words))
        val invalid = AiSubtitleProcessor.prefix("[{\"e\":1,\"t\":\"你好\"},{\"e\":0,\"t\":\"错误\"},{\"e\":2,\"t\":\"再次\"}]", words, "en")
        assertEquals(2, invalid.nextIndex)
        assertFalse(AiSubtitleProcessor.valid(invalid.cues, words))
    }
    @Test fun draftsAreVisibleButRemainEligibleForTranslation() {
        val session = CaptionSession()
        session.putDraftTranslation("Hello", "你")
        assertEquals("你", session.translationFor("Hello"))
        assertFalse(session.hasFinalTranslation("Hello"))
        session.putTranslation("Hello", "你好")
        assertTrue(session.hasFinalTranslation("Hello"))
        session.clearTranslations()
        assertNull(session.translationFor("Hello"))
    }
    @Test fun requestKeepsModelAndOmitsGenerationLimits() {
        val body = JsonParser.parseString(OpenAICompatibleTranslator.buildRequestBody(request(), "system", "user", true)).asJsonObject
        assertEquals("keep-this-model", body["model"].asString)
        assertFalse(body.has("temperature"))
        assertFalse(body.has("maxTokens"))
        assertFalse(body.has("max_tokens"))
        assertFalse(body.has("max_completion_tokens"))
    }
    @Test fun failedStreamFallsBackToCompleteResponse() {
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat") { exchange ->
            val body = JsonParser.parseReader(exchange.requestBody.reader()).asJsonObject
            calls++
            val text: String
            if (body["stream"].asBoolean) {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                text = "data: {\"choices\":[{\"delta\":{\"content\":\"[{\\\"id\\\":0,\\\"text\\\":\\\"你好\\\"}]\"}}]}\n\ndata: [DONE]\n\n"
            } else {
                exchange.responseHeaders.add("Content-Type", "application/json")
                text = "{\"choices\":[{\"message\":{\"content\":\"[{\\\"id\\\":0,\\\"text\\\":\\\"你好\\\"},{\\\"id\\\":1,\\\"text\\\":\\\"世界\\\"}]\"}}]}"
            }
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val completed = ArrayList<Pair<Int, String>>()
            val request = request(settings().copy(openAiEndpoint = "http://127.0.0.1:${server.address.port}/chat"))
                .copy(onCompleted = { id, text -> completed += id to text })
            assertEquals(listOf("你好", "世界"), OpenAICompatibleTranslator.translate(request).texts)
            assertEquals(listOf(0 to "你好"), completed)
            assertEquals(2, calls)
        } finally { server.stop(0) }
    }
    @Test fun successfulStreamsPublishCompletedIdsForEveryBatchProtocol() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var calls = 0
        server.createContext("/chat") { exchange ->
            val body = JsonParser.parseReader(exchange.requestBody.reader()).asJsonObject
            assertTrue(body["stream"].asBoolean)
            val protocol = listOf("json", "xml", "line")[calls++]
            val pieces = when (protocol) {
                "xml" -> listOf("<root><t id=\"1\">世", "界</t>", "<t id=\"0\">你好</t></root>")
                "line" -> listOf("1 | 世", "界\n", "0 | 你好\n")
                else -> listOf("[{\"id\":1,\"text\":\"世", "界\"},", "{\"id\":0,\"text\":\"你好\"}]")
            }
            val stream = pieces.joinToString("") { piece ->
                val delta = com.google.gson.JsonObject().apply { addProperty("content", piece) }
                val event = com.google.gson.JsonObject().apply { add("choices", com.google.gson.JsonArray().apply {
                    add(com.google.gson.JsonObject().apply { add("delta", delta) })
                }) }
                "data: $event\n\n"
            } + "data: [DONE]\n\n"
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val bytes = stream.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            for (protocol in listOf("json", "xml", "line")) {
                val completed = ArrayList<Pair<Int, String>>()
                val request = request(settings().copy(openAiEndpoint = "http://127.0.0.1:${server.address.port}/chat", batchPromptMode = protocol))
                    .copy(onCompleted = { id, text -> completed += id to text })
                assertEquals(listOf("你好", "世界"), OpenAICompatibleTranslator.translate(request).texts)
                assertEquals(listOf(1 to "世界", 0 to "你好"), completed)
            }
            assertEquals(3, calls)
        } finally { server.stop(0) }
    }
    @Test fun contextKeepsOnlyCompletePairsAndDoesNotCrossVideos() {
        val bodies = ArrayList<com.google.gson.JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat") { exchange ->
            bodies += JsonParser.parseReader(exchange.requestBody.reader()).asJsonObject
            val content = "[{\"id\":0,\"text\":\"译文\"}]"
            val response = com.google.gson.JsonObject().apply {
                add("choices", com.google.gson.JsonArray().apply { add(com.google.gson.JsonObject().apply {
                    add("message", com.google.gson.JsonObject().apply { addProperty("content", content) })
                }) })
            }.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start(); OpenAICompatibleTranslator.clearHistory()
        try {
            val settings = settings().copy(openAiEndpoint = "http://127.0.0.1:${server.address.port}/chat", useStream = false, useContext = true)
            for (text in listOf("first", "second", "third")) {
                OpenAICompatibleTranslator.translate(request(settings).copy(texts = listOf(text), document = VideoContext(videoId = "video-one")))
            }
            OpenAICompatibleTranslator.translate(request(settings).copy(texts = listOf("other"), document = VideoContext(videoId = "video-two")))
            assertEquals(listOf(2, 4, 4, 2), bodies.map { it["messages"].asJsonArray.size() })
            assertTrue(bodies[2]["messages"].asJsonArray[1].asJsonObject["content"].asString.contains("second"))
            assertFalse(bodies[2]["messages"].asJsonArray[1].asJsonObject["content"].asString.contains("first"))
        } finally { server.stop(0); OpenAICompatibleTranslator.clearHistory() }
    }
    @Test fun playbackSchedulesTimeWindowsAndSeeksWithoutAdvancingPausedCaptions() {
        val cues = listOf(SubtitleCue(0.0, 1000.0, "first"), SubtitleCue(3000.0, 5000.0, "second"),
            SubtitleCue(89000.0, 91000.0, "edge"), SubtitleCue(95000.0, 96000.0, "outside"))
        val scheduler = SubtitleScheduler()
        assertNull(scheduler.cueAt(cues))
        assertEquals("first", scheduler.cueAt(cues, 900.0)?.text)
        assertNull(scheduler.cueAt(cues, 2000.0))
        assertNull(scheduler.cueAt(cues, 96000.0))
        assertEquals(listOf("first", "second", "edge"), scheduler.upcoming(cues, 0.0, 90))
        assertTrue(scheduler.shouldPrefetch(0.0, 30, now = 1))
        assertFalse(scheduler.shouldPrefetch(500.0, 30, now = 500_000_001))
        assertFalse(scheduler.shouldPrefetch(500.0, 30, now = 10_000_000_001))
        assertEquals(500.0, scheduler.position, 0.0)
        assertTrue(scheduler.shouldPrefetch(95000.0, 30, now = 10_100_000_001))
        assertTrue(scheduler.shouldPrefetch(0.0, 30, now = 10_200_000_001))
        assertFalse(scheduler.shouldPrefetch(0.0, 30, now = 20_000_000_001))
        assertTrue(scheduler.shouldPrefetch(0.0, 30, now = 40_200_000_001))
    }
    @Test fun terminologyAndLegacyPromptMatchKissFormatting() {
        assertEquals(mapOf("React" to "", "component" to "组件"), PromptRenderer.glossary("React;component,组件"))
        val request = request(settings().copy(tone = "formal", glossary = "component,组件")).copy(document = VideoContext(title = "Title"))
        val prompt = PromptRenderer.render(KissPrompts.SINGLE_LEGACY_USER, request, "hello")
        assertTrue(prompt.contains("Title: Title\nDescription: \nSummary: \nTone: formal"))
        assertTrue(prompt.contains("component translates to 组件"))
        assertTrue(prompt.contains("to Simplified Chinese"))
        assertTrue(prompt.contains("Reference the following translations"))
    }
    @Test fun cancellationStopsNetworkRequests() {
        val token = TranslationCancellation()
        token.cancel()
        assertTrue(runCatching { OpenAICompatibleTranslator.translate(request().copy(cancellation = token)) }.exceptionOrNull() is java.util.concurrent.CancellationException)
    }
    @Test fun diskCacheSurvivesRecreationWithoutPlaintextKeys() {
        val directory = kotlin.io.path.createTempDirectory("polyglot-cache-test").toFile()
        try {
            TranslationCache(directory).write("secret-api-key:Hello", "你好")
            assertEquals("你好", TranslationCache(directory).read("secret-api-key:Hello"))
            assertFalse(directory.listFiles()!!.any { it.name.contains("secret") || it.readText().contains("secret") })
        } finally { directory.deleteRecursively() }
    }
}
