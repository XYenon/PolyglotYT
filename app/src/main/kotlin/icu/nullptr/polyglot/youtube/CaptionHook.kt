package icu.nullptr.polyglot.youtube

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import icu.nullptr.polyglot.captions.BilingualFormatter
import icu.nullptr.polyglot.captions.CaptionCue
import icu.nullptr.polyglot.captions.CaptionLanguageState
import icu.nullptr.polyglot.captions.CaptionSession
import icu.nullptr.polyglot.module
import icu.nullptr.polyglot.translate.TranslationManager
import icu.nullptr.polyglot.util.hook
import icu.nullptr.polyglot.util.logD
import icu.nullptr.polyglot.util.logI
import icu.nullptr.polyglot.util.logV
import icu.nullptr.polyglot.util.logW
import icu.nullptr.polyglot.util.toConstructor
import icu.nullptr.polyglot.util.toMethod
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object CaptionHook : BaseHook {
    override val name = "CaptionHook"
    override val totalHooks = 6

    private val session = CaptionSession()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captionWindows = WeakHashMap<Any, CaptionWindow>()
    private val nativeCaptionWindowUpdates = WeakHashMap<Any, NativeCaptionWindowUpdate>()
    private val rendererStates = WeakHashMap<Any, RendererState>()
    private val bilingualCaptionEntries = WeakHashMap<Any, List<String>>()
    private val bilingualCaptionOffsets = WeakHashMap<ViewGroup, BilingualCaptionOffsets>()
    private val captionBottomOffsets = WeakHashMap<View, CaptionBottomOffset>()
    private val captionWindowClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val captionWindowFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val captionWindowTextFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val captionAccumulatorEventListFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val captionEventWindowListFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val captionWindowLookupFailures = ConcurrentHashMap.newKeySet<Class<*>>()
    private val captionWindowParseFailures = ConcurrentHashMap.newKeySet<Class<*>>()
    private val captionWindowRewriteFailures = ConcurrentHashMap.newKeySet<Class<*>>()
    private val captionAccumulatorParseFailures = ConcurrentHashMap.newKeySet<Class<*>>()
    private val captionEventParseFailures = ConcurrentHashMap.newKeySet<Class<*>>()
    private val captionPrefetchMisses = ConcurrentHashMap.newKeySet<String>()
    private val captionStateLock = Any()
    @Volatile
    private var captionTimelineIsRolling = false
    private val captionTrackEpoch = AtomicLong(0L)
    private val captionGeneration = AtomicLong(0L)
    private val rendererSequence = AtomicLong(0L)
    private val applyingTranslatedText = ThreadLocal.withInitial { false }
    private val rewritingCaptionWindow = ThreadLocal.withInitial { false }

    override fun install(dexkit: DexKitBridge): Int {
        var installed = 0

        if (installTimelineBuildHook(dexkit)) {
            installed++
        }

        if (installOverlayUpdateHooks(dexkit)) {
            installed++
        }

        if (installCaptionBottomSpacingHooks(dexkit)) {
            installed++
        }

        if (installCaptionEventHook(dexkit)) {
            installed++
        }

        if (installRenderTextHooks(dexkit)) {
            installed++
        }

        if (installCaptionLanguageHooks(dexkit)) {
            installed++
        }

        logI(name, "Installed $installed caption hook(s)")
        return installed
    }

    fun formatCaption(original: CharSequence?): CharSequence {
        val translated = session.translationFor(original?.toString())
        return BilingualFormatter.format(original, translated)
    }

    fun onEnabledChanged(enabled: Boolean) {
        if (!enabled) {
            synchronized(captionStateLock) {
                captionGeneration.incrementAndGet()
            }
        }
        mainHandler.post {
            if (module.config.enabled != enabled) return@post

            if (enabled) {
                replayNativeCaptionWindowUpdates()
                val activeWindows = activeCaptionWindows()
                activeWindows.forEach { (owner, window) ->
                    applyCachedTranslationToCaptionWindow(owner)
                    requestCaptionWindowTranslations(window, source = "enabled")
                }
            } else {
                restoreAllBilingualCaptionOffsets()
                replayNativeCaptionWindowUpdates()
                synchronized(bilingualCaptionEntries) {
                    bilingualCaptionEntries.clear()
                }
            }
        }
    }

    fun onTranslationSettingsChanged() {
        TranslationManager.onSettingsChanged()
        synchronized(captionStateLock) {
            captionGeneration.incrementAndGet()
            session.clearTranslations()
        }
        mainHandler.post {
            if (!module.config.enabled) return@post

            activeCaptionWindows().forEach { (owner, window) ->
                updateCaptionWindowTextLists(owner, window.displayText)
                requestCaptionWindowTranslations(window, source = "settings")
            }
        }
    }

    fun onSubtitleModeChanged() {
        mainHandler.post {
            if (!module.config.enabled) return@post

            val rendererFallbacks = LinkedHashSet<String>()
            activeCaptionWindows().forEach { (owner, window) ->
                val translated = session.translationFor(window.displayText)
                    ?: return@forEach
                val replacement = BilingualFormatter.format(window.displayText, translated)
                if (!updateCaptionWindowTextLists(owner, replacement)) {
                    rendererFallbacks += window.displayText
                }
            }
            rendererFallbacks.forEach { text ->
                refreshVisibleRenderers(text, source = "subtitle mode")
            }
        }
    }

    private fun activeCaptionWindows(): List<Pair<Any, CaptionWindow>> =
        synchronized(captionWindows) {
            captionWindows.entries.map { (owner, window) -> owner to window }
        }

    private fun installTimelineBuildHook(dexkit: DexKitBridge): Boolean {
        val method = dexkit.findCaptionTimelineBuildMethod()
            ?.toMethod() ?: run {
            logW(name, "Caption timeline builder not found")
            return false
        }

        hook(method) { chain ->
            val expectedTrackEpoch = captionTrackEpoch.get()
            val result = chain.proceed()
            if (module.config.enabled && expectedTrackEpoch == captionTrackEpoch.get()) {
                val newCues = observeTimeline(result, expectedTrackEpoch)
                logObservedCueCount(newCues.size, source = "timeline")
            }
            result
        }

        logD(name, "Hooked caption timeline builder: ${method.shortName()}")
        return true
    }

    private fun installRenderTextHooks(dexkit: DexKitBridge): Boolean {
        val methods = dexkit.findCaptionRenderTextMethods()
            .map { it.toMethod() }
            .filter { method ->
                View::class.java.isAssignableFrom(method.declaringClass) &&
                        method.declaringClass.hasInstanceFieldAssignableTo(Editable::class.java)
            }
            .distinctBy { it.stableId() }

        var installed = 0
        for (method in methods) {
            hook(method) { chain ->
                if (applyingTranslatedText.get() == true) {
                    return@hook chain.proceed()
                }

                if (!module.config.enabled) {
                    val parent = (chain.thisObject as? View)?.parent
                    restoreBilingualCaptionOffsets(parent)
                    return@hook chain.proceed()
                }

                val thisObject = chain.thisObject ?: return@hook chain.proceed()
                if (!thisObject.javaClass.hasInstanceFieldAssignableTo(Editable::class.java)) {
                    return@hook chain.proceed()
                }

                val original = chain.getArg(0) as? CharSequence
                    ?: return@hook chain.proceed()
                val normalizedOriginal = CaptionCue.normalize(original.toString())
                val parent = (thisObject as View).parent
                if (parent != null && rewritingCaptionWindow.get() != true) {
                    val windowArg = parent.captionWindowFieldValue()
                    val newCues = observeCaptionWindow(parent, windowArg)
                    logObservedCueCount(newCues.size, source = "window")
                }
                val captionWindow = synchronized(captionWindows) {
                    captionWindows[parent]
                }
                if (parent != null) {
                    rememberRendererState(thisObject, parent, method, normalizedOriginal)
                    alignBilingualCaptionEntriesAfterLayout(
                        owner = parent,
                        normalizedText = normalizedOriginal,
                    )
                }

                if (parent != null && session.observeRenderedText(original)) {
                    val length = normalizedOriginal.length
                    logV(name, "Observed rendered caption text, length=$length")
                }

                if (captionWindow != null) {
                    val translated = session.translationFor(captionWindow.displayText)
                    if (translated != null || captionWindow.isRolling) {
                        val formatted = BilingualFormatter.format(
                            captionWindow.displayText,
                            translated,
                        )
                        val renderedExpectedEntry = formatted.captionLines().any { entry ->
                            CaptionCue.normalize(entry.toString()) == normalizedOriginal
                        }
                        if (renderedExpectedEntry &&
                            parent != null && parent.hasCaptionTextLines(formatted)
                        ) {
                            return@hook chain.proceed()
                        }
                        val replacement = replacementForCaptionWindow(
                            window = captionWindow,
                            renderedFragment = normalizedOriginal,
                            translated = translated,
                        )
                        return@hook chain.proceed(arrayOf(replacement))
                    }
                    return@hook chain.proceed()
                }

                val formatted = formatCaption(original)
                if (formatted !== original && formatted.toString() != original.toString()) {
                    return@hook chain.proceed(arrayOf(formatted))
                }

                chain.proceed()
            }

            logD(name, "Hooked caption renderer: ${method.shortName()}")
            installed++
        }

        if (installed == 0) {
            logW(name, "Caption renderer not found")
        }
        return installed > 0
    }

    private fun installCaptionLanguageHooks(dexkit: DexKitBridge): Boolean {
        val trackClassName = dexkit.findCaptionTrackClassName() ?: run {
            logW(name, "Caption track class not found")
            return false
        }

        val methods = dexkit.findCaptionTrackStateMethods(trackClassName)
            .map { it.toMethod() }
            .distinctBy { it.stableId() }

        if (methods.isEmpty()) {
            logW(name, "Caption language methods not found")
            return false
        }

        for (method in methods) {
            hook(method) { chain ->
                observeCaptionTrack(chain.getArg(0), "${method.shortName()} arg0")
                val result = chain.proceed()
                result
            }
        }

        logD(name, "Hooked caption language trackers: ${methods.size}")
        return true
    }

    private fun installOverlayUpdateHooks(dexkit: DexKitBridge): Boolean {
        val captionWindowClassNames = dexkit.findCaptionWindowClassNames()
        val methods = (
            dexkit.findCaptionOverlayUpdateMethods(captionWindowClassNames) +
                dexkit.findCaptionWindowUpdateMethods(captionWindowClassNames)
        ).map { it.toMethod() }
            .filter { method ->
                View::class.java.isAssignableFrom(method.declaringClass) &&
                        (method.parameterTypes.singleOrNull() != List::class.java ||
                                method.declaringClass.hasInstanceFieldAssignableTo(SparseArray::class.java))
            }
            .distinctBy { it.stableId() }

        methods.mapNotNullTo(captionWindowClasses) { method ->
            method.parameterTypes.singleOrNull()?.takeUnless { it == List::class.java }
        }

        var installed = 0
        for (method in methods) {
            hook(method) { chain ->
                val arg = chain.getArg(0)
                if (arg != null && arg !is List<*>) {
                    rememberNativeCaptionWindowUpdate(chain.thisObject, arg, method)
                }
                if (module.config.enabled) {
                    val source = if (arg is List<*>) "overlay" else "window"
                    val newCues = if (arg is List<*>) {
                        observeCaptionWindows(arg)
                    } else {
                        observeCaptionWindow(chain.thisObject, arg)
                    }
                    logObservedCueCount(newCues.size, source)
                }
                val restoredFields = if (module.config.enabled && arg != null && arg !is List<*>) {
                    replaceCaptionWindowTextFields(chain.thisObject, arg)
                } else {
                    emptyList()
                }
                val rewriting = restoredFields.isNotEmpty()
                val wasRewriting = rewritingCaptionWindow.get()
                val result = try {
                    if (rewriting) rewritingCaptionWindow.set(true)
                    chain.proceed()
                } finally {
                    if (rewriting) rewritingCaptionWindow.set(wasRewriting)
                    if (arg != null) {
                        restoredFields.forEach { value -> value.restore(arg) }
                    }
                }
                if (module.config.enabled && arg !is List<*> && restoredFields.isEmpty()) {
                    applyCachedTranslationToCaptionWindow(chain.thisObject)
                }
                result
            }

            val target = if (method.parameterTypes.singleOrNull() == List::class.java) {
                "overlay"
            } else {
                "window"
            }
            logD(name, "Hooked caption $target update: ${method.shortName()}")
            installed++
        }

        if (installed == 0) {
            logW(name, "Caption overlay update methods not found")
        }
        return installed > 0
    }

    private fun installCaptionBottomSpacingHooks(dexkit: DexKitBridge): Boolean {
        val captionWindowClassNames = dexkit.findCaptionWindowClassNames()
        val methods = dexkit.findCaptionOverlayUpdateMethods(captionWindowClassNames)
            .mapNotNull { candidate ->
                var clazz: Class<*>? = candidate.toMethod().declaringClass
                while (clazz != null && clazz != ViewGroup::class.java &&
                    ViewGroup::class.java.isAssignableFrom(clazz)
                ) {
                    val layoutMethod = clazz.declaredMethods.firstOrNull { method ->
                        method.name == "onLayout" && method.parameterTypes.contentEquals(
                            arrayOf(
                                Boolean::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                            ),
                        )
                    }
                    if (layoutMethod != null) return@mapNotNull layoutMethod
                    clazz = clazz.superclass
                }
                null
            }
            .onEach { it.isAccessible = true }
            .distinctBy { it.stableId() }

        for (method in methods) {
            hook(method) { chain ->
                val overlay = chain.thisObject as? ViewGroup ?: return@hook chain.proceed()
                // Start each native layout from its own coordinates, without our last offset.
                for (index in 0 until overlay.childCount) {
                    restoreCaptionBottomOffset(overlay.getChildAt(index))
                }
                val result = chain.proceed()
                for (index in 0 until overlay.childCount) {
                    refreshCaptionBottomSpacing(overlay.getChildAt(index))
                }
                result
            }
            logD(name, "Hooked caption bottom spacing: ${method.shortName()}")
        }
        if (methods.isEmpty()) {
            logW(name, "Caption overlay layout methods not found")
        }
        return methods.isNotEmpty()
    }

    private fun restoreCaptionBottomOffset(window: View) {
        val state = captionBottomOffsets[window] ?: return
        window.offsetTopAndBottom(-state.offset)
        state.offset = 0
    }

    private fun refreshCaptionBottomSpacing(window: View) {
        val overlay = window.parent as? ViewGroup ?: return
        val entries = window as? ViewGroup ?: return
        val contentBottom = (0 until entries.childCount)
            .map { entries.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE && it.height > 0 }
            .maxOfOrNull { it.bottom + it.translationY }
        if (contentBottom == null) {
            restoreCaptionBottomOffset(window)
            return
        }
        val state = captionBottomOffsets.getOrPut(window) {
            window.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    mainHandler.post { refreshCaptionBottomSpacing(view) }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    restoreCaptionBottomOffset(view)
                }
            })
            CaptionBottomOffset()
        }
        val nativeBottom = window.top - state.offset + window.translationY + contentBottom
        val gap = (overlay.height - nativeBottom).coerceAtLeast(0f)
        val offset = (gap * CAPTION_BOTTOM_GAP_REDUCTION).toInt()
        window.offsetTopAndBottom(offset - state.offset)
        state.offset = offset
    }

    private fun installCaptionEventHook(dexkit: DexKitBridge): Boolean {
        val webVttDecoder = dexkit.findWebVttDecoderMethod() ?: run {
            logW(name, "Caption event decoder not found")
            return false
        }
        val constructor = dexkit.findCaptionEventWrapperConstructor(webVttDecoder)
            ?.toConstructor() ?: run {
            logW(name, "Caption event wrapper not found")
            return false
        }

        hook(constructor) { chain ->
            val expectedTrackEpoch = captionTrackEpoch.get()
            val result = chain.proceed()
            if (module.config.enabled && expectedTrackEpoch == captionTrackEpoch.get()) {
                observeCaptionEventAccumulator(chain.getArg(0), expectedTrackEpoch)
            }
            result
        }

        logD(name, "Hooked caption event wrapper: ${constructor.shortName()}")
        return true
    }

    private fun observeTimeline(result: Any?, expectedTrackEpoch: Long): List<CaptionCue> {
        if (expectedTrackEpoch != captionTrackEpoch.get()) return emptyList()
        if (result == null) {
            clearCaptionTimeline(expectedTrackEpoch)
            return emptyList()
        }

        val timelineTextUnwrapper = result.javaClass.declaredMethods.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.contentEquals(arrayOf(CharSequence::class.java)) &&
                    CharSequence::class.java.isAssignableFrom(method.returnType)
        }?.apply { isAccessible = true }
        val useFullTextPrefixGuard = result.javaClass.declaredMethods.any { method ->
            Modifier.isStatic(method.modifiers) &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.contentEquals(
                        arrayOf(CharSequence::class.java, CharSequence::class.java),
                    )
        }
        val lists = result.instanceFieldValues()
            .filterIsInstance<List<*>>()
            .filter { it.isNotEmpty() }
        val texts = lists.firstOrNull { list -> list.any { it is CharSequence } } ?: run {
            clearCaptionTimeline(expectedTrackEpoch)
            return emptyList()
        }
        val timeLists = lists.filter { list -> list.all { it is Number } }
        if (timeLists.size < 2) {
            clearCaptionTimeline(expectedTrackEpoch)
            return emptyList()
        }

        val (starts, ends) = orderedTimelineTimeLists(timeLists[0], timeLists[1])

        val size = minOf(starts.size, ends.size, texts.size)
        if (size == 0) {
            clearCaptionTimeline(expectedTrackEpoch)
            return emptyList()
        }

        val cues = ArrayList<CaptionCue>(size)
        for (index in 0 until size) {
            val text = texts[index] as? CharSequence ?: continue
            val unwrappedText = timelineTextUnwrapper
                ?.let { method -> runCatching { method.invoke(null, text) as? CharSequence }.getOrNull() }
                ?: text
            val normalized = CaptionCue.normalize(unwrappedText.toString())
            if (normalized.isEmpty()) continue

            cues.add(
                CaptionCue(
                    videoId = "",
                    startMs = (starts[index] as? Number)?.toLong() ?: CaptionCue.UNKNOWN_TIME_MS,
                    endMs = (ends[index] as? Number)?.toLong() ?: CaptionCue.UNKNOWN_TIME_MS,
                    text = normalized,
                ),
            )
        }
        observeTimelineWindowBatch(
            starts = starts,
            ends = ends,
            texts = texts,
            size = size,
            textUnwrapper = timelineTextUnwrapper,
            useFullTextPrefixGuard = useFullTextPrefixGuard,
            expectedTrackEpoch = expectedTrackEpoch,
        )
        return synchronized(captionStateLock) {
            if (expectedTrackEpoch != captionTrackEpoch.get()) {
                emptyList()
            } else {
                session.observeNewCues(cues)
            }
        }
    }

    private fun observeTimelineWindowBatch(
        starts: List<*>,
        ends: List<*>,
        texts: List<*>,
        size: Int,
        textUnwrapper: Method?,
        useFullTextPrefixGuard: Boolean,
        expectedTrackEpoch: Long,
    ) {
        val lines = ArrayList<TimelineLine>(size)
        for (index in 0 until size) {
            val startMs = (starts[index] as? Number)?.toLong() ?: continue
            val endMs = (ends[index] as? Number)?.toLong() ?: continue
            val text = texts[index] as? CharSequence ?: continue
            val unwrappedText = textUnwrapper
                ?.let { method -> runCatching { method.invoke(null, text) as? CharSequence }.getOrNull() }
                ?: text
            lines += TimelineLine(startMs, endMs, unwrappedText.toString())
        }
        val distinctEndCount = lines.mapTo(HashSet()) { it.endMs }.size
        val isRolling = lines.isNotEmpty() &&
                distinctEndCount * ROLLING_TIMELINE_END_RATIO < lines.size
        val rawWindows = timelineWindows(lines, isRolling, useFullTextPrefixGuard)
        val windows = if (isRolling) {
            groupedRollingTimelineWindows(rawWindows)
        } else {
            rawWindows
        }
        val (stored, generation) = synchronized(captionStateLock) {
            if (expectedTrackEpoch != captionTrackEpoch.get()) return
            captionTimelineIsRolling = isRolling
            val count = session.replaceCaptionTimeline(
                windows.map { window -> window.startMs to window.text },
            )
            count to if (count >= 0) captionGeneration.incrementAndGet() else captionGeneration.get()
        }
        if (stored >= 0) {
            captionPrefetchMisses.clear()
            windows.firstOrNull()?.let { firstWindow ->
                queueCaptionTranslations(
                    texts = windows.map { window -> window.text },
                    source = "timeline",
                    currentText = firstWindow.text,
                    expectedGeneration = generation,
                )
            }
            logV(
                name,
                "Observed caption timeline window batch, cues=${lines.size}, windows=$stored, " +
                    "segments=${windows.mapTo(HashSet()) { window -> window.text }.size}, " +
                    "rolling=$isRolling",
            )
        }
    }

    private fun groupedRollingTimelineWindows(windows: List<TimelineWindow>): List<TimelineWindow> {
        if (windows.size < 2) return windows

        val grouped = ArrayList<TimelineWindow>(windows.size)
        var runStart = 0
        for (index in 1..windows.size) {
            if (index < windows.size &&
                windows[index].startMs - windows[index - 1].startMs <= MAX_ROLLING_SEGMENT_GAP_MS
            ) {
                continue
            }

            grouped += groupedRollingTimelineRun(windows.subList(runStart, index))
            runStart = index
        }
        return grouped
    }

    private fun groupedRollingTimelineRun(windows: List<TimelineWindow>): List<TimelineWindow> {
        if (windows.size < 2) return windows

        val transcript = StringBuilder()
        val windowTextStarts = IntArray(windows.size)
        windows.forEachIndexed { index, window ->
            if (transcript.isNotEmpty()) transcript.append(' ')
            windowTextStarts[index] = transcript.length
            transcript.append(window.text)
        }
        val text = transcript.toString()
        val segments = rollingTextSegments(text)
        if (segments.isEmpty()) return windows

        val segmentByWindow = IntArray(windows.size)
        var segmentIndex = 0
        windowTextStarts.forEachIndexed { windowIndex, textStart ->
            while (segmentIndex < segments.lastIndex &&
                textStart >= segments[segmentIndex].endIndex
            ) {
                segmentIndex++
            }
            segmentByWindow[windowIndex] = segmentIndex
        }

        val occupiedSegments = segmentByWindow.distinct()
        val displayTextBySegment = HashMap<Int, String>(occupiedSegments.size)
        occupiedSegments.forEachIndexed { index, occupiedSegment ->
            val startIndex = segments[occupiedSegment].startIndex
            val endIndex = occupiedSegments.getOrNull(index + 1)
                ?.let { nextSegment -> segments[nextSegment].startIndex }
                ?: text.length
            displayTextBySegment[occupiedSegment] =
                CaptionCue.normalize(text.substring(startIndex, endIndex))
        }

        return windows.mapIndexed { index, window ->
            TimelineWindow(
                startMs = window.startMs,
                text = displayTextBySegment.getValue(segmentByWindow[index]),
            )
        }
    }

    private fun rollingTextSegments(text: String): List<TextRange> {
        val parts = ArrayList<TextRange>()
        var startIndex = 0
        sentenceBoundary.findAll(text).forEach { boundary ->
            appendRollingTextParts(text, startIndex, boundary.range.last + 1, parts)
            startIndex = boundary.range.last + 1
        }
        appendRollingTextParts(text, startIndex, text.length, parts)

        val segments = ArrayList<TextRange>(parts.size)
        for (part in parts) {
            val previous = segments.lastOrNull()
            if (previous != null &&
                part.endIndex - previous.startIndex <= MAX_ROLLING_SEGMENT_LENGTH
            ) {
                segments[segments.lastIndex] = TextRange(previous.startIndex, part.endIndex)
            } else {
                segments += part
            }
        }
        return segments
    }

    private fun appendRollingTextParts(
        text: String,
        rangeStart: Int,
        rangeEnd: Int,
        parts: MutableList<TextRange>,
    ) {
        var startIndex = rangeStart
        while (startIndex < rangeEnd && text[startIndex].isWhitespace()) startIndex++
        var endIndex = rangeEnd
        while (endIndex > startIndex && text[endIndex - 1].isWhitespace()) endIndex--

        while (endIndex - startIndex > MAX_ROLLING_SEGMENT_LENGTH) {
            val remainingLength = endIndex - startIndex
            val remainingParts =
                (remainingLength + MAX_ROLLING_SEGMENT_LENGTH - 1) /
                        MAX_ROLLING_SEGMENT_LENGTH
            val targetEnd = startIndex +
                    (remainingLength + remainingParts - 1) / remainingParts
            val minimumEnd = maxOf(
                endIndex - (remainingParts - 1) * MAX_ROLLING_SEGMENT_LENGTH,
                startIndex + MIN_ROLLING_SEGMENT_LENGTH,
            )
            val maximumEnd = minOf(startIndex + MAX_ROLLING_SEGMENT_LENGTH, endIndex)
            val clauseEnd = clauseBoundary.findAll(text, startIndex)
                .map { boundary -> boundary.range.last + 1 }
                .takeWhile { boundaryEnd -> boundaryEnd <= maximumEnd }
                .filter { boundaryEnd -> boundaryEnd >= minimumEnd }
                .minByOrNull { boundaryEnd ->
                    if (boundaryEnd >= targetEnd) {
                        boundaryEnd - targetEnd
                    } else {
                        targetEnd - boundaryEnd
                    }
                }
            val splitIndex = clauseEnd
                ?: closestWhitespace(text, minimumEnd, maximumEnd, targetEnd)
                ?: maximumEnd
            var trimmedSplitIndex = splitIndex
            while (trimmedSplitIndex > startIndex && text[trimmedSplitIndex - 1].isWhitespace()) {
                trimmedSplitIndex--
            }
            parts += TextRange(startIndex, trimmedSplitIndex)
            startIndex = splitIndex
            while (startIndex < endIndex && text[startIndex].isWhitespace()) startIndex++
        }
        if (startIndex < endIndex) {
            parts += TextRange(startIndex, endIndex)
        }
    }

    private fun closestWhitespace(
        text: String,
        minimumEnd: Int,
        maximumEnd: Int,
        targetEnd: Int,
    ): Int? {
        var closest: Int? = null
        var closestDistance = Int.MAX_VALUE
        for (index in minimumEnd..maximumEnd) {
            if (index >= text.length || !text[index].isWhitespace()) continue
            val distance = if (index >= targetEnd) index - targetEnd else targetEnd - index
            if (distance < closestDistance) {
                closest = index
                closestDistance = distance
            }
        }
        return closest
    }

    private fun clearCaptionTimeline(expectedTrackEpoch: Long) {
        val cleared = synchronized(captionStateLock) {
            if (expectedTrackEpoch != captionTrackEpoch.get()) return
            captionTimelineIsRolling = false
            val count = session.replaceCaptionTimeline(emptyList())
            if (count >= 0) captionGeneration.incrementAndGet()
            count >= 0
        }
        if (cleared) {
            captionPrefetchMisses.clear()
            logV(name, "Cleared caption timeline")
        }
    }

    private fun timelineWindows(
        lines: List<TimelineLine>,
        isRolling: Boolean,
        useFullTextPrefixGuard: Boolean,
    ): List<TimelineWindow> {
        val timelineTimes = lines
            .flatMapTo(HashSet(lines.size * 2)) { line -> listOf(line.startMs, line.endMs) }
            .sorted()
        if (timelineTimes.size < 2) return emptyList()

        val windows = ArrayList<TimelineWindow>()
        for (timeMs in timelineTimes.dropLast(1)) {
            val fullText = timelineWindowTextAt(
                lines,
                timeMs,
                isRolling,
                useFullTextPrefixGuard,
            )
            val normalized = CaptionCue.normalize(captionDisplayText(fullText, isRolling))
            if (normalized.isNotEmpty() && windows.lastOrNull()?.text != normalized) {
                windows += TimelineWindow(timeMs, normalized)
            }
        }
        return windows
    }

    private fun timelineWindowTextAt(
        lines: List<TimelineLine>,
        timeMs: Long,
        isRolling: Boolean,
        useFullTextPrefixGuard: Boolean,
    ): String {
        var index = findActiveTimelineLine(lines, timeMs, isRolling)
        if (index < 0) return ""

        if (!isRolling) {
            val currentText = lines[index].text
            val endMs = lines[index].endMs
            while (index + 1 < lines.size && lines[index + 1].endMs == endMs) {
                index++
            }
            val fullText = lines[index].text
            return if (useFullTextPrefixGuard && !fullText.startsWith(currentText)) {
                currentText
            } else {
                fullText
            }
        }

        val currentText = StringBuilder()
        val fullText = StringBuilder()
        while (index + 1 < lines.size &&
            lines[index + 1].startMs <= timeMs &&
            lines[index + 1].endMs > timeMs
        ) {
            val text = lines[index].text
            if (text.endsWith('\n')) {
                currentText.append(text)
                fullText.append(text)
            }
            index++
        }

        currentText.append(lines[index].text)
        val endMs = lines[index].endMs
        while (index < lines.size && lines[index].endMs == endMs) {
            val text = lines[index].text
            if (text.isNotEmpty() && text.endsWith('\n')) {
                fullText.append(text)
                break
            }

            index++
            if (index >= lines.size || lines[index].endMs > endMs) {
                fullText.append(text)
                break
            }
        }
        return if (useFullTextPrefixGuard && !fullText.startsWith(currentText)) {
            currentText.toString()
        } else {
            fullText.toString()
        }
    }

    private fun findActiveTimelineLine(
        lines: List<TimelineLine>,
        timeMs: Long,
        findEarliest: Boolean,
    ): Int {
        var low = 0
        var high = lines.lastIndex
        var index = -1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val middleStartMs = lines[middle].startMs
            when {
                middleStartMs < timeMs -> low = middle + 1
                middleStartMs > timeMs -> high = middle - 1
                else -> {
                    index = middle
                    break
                }
            }
        }

        if (index < 0) {
            index = low - 1
            if (index < 0 || timeMs >= lines[index].endMs) return -1
        }

        val step = if (findEarliest) -1 else 1
        while (true) {
            val candidate = index + step
            if (candidate !in lines.indices) break
            val line = lines[candidate]
            if (line.startMs > timeMs || line.endMs <= timeMs) break
            index = candidate
        }
        return index
    }

    private fun observeCaptionWindows(arg: Any?): List<CaptionCue> {
        val items = if (arg is List<*>) arg else listOf(arg)
        val windows = items.mapNotNull { item ->
            item?.toCaptionWindow()
        }
        for (window in windows) {
            requestCaptionWindowTranslations(window, source = "overlay")
        }
        return session.observeNewCues(windows.map { it.cue })
    }

    private fun observeCaptionWindow(owner: Any?, arg: Any?): List<CaptionCue> {
        if (owner == null || arg == null) return emptyList()
        val window = arg.toCaptionWindow() ?: return emptyList()
        val previous = synchronized(captionWindows) {
            captionWindows.put(owner, window)
        }

        if (previous?.displayText == window.displayText &&
            previous.isRolling == window.isRolling
        ) {
            return emptyList()
        }
        restoreBilingualCaptionOffsets(owner)
        synchronized(bilingualCaptionEntries) {
            bilingualCaptionEntries.remove(owner)
        }

        logV(
            name,
            "Observed caption window, rolling=${window.isRolling}, " +
                "id=${window.cue.windowId}, time=${window.cue.startMs}, " +
                "currentLength=${window.currentText.length}, fullLength=${window.fullText.length}, " +
                "sourceLines=${window.fullText.count { character -> character == '\n' } + 1}, " +
                "segmentLength=${window.displayText.length}, " +
                "timelineGrouped=${window.isTimelineGrouped}, " +
                "segmentHash=${window.displayText.logHash()}, " +
                "owner=${owner.javaClass.simpleName}@${System.identityHashCode(owner)}",
        )
        requestCaptionWindowTranslations(window, source = "window")
        session.observeCue(window.cue)
        return if (session.translationFor(window.displayText) == null) {
            listOf(window.cue)
        } else {
            emptyList()
        }
    }

    private fun observeCaptionEventAccumulator(arg: Any?, expectedTrackEpoch: Long) {
        if (expectedTrackEpoch != captionTrackEpoch.get()) return
        if (arg == null) return
        val listFields = captionAccumulatorEventListFields.getOrPut(arg.javaClass) {
            arg.instanceFields().filter { field ->
                List::class.java.isAssignableFrom(field.type)
            }
        }
        val events = listFields.firstNotNullOfOrNull { field ->
            (field.getValueOrNull(arg) as? List<*>)?.takeIf { it.isNotEmpty() }
        } ?: run {
            if (captionAccumulatorParseFailures.add(arg.javaClass)) {
                logV(
                    name,
                    "Caption parser output unavailable from ${arg.javaClass.name}, " +
                        "candidateFields=${listFields.size}",
                )
            }
            return
        }
        observeCaptionEventBatch(events, expectedTrackEpoch)
    }

    private fun observeCaptionEventBatch(arg: Any?, expectedTrackEpoch: Long) {
        if (expectedTrackEpoch != captionTrackEpoch.get()) return
        val events = arg as? List<*> ?: return
        if (events.isEmpty()) return

        val texts = ArrayList<String>()
        for (event in events) {
            if (event == null) continue
            val listFields = captionEventWindowListFields.getOrPut(event.javaClass) {
                event.instanceFields().filter { field ->
                    List::class.java.isAssignableFrom(field.type)
                }
            }
            val windows = listFields.firstNotNullOfOrNull { field ->
                val value = field.getValueOrNull(event) as? List<*> ?: return@firstNotNullOfOrNull null
                value.takeIf { list ->
                    list.any { item ->
                        item != null && captionWindowClasses.any { it.isInstance(item) }
                    }
                }
            } ?: continue

            for (item in windows) {
                val window = item?.toCaptionWindow() ?: continue
                texts += window.displayText
            }
        }

        val stored = synchronized(captionStateLock) {
            if (expectedTrackEpoch != captionTrackEpoch.get()) return
            session.observeCaptionEventBatch(texts)
        }
        if (stored > 0) {
            logV(
                name,
                "Observed caption event batch, events=${events.size}, windows=$stored",
            )
        } else {
            events.firstNotNullOfOrNull { it?.javaClass }?.let { eventClass ->
                if (captionEventParseFailures.add(eventClass)) {
                    logV(
                        name,
                        "Caption event windows unavailable from ${eventClass.name}, " +
                            "events=${events.size}",
                    )
                }
            }
        }
    }

    private fun Any.toCaptionWindow(): CaptionWindow? {
        if (captionWindowClasses.none { it.isInstance(this) }) return null

        val fields = instanceFields()
        val values = fields.map { field -> field.getValueOrNull(this) }
        val texts = values
            .filterIsInstance<CharSequence>()
            .map { captionDisplayText(it.toString(), isRolling = false) }
            .filter { CaptionCue.normalize(it).isNotEmpty() }
            .distinct()
        val fullText = texts.maxByOrNull { CaptionCue.normalize(it).length } ?: run {
            if (captionWindowParseFailures.add(javaClass)) {
                logV(
                    name,
                    "Caption window text unavailable from ${javaClass.name}, " +
                        "fields=${fields.size}, readableValues=${values.count { it != null }}",
                )
            }
            return null
        }
        val normalizedFullText = CaptionCue.normalize(fullText)
        val currentText = texts
            .filter { it != fullText }
            .firstOrNull { normalizedFullText.startsWith(CaptionCue.normalize(it)) }
            ?: fullText
        val startMs = values.filterIsInstance<Long>().firstOrNull()
            ?: CaptionCue.UNKNOWN_TIME_MS
        val detectedRolling = CaptionCue.normalize(currentText) != normalizedFullText
        val timelineDisplayText = if (detectedRolling || captionTimelineIsRolling) {
            session.timelineCaptionTextAt(startMs)
        } else {
            null
        }
        val isRolling = detectedRolling ||
                (captionTimelineIsRolling && timelineDisplayText != null)
        val nativeDisplayText = captionDisplayText(fullText, isRolling)
        val displayText = timelineDisplayText ?: nativeDisplayText

        return CaptionWindow(
            cue = CaptionCue(
                videoId = "",
                startMs = startMs,
                endMs = CaptionCue.UNKNOWN_TIME_MS,
                text = displayText,
                windowId = values.filterIsInstance<Int>().firstOrNull()
                    ?: CaptionCue.UNKNOWN_WINDOW_ID,
            ),
            currentText = currentText,
            fullText = fullText,
            displayText = displayText,
            isRolling = isRolling,
            isTimelineGrouped = timelineDisplayText != null && timelineDisplayText != nativeDisplayText,
        )
    }

    private fun Any.captionWindowFieldValue(): Any? {
        val fields = captionWindowFields.getOrPut(javaClass) {
            instanceFields().filter { field ->
                captionWindowClasses.any { windowClass ->
                    windowClass.isAssignableFrom(field.type)
                }
            }
        }
        val value = fields.firstNotNullOfOrNull { field ->
            field.getValueOrNull(this)?.takeIf { candidate ->
                captionWindowClasses.any { it.isInstance(candidate) }
            }
        } ?: instanceFields().firstNotNullOfOrNull { field ->
            field.getValueOrNull(this)?.takeIf { candidate ->
                captionWindowClasses.any { it.isInstance(candidate) }
            }
        }
        if (value == null && captionWindowLookupFailures.add(javaClass)) {
            logV(
                name,
                "Caption window unavailable from renderer parent ${javaClass.name}, " +
                    "candidateFields=${fields.size}",
            )
        }
        return value
    }

    private fun orderedTimelineTimeLists(first: List<*>, second: List<*>): Pair<List<*>, List<*>> {
        val comparableSize = minOf(first.size, second.size)
        var firstBeforeSecond = 0
        var secondBeforeFirst = 0
        for (index in 0 until comparableSize) {
            val a = (first[index] as? Number)?.toLong() ?: continue
            val b = (second[index] as? Number)?.toLong() ?: continue
            if (a <= b) firstBeforeSecond++ else secondBeforeFirst++
        }
        return if (firstBeforeSecond >= secondBeforeFirst) {
            first to second
        } else {
            second to first
        }
    }

    private fun Any.instanceFieldValues(): List<Any?> {
        return instanceFields().map { field -> field.getValueOrNull(this) }
    }

    private fun Any.instanceFields(): List<Field> = javaClass.instanceFields()

    private fun Class<*>.instanceFields(): List<Field> {
        val fields = ArrayList<Field>()
        var clazz: Class<*>? = this
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                fields += field
            }
            clazz = clazz.superclass
        }
        return fields
    }

    private fun Field.getValueOrNull(instance: Any): Any? =
        runCatching {
            isAccessible = true
            get(instance)
        }.getOrNull()

    private fun Class<*>.hasInstanceFieldAssignableTo(type: Class<*>): Boolean {
        var clazz: Class<*>? = this
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                if (!Modifier.isStatic(field.modifiers) && type.isAssignableFrom(field.type)) {
                    return true
                }
            }
            clazz = clazz.superclass
        }
        return false
    }

    private fun logObservedCueCount(newCueCount: Int, source: String) {
        if (newCueCount <= 0) return

        val total = session.observedCueCount()
        if (total <= 5 || total % 25 == 0) {
            logV(name, "Observed $newCueCount new caption cue(s) from $source, total=$total")
        }
    }

    private fun requestCaptionWindowTranslations(window: CaptionWindow, source: String) {
        val upcoming = session.upcomingCaptionTexts(
            currentText = window.displayText,
            currentStartMs = window.cue.startMs,
            limit = CAPTION_PREFETCH_WINDOW_COUNT,
        )
        if (window.isRolling && upcoming.isEmpty() && captionPrefetchMisses.add(window.fullText)) {
            logV(
                name,
                "Caption timeline prefetch match unavailable, " +
                    "windowLength=${window.fullText.length}, segmentLength=${window.displayText.length}",
            )
        }

        val candidates = LinkedHashSet<String>(CAPTION_PREFETCH_WINDOW_COUNT).apply {
            add(window.displayText)
            addAll(upcoming)
        }
        queueCaptionTranslations(candidates, source, window.displayText)
    }

    private fun queueCaptionTranslations(
        texts: Iterable<String>,
        source: String,
        currentText: String,
        expectedGeneration: Long? = null,
    ) {
        val candidates = LinkedHashSet<String>(CAPTION_PREFETCH_WINDOW_COUNT)
        for (text in texts) {
            candidates += text
            if (candidates.size >= CAPTION_PREFETCH_WINDOW_COUNT) break
        }
        if (candidates.isEmpty()) return

        val requestSource = if (candidates.size > 1) "prefetch" else source
        var scheduled = 0
        var batchCount = 0
        for (batch in candidates.chunked(CAPTION_TRANSLATION_BATCH_SIZE)) {
            val batchScheduled = requestTranslations(batch, requestSource, expectedGeneration)
            if (batchScheduled > 0) {
                scheduled += batchScheduled
                batchCount++
            }
        }
        if (scheduled > 0) {
            logV(
                name,
                "Queued $scheduled caption translation(s) in $batchCount batch(es) from $source, " +
                    "currentHash=${currentText.logHash()}",
            )
        }
    }

    private fun requestTranslations(
        texts: Iterable<String>,
        source: String,
        expectedGeneration: Long? = null,
    ): Int {
        val pending = texts.filter { text -> session.translationFor(text) == null }
        if (pending.isEmpty()) return 0

        val sourceLanguage = CaptionLanguageState.currentSourceLanguage()
        val provider = module.config.provider
        val targetLanguage = module.config.targetLanguage
        val trackEpoch = captionTrackEpoch.get()
        val generation = synchronized(captionStateLock) {
            captionGeneration.get().also { currentGeneration ->
                if (expectedGeneration != null && expectedGeneration != currentGeneration) return 0
            }
        }
        return TranslationManager.translateBatchAsync(
            texts = pending,
            context = "YouTube subtitle $source",
            sourceLanguage = sourceLanguage,
        ) { original, translated ->
            mainHandler.post {
                val accepted = synchronized(captionStateLock) {
                    if (!module.config.enabled ||
                        trackEpoch != captionTrackEpoch.get() ||
                        generation != captionGeneration.get() ||
                        provider != module.config.provider ||
                        sourceLanguage != CaptionLanguageState.currentSourceLanguage() ||
                        targetLanguage != module.config.targetLanguage
                    ) {
                        false
                    } else {
                        session.putTranslation(original, translated)
                        true
                    }
                }
                if (!accepted) return@post

                val normalizedTranslation = CaptionCue.normalize(translated)
                logV(
                    name,
                    "Translated caption from $source, sourceLanguage=$sourceLanguage, " +
                        "targetLanguage=$targetLanguage, length=${original.length}, " +
                        "translatedLength=${normalizedTranslation.length}, " +
                        "changed=${normalizedTranslation != CaptionCue.normalize(original)}, " +
                        "keyHash=${original.logHash()}",
                )
                refreshVisibleRenderers(original, source)
            }
        }
    }

    private fun observeCaptionTrack(track: Any?, source: String) {
        if (!CaptionLanguageState.updateFromCaptionTrack(track, source)) return

        synchronized(captionStateLock) {
            captionTrackEpoch.incrementAndGet()
            captionGeneration.incrementAndGet()
            captionTimelineIsRolling = false
            session.clear()
        }
        restoreAllBilingualCaptionOffsets()
        synchronized(captionWindows) {
            captionWindows.clear()
        }
        synchronized(nativeCaptionWindowUpdates) {
            nativeCaptionWindowUpdates.clear()
        }
        synchronized(rendererStates) {
            rendererStates.clear()
        }
        synchronized(bilingualCaptionEntries) {
            bilingualCaptionEntries.clear()
        }
        captionPrefetchMisses.clear()
        rendererSequence.set(0L)
    }

    private fun rememberNativeCaptionWindowUpdate(owner: Any?, argument: Any, method: Method) {
        if (owner == null) return
        method.isAccessible = true
        synchronized(nativeCaptionWindowUpdates) {
            nativeCaptionWindowUpdates[owner] = NativeCaptionWindowUpdate(method, argument)
        }
    }

    private fun replayNativeCaptionWindowUpdates() {
        val updates = synchronized(nativeCaptionWindowUpdates) {
            nativeCaptionWindowUpdates.entries.map { (owner, update) -> owner to update }
        }
        for ((owner, update) in updates) {
            runCatching {
                update.method.invoke(owner, update.argument)
            }.onFailure { error ->
                logW(name, "Unable to replay native caption window", error)
            }
        }
    }

    private fun rememberRendererState(
        renderer: Any,
        owner: Any,
        method: Method,
        normalizedText: String,
    ) {
        if (normalizedText.isEmpty()) return

        synchronized(rendererStates) {
            rendererStates[renderer] = RendererState(
                owner = WeakReference(owner),
                method = method,
                normalizedText = normalizedText,
                sequence = rendererSequence.incrementAndGet(),
            )
        }
    }

    private fun replacementForCaptionWindow(
        window: CaptionWindow,
        renderedFragment: String,
        translated: String?,
    ): CharSequence {
        val fragment = CaptionCue.normalize(renderedFragment)
        if (fragment.isEmpty()) return renderedFragment

        val displayText = window.displayText
        val formatted = BilingualFormatter.format(displayText, translated)
        if (CaptionCue.normalize(formatted.toString()) == fragment) {
            return renderedFragment
        }
        val isAnchor = displayText == fragment || if (window.isRolling) {
            displayText.contains(fragment)
        } else {
            displayText.endsWith(fragment)
        }
        return if (isAnchor) {
            formatted
        } else {
            ""
        }
    }

    private fun applyCachedTranslationToCaptionWindow(owner: Any?) {
        if (owner == null) return
        val window = synchronized(captionWindows) {
            captionWindows[owner]
        } ?: return
        val translated = session.translationFor(window.displayText)
        if (translated == null && !window.isRolling) return
        updateCaptionWindowTextLists(
            owner = owner,
            replacement = BilingualFormatter.format(window.displayText, translated),
        )
    }

    private fun replaceCaptionWindowTextFields(owner: Any?, arg: Any): List<RestorableFieldValue> {
        val window = arg.toCaptionWindow() ?: return emptyList()
        val translated = session.translationFor(window.displayText)
        if (translated == null && !window.isRolling) return emptyList()
        val replacement = BilingualFormatter.format(window.displayText, translated)
        val fields = captionWindowTextFields.getOrPut(arg.javaClass) {
            arg.instanceFields().filter { field ->
                CharSequence::class.java.isAssignableFrom(field.type)
            }
        }
        if (fields.isEmpty()) return emptyList()

        val values = ArrayList<RestorableFieldValue>(fields.size)
        for (field in fields) {
            val original = field.getValueOrNull(arg)
            val replaced = runCatching {
                field.isAccessible = true
                field.set(arg, replacement)
            }.isSuccess
            if (!replaced) {
                values.asReversed().forEach { value -> value.restore(arg) }
                if (captionWindowRewriteFailures.add(arg.javaClass)) {
                    logV(name, "Unable to rewrite caption window text for ${arg.javaClass.name}")
                }
                return emptyList()
            }
            values += RestorableFieldValue(field, original)
        }
        if (owner != null) {
            rememberBilingualCaptionEntries(owner, replacement)
        }
        return values
    }

    private fun updateCaptionWindowTextLists(owner: Any, replacement: CharSequence): Boolean {
        val textLists = owner.captionTextLists()
        if (textLists.isEmpty()) return false

        val replacementEntries = replacement.captionLines()
        rememberBilingualCaptionEntries(owner, replacementEntries)
        var changed = false
        for (list in textLists) {
            if (list.hasSameTextAs(replacementEntries)) continue

            @Suppress("UNCHECKED_CAST")
            val mutableList = list as MutableList<Any?>
            mutableList.clear()
            mutableList.addAll(replacementEntries)
            changed = true
        }
        if (changed) {
            (owner as? View)?.requestLayout()
            logV(
                name,
                "Updated caption text lists, entries=${replacementEntries.size}, " +
                    "lineBreaks=${(replacementEntries.size - 1).coerceAtLeast(0)}",
            )
        }
        return true
    }

    private fun rememberBilingualCaptionEntries(owner: Any, replacement: CharSequence) {
        rememberBilingualCaptionEntries(owner, replacement.captionLines())
    }

    private fun rememberBilingualCaptionEntries(
        owner: Any,
        replacementEntries: List<CharSequence>,
    ) {
        synchronized(bilingualCaptionEntries) {
            if (replacementEntries.size == 2) {
                bilingualCaptionEntries[owner] = replacementEntries.map { it.toString() }
            } else {
                restoreBilingualCaptionOffsets(owner)
                bilingualCaptionEntries.remove(owner)
            }
        }
    }

    private fun Any.captionTextLists(): List<MutableList<*>> =
        instanceFieldValues()
            .filterIsInstance<MutableList<*>>()
            .filter { list ->
                list.isNotEmpty() && list.all { it is CharSequence }
            }

    private fun Any.hasCaptionTextLines(replacement: CharSequence): Boolean {
        val expected = replacement.captionLines()
        return captionTextLists().any { list -> list.hasSameTextAs(expected) }
    }

    private fun List<*>.hasSameTextAs(expected: List<CharSequence>): Boolean =
        size == expected.size && indices.all { index ->
            this[index].toString() == expected[index].toString()
        }

    private fun CharSequence.captionLines(): List<CharSequence> {
        val lines = ArrayList<CharSequence>()
        var start = 0
        while (start < length) {
            val lineBreak = indexOf('\n', start)
            val end = if (lineBreak < 0) length else lineBreak
            lines += subSequence(start, end)
            if (lineBreak < 0) break
            start = lineBreak + 1
        }
        if (lines.isEmpty()) {
            lines += ""
        }
        return lines
    }

    private fun alignBilingualCaptionEntriesAfterLayout(
        owner: Any,
        normalizedText: String,
    ) {
        val parent = owner as? ViewGroup ?: return
        val expectedEntries = synchronized(bilingualCaptionEntries) {
            bilingualCaptionEntries[owner]
        }
        if (expectedEntries == null || normalizedText !in expectedEntries) {
            restoreBilingualCaptionOffsets(parent)
            return
        }
        if (normalizedText != expectedEntries[1]) return

        mainHandler.post {
            if (!module.config.enabled) {
                restoreBilingualCaptionOffsets(parent)
                return@post
            }
            val activeEntries = synchronized(bilingualCaptionEntries) {
                bilingualCaptionEntries[owner]
            }
            if (activeEntries != expectedEntries ||
                !owner.captionTextLists().any { it.hasSameTextAs(expectedEntries) } ||
                parent.childCount < expectedEntries.size
            ) {
                restoreBilingualCaptionOffsets(parent)
                return@post
            }

            val first = parent.getChildAt(0)
            val second = parent.getChildAt(1)
            if (first.visibility != View.VISIBLE || second.visibility != View.VISIBLE) {
                restoreBilingualCaptionOffsets(parent)
                return@post
            }

            val offsets = bilingualCaptionOffsets(parent, first, second)
            offsets.captureExternalTransforms(parent, first)
            val gap = (
                second.top - first.bottom + second.translationY - offsets.firstTranslationY
            ).coerceAtLeast(0f)
            val window = synchronized(captionWindows) {
                captionWindows[owner]
            }
            val firstTranslationY = offsets.firstTranslationY + gap
            val parentTranslationY = offsets.parentTranslationY +
                    if (window?.isRolling == false) gap / 2f else 0f
            val changed = first.translationY != firstTranslationY ||
                    parent.translationY != parentTranslationY
            if (changed) {
                first.translationY = firstTranslationY
                parent.translationY = parentTranslationY
                logV(
                    name,
                    "Aligned bilingual caption entries, entries=${expectedEntries.size}, " +
                        "children=${parent.childCount}, measuredGap=${gap.toInt()}, " +
                        "firstHeight=${first.height}, secondHeight=${second.height}, " +
                        "rolling=${window?.isRolling}, " +
                        "parentOffset=${(parentTranslationY - offsets.parentTranslationY).toInt()}, " +
                        "anchor=last",
                )
            }
            offsets.appliedFirstTranslationY = firstTranslationY.takeIf { gap > 0f }
            offsets.appliedParentTranslationY = parentTranslationY.takeIf {
                window?.isRolling == false && gap > 0f
            }
            refreshCaptionBottomSpacing(parent)
        }
    }

    private fun bilingualCaptionOffsets(
        parent: ViewGroup,
        first: View,
        second: View,
    ): BilingualCaptionOffsets {
        val existing = synchronized(bilingualCaptionOffsets) {
            bilingualCaptionOffsets[parent]
        }
        if (existing?.first?.get() === first && existing.second.get() === second) {
            return existing
        }

        restoreBilingualCaptionOffsets(parent)
        return BilingualCaptionOffsets(
            first = WeakReference(first),
            second = WeakReference(second),
            firstTranslationY = first.translationY,
            parentTranslationY = parent.translationY,
        ).also { offsets ->
            synchronized(bilingualCaptionOffsets) {
                bilingualCaptionOffsets[parent] = offsets
            }
        }
    }

    private fun restoreBilingualCaptionOffsets(owner: Any?) {
        val parent = owner as? ViewGroup ?: return
        val offsets = synchronized(bilingualCaptionOffsets) {
            bilingualCaptionOffsets.remove(parent)
        } ?: return

        restoreBilingualCaptionOffsets(parent, offsets)
    }

    private fun restoreBilingualCaptionOffsets(
        parent: ViewGroup,
        offsets: BilingualCaptionOffsets,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                restoreBilingualCaptionOffsets(parent, offsets)
            }
            return
        }

        val first = offsets.first.get()
        if (first != null && first.translationY == offsets.appliedFirstTranslationY) {
            first.translationY = offsets.firstTranslationY
        }
        if (parent.translationY == offsets.appliedParentTranslationY) {
            parent.translationY = offsets.parentTranslationY
        }
        refreshCaptionBottomSpacing(parent)
    }

    private fun BilingualCaptionOffsets.captureExternalTransforms(parent: ViewGroup, first: View) {
        val appliedFirst = appliedFirstTranslationY
        if (appliedFirst == null || first.translationY != appliedFirst) {
            firstTranslationY = first.translationY
            appliedFirstTranslationY = null
        }

        val appliedParent = appliedParentTranslationY
        if (appliedParent == null || parent.translationY != appliedParent) {
            parentTranslationY = parent.translationY
            appliedParentTranslationY = null
        }
    }

    private fun restoreAllBilingualCaptionOffsets() {
        val offsets = synchronized(bilingualCaptionOffsets) {
            bilingualCaptionOffsets.entries
                .map { (parent, value) -> parent to value }
                .also { bilingualCaptionOffsets.clear() }
        }
        offsets.forEach { (parent, value) ->
            restoreBilingualCaptionOffsets(parent, value)
        }
    }

    private fun refreshVisibleRenderers(original: String, source: String) {
        val normalized = CaptionCue.normalize(original)
        if (normalized.isEmpty()) return

        mainHandler.post {
            val activeWindows = synchronized(captionWindows) {
                captionWindows.entries
                    .filter { (_, window) ->
                        CaptionCue.normalize(window.displayText) == normalized
                    }
                    .map { (owner, window) -> owner to window }
            }
            val snapshot = synchronized(rendererStates) {
                rendererStates.entries
                    .mapNotNull { (renderer, state) ->
                        val view = renderer as? View ?: return@mapNotNull null
                        val owner = state.owner.get() ?: return@mapNotNull null
                        if (view.parent !== owner || !view.isAttachedToWindow || !view.isShown) {
                            return@mapNotNull null
                        }
                        renderer to state
                    }
            }

            val translated = session.translationFor(normalized)
            if (translated == null) return@post

            var refreshed = false
            for ((owner, window) in activeWindows) {
                val displayText = window.displayText
                val replacement = BilingualFormatter.format(displayText, translated)
                if (updateCaptionWindowTextLists(owner, replacement)) {
                    refreshed = true
                    continue
                }

                val targets = snapshot
                    .filter { (_, state) ->
                        state.owner.get() === owner &&
                                state.normalizedText.isNotEmpty() &&
                                displayText.contains(state.normalizedText)
                    }
                    .sortedBy { (_, state) -> state.sequence }
                if (targets.isEmpty()) continue

                val anchor = if (window.isRolling) {
                    targets.firstOrNull { (_, state) -> displayText.startsWith(state.normalizedText) }
                } else {
                    targets.lastOrNull { (_, state) -> displayText.endsWith(state.normalizedText) }
                } ?: targets.last()

                for ((renderer, state) in targets) {
                    if (renderer !== anchor.first) {
                        invokeRenderer(renderer, state.method, "", source, normalized.length)
                    }
                }

                invokeRenderer(anchor.first, anchor.second.method, replacement, source, normalized.length)
                refreshed = true
            }

            if (!refreshed) {
                logV(
                    name,
                    "Translation ready without a matching visible caption from $source, " +
                        "length=${normalized.length}, keyHash=${normalized.logHash()}",
                )
            }
        }
    }

    private fun invokeRenderer(
        renderer: Any,
        method: Method,
        text: CharSequence,
        source: String,
        originalLength: Int,
    ) {
        runCatching {
            applyingTranslatedText.set(true)
            method.invoke(renderer, text)
        }.onSuccess {
            if (text.isNotEmpty()) {
                logV(name, "Refreshed visible caption from $source, length=$originalLength")
            }
        }.onFailure { e ->
            logW(name, "Unable to refresh visible caption", e)
        }.also {
            applyingTranslatedText.set(false)
        }
    }

    private fun Executable.shortName(): String =
        "${declaringClass.name}#$name(${parameterTypes.joinToString { it.simpleName }})"

    private fun Executable.stableId(): String =
        "${declaringClass.name}#$name#${parameterTypes.joinToString { it.name }}"

    private fun DexKitBridge.findCaptionTimelineBuildMethod(): MethodData? {
        val addLineMethod = findMethod {
            matcher {
                returnType("void")
                paramTypes("java.lang.CharSequence", "long", "long")
                usingEqStrings(NON_DECREASING_SUBTITLE_TIME_ERROR)
            }
        }.singleOrNull()

        val builderClass = addLineMethod?.declaredClass ?: return null
        val candidates = builderClass.methods.filter { method ->
            method.isMethod &&
                    method.paramCount == 0 &&
                    method.returnTypeName != "void" &&
                    method.returnType?.hasAtLeastListFields(count = 3) == true
        }

        return candidates.singleOrNull() ?: candidates.firstOrNull()
    }

    private fun DexKitBridge.findWebVttDecoderMethod(): MethodData? =
        findMethod {
            matcher {
                returnType("void")
                usingEqStrings(WEBVTT_HEADER, MISSING_WEBVTT_HEADER_ERROR)
            }
        }.singleOrNull { method ->
            method.isMethod && method.paramCount > 0
        }

    private fun DexKitBridge.findCaptionEventWrapperConstructor(
        webVttDecoder: MethodData,
    ): MethodData? {
        val accumulatorType = webVttDecoder.paramTypeNames.firstOrNull() ?: return null
        return findMethod {
            matcher {
                paramTypes(accumulatorType)
            }
        }.singleOrNull { method ->
            method.isConstructor
        }
    }

    private fun ClassData.hasAtLeastListFields(count: Int): Boolean =
        fields.count { !Modifier.isStatic(it.modifiers) && it.typeName == "java.util.List" } >= count

    private fun ClassData.hasInstanceFieldTypeInHierarchy(typeName: String): Boolean {
        var clazz: ClassData? = this
        while (clazz != null) {
            if (clazz.fields.any { !Modifier.isStatic(it.modifiers) && it.typeName == typeName }) {
                return true
            }
            clazz = clazz.superClass
        }
        return false
    }

    private fun DexKitBridge.findCaptionRenderTextMethods(): List<MethodData> =
        findMethod {
            matcher {
                returnType("void")
                paramTypes("java.lang.CharSequence")
            }
        }.filter { method ->
            method.isMethod &&
                    method.declaredClass?.hasInstanceFieldTypeInHierarchy(EDITABLE_TYPE) == true
        }

    private fun DexKitBridge.findCaptionWindowClassNames(): Set<String> =
        findClass {
            matcher {
                usingEqStrings(CAPTION_WINDOW_TO_STRING_FORMAT)
            }
        }.mapTo(HashSet()) { it.name }

    private fun DexKitBridge.findCaptionOverlayUpdateMethods(
        captionWindowClassNames: Set<String>,
    ): List<MethodData> {
        if (captionWindowClassNames.isEmpty()) return emptyList()

        return findMethod {
            matcher {
                returnType("void")
                paramTypes("java.util.List")
            }
        }.filter { method ->
            method.isMethod &&
                    method.declaredClass?.hasInstanceFieldTypeInHierarchy(SPARSE_ARRAY_TYPE) == true &&
                    method.usingFields.any { it.field.declaredClassName in captionWindowClassNames }
        }
    }

    private fun DexKitBridge.findCaptionWindowUpdateMethods(
        captionWindowClassNames: Set<String>,
    ): List<MethodData> =
        captionWindowClassNames.flatMap { className ->
            findMethod {
                matcher {
                    returnType("void")
                    paramTypes(className)
                }
            }.filter { it.isMethod }
        }

    private fun DexKitBridge.findCaptionTrackClassName(): String? =
        findMethod {
            matcher {
                returnType("boolean")
                usingEqStrings(AUTO_TRANSLATE_CAPTIONS_OPTION)
            }
        }.singleOrNull { method -> method.isMethod && method.paramCount == 0 }
            ?.toMethod()
            ?.declaringClass
            ?.name

    private fun DexKitBridge.findCaptionTrackStateMethods(trackClassName: String): List<MethodData> =
        findMethod {
            matcher {
                returnType("void")
                paramTypes(trackClassName)
                usingEqStrings(MENU_ITEM_CAPTIONS_KEY)
            }
        }.filter { method ->
            method.isMethod &&
                    !Modifier.isAbstract(method.modifiers)
        }

    private const val NON_DECREASING_SUBTITLE_TIME_ERROR =
        "subtitles are not given in non-decreasing start time order"
    private const val WEBVTT_HEADER = "WEBVTT"
    private const val MISSING_WEBVTT_HEADER_ERROR = "Missing WEBVTT header"
    private const val AUTO_TRANSLATE_CAPTIONS_OPTION = "AUTO_TRANSLATE_CAPTIONS_OPTION"
    private const val MENU_ITEM_CAPTIONS_KEY = "menu_item_captions"
    private const val CAPTION_WINDOW_TO_STRING_FORMAT =
        "id: %d time: %d text: %s fullText: %s settings: %s"
    private const val EDITABLE_TYPE = "android.text.Editable"
    private const val SPARSE_ARRAY_TYPE = "android.util.SparseArray"
    private const val CAPTION_PREFETCH_WINDOW_COUNT = 12
    private const val CAPTION_TRANSLATION_BATCH_SIZE = 4
    private const val ROLLING_TIMELINE_END_RATIO = 2
    private const val MAX_ROLLING_SEGMENT_LENGTH = 100
    private const val MIN_ROLLING_SEGMENT_LENGTH = 40
    private const val MAX_ROLLING_SEGMENT_GAP_MS = 5_000L
    private const val CAPTION_BOTTOM_GAP_REDUCTION = 1f / 3f
    private val sentenceBoundary = Regex("[.!?。！？…]+[\\\"'”’）)\\]]*(?=\\s|$)")
    private val clauseBoundary = Regex("[,;:，；：]+[\\\"'”’）)\\]]*(?=\\s|$)")

    private fun String.logHash(): String =
        Integer.toHexString(CaptionCue.normalize(this).hashCode())

    private fun captionDisplayText(text: String, isRolling: Boolean): String {
        val lines = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { line -> CaptionCue.normalize(line) }
        if (!isRolling) {
            return lines.joinToString("\n").trim()
        }
        return lines.lastOrNull { line -> line.isNotEmpty() }.orEmpty()
    }

    private data class RendererState(
        val owner: WeakReference<Any>,
        val method: Method,
        val normalizedText: String,
        val sequence: Long,
    )

    private data class CaptionWindow(
        val cue: CaptionCue,
        val currentText: String,
        val fullText: String,
        val displayText: String,
        val isRolling: Boolean,
        val isTimelineGrouped: Boolean,
    )

    private data class NativeCaptionWindowUpdate(
        val method: Method,
        val argument: Any,
    )

    private data class CaptionBottomOffset(var offset: Int = 0)

    private data class BilingualCaptionOffsets(
        val first: WeakReference<View>,
        val second: WeakReference<View>,
        var firstTranslationY: Float,
        var parentTranslationY: Float,
        var appliedFirstTranslationY: Float? = null,
        var appliedParentTranslationY: Float? = null,
    )

    private data class TimelineLine(
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    private data class TimelineWindow(
        val startMs: Long,
        val text: String,
    )

    private data class TextRange(
        val startIndex: Int,
        val endIndex: Int,
    )

    private data class RestorableFieldValue(
        val field: Field,
        val value: Any?,
    ) {
        fun restore(instance: Any) {
            runCatching {
                field.isAccessible = true
                field.set(instance, value)
            }
        }
    }
}
