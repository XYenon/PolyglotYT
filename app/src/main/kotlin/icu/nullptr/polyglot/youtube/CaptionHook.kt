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
import icu.nullptr.polyglot.captions.SegmentedCaptionPresentation
import icu.nullptr.polyglot.captions.SubtitleProcessing
import icu.nullptr.polyglot.captions.SubtitlePipeline
import icu.nullptr.polyglot.captions.TimedTextDecoder
import icu.nullptr.polyglot.captions.TimedTextEvent
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
    private val pipeline = SubtitlePipeline { onPipelineChanged() }
    private val rawDecoderEvents = ThreadLocal<List<TimedTextEvent>?>()
    private val clockClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    @Volatile private var playbackPositionMs = Double.NaN
    private var bridgeVideoTime: Method? = null
    private var bridgePolling = false
    private val bridgeClock = object : Runnable {
        override fun run() {
            if (!bridgePolling) return
            if (!module.config.enabled || pipeline.cues().isEmpty()) {
                bridgePolling = false
                return
            }
            runCatching { (bridgeVideoTime?.invoke(null) as? Number)?.toDouble() }.getOrNull()
                ?.takeIf { it >= 0 }?.let { onPlaybackPosition(it) }
            mainHandler.postDelayed(this, 250)
        }
    }
    private val pendingDisplayRefresh = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captionWindows = WeakHashMap<Any, CaptionWindow>()
    private val nativeCaptionWindowUpdates = WeakHashMap<Any, NativeCaptionWindowUpdate>()
    private val appliedPresentations = WeakHashMap<Any, RenderedCaption>()
    private val captionBottomOffsets = WeakHashMap<View, CaptionBottomOffset>()
    private val captionWindowClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val captionWindowFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val captionWindowTextFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val captionAccumulatorEventListFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val captionEventWindowListFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val captionWindowLookupFailures = ConcurrentHashMap.newKeySet<Class<*>>()
    private val captionWindowRewriteFailures = ConcurrentHashMap.newKeySet<Class<*>>()
    private val captionAccumulatorParseFailures = ConcurrentHashMap.newKeySet<Class<*>>()
    private val captionEventParseFailures = ConcurrentHashMap.newKeySet<Class<*>>()
    private val captionStateLock = Any()
    private val captionTrackEpoch = AtomicLong(0L)
    private val captionGeneration = AtomicLong(0L)
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

    fun onEnabledChanged(enabled: Boolean) {
        if (!enabled) {
            TranslationManager.cancelPending()
            synchronized(captionStateLock) { captionGeneration.incrementAndGet() }
        } else pipeline.reprocess()
        mainHandler.post {
            if (module.config.enabled != enabled) return@post
            replayNativeCaptionWindowUpdates()
            if (enabled) activeCaptionWindows().forEach { (_, window) ->
                requestCaptionWindowTranslations(window, source = "enabled")
            }
        }
    }

    fun onTranslationSettingsChanged() {
        TranslationManager.onSettingsChanged()
        synchronized(captionStateLock) {
            captionGeneration.incrementAndGet()
            session.clearTranslations()
        }
        pipeline.reprocess()
        mainHandler.post {
            replayNativeCaptionWindowUpdates()
            if (module.config.enabled && module.config.autoTranslate) activeCaptionWindows().forEach { (_, window) ->
                requestCaptionWindowTranslations(window, source = "settings")
            }
        }
    }

    fun onSubtitleModeChanged() {
        mainHandler.post { replayNativeCaptionWindowUpdates() }
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
            val nativeResult = chain.proceed()
            if (module.config.enabled && module.config.autoTranslate && expectedTrackEpoch == captionTrackEpoch.get()) {
                runCatching { buildSegmentedTimeline(nativeResult) }
                    .onFailure { logW(name, "Unable to build kiss caption timeline", it) }.getOrNull() ?: nativeResult
            } else nativeResult
        }

        runCatching { installRawCaptionDecoderHooks(dexkit) }
            .onFailure { logW(name, "Unable to install raw subtitle decoder hooks; native timeline remains available", it) }
        runCatching { installVideoContextHooks() }
            .onFailure { logW(name, "Unable to install optional video context hooks", it) }
        logD(name, "Hooked caption timeline builder: ${method.shortName()}")
        return true
    }

    private fun installRenderTextHooks(dexkit: DexKitBridge): Boolean {
        val methods = dexkit.findCaptionRenderTextMethods().map { it.toMethod() }
            .filter { View::class.java.isAssignableFrom(it.declaringClass) && it.declaringClass.hasInstanceFieldAssignableTo(Editable::class.java) }
            .distinctBy { it.stableId() }
        for (method in methods) {
            hook(method) { chain ->
                // Window updates supply the complete processed source and translation. Each
                // resulting renderer row passes through without additional merging or offsets.
                if (module.config.enabled && rewritingCaptionWindow.get() != true) {
                    val owner = (chain.thisObject as? View)?.parent
                    if (owner != null) {
                        observeCaptionWindow(owner, owner.captionWindowFieldValue())
                        val original = chain.getArg(0) as? CharSequence
                        if (session.observeRenderedText(original)) logV(name, "Observed native rendered caption text, length=${original?.length ?: 0}")
                    }
                }
                chain.proceed()
            }
            logD(name, "Hooked caption renderer observer: ${method.shortName()}")
        }
        if (methods.isEmpty()) logW(name, "Caption renderer not found")
        return methods.isNotEmpty()
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
        val layoutParameterTypes = arrayOf(
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val captionWindowClassNames = dexkit.findCaptionWindowClassNames()
        val methods = dexkit.findCaptionOverlayUpdateMethods(captionWindowClassNames)
            .mapNotNull { candidate ->
                var clazz: Class<*>? = candidate.toMethod().declaringClass
                while (clazz != null && clazz != ViewGroup::class.java &&
                    ViewGroup::class.java.isAssignableFrom(clazz)
                ) {
                    val layoutMethod = clazz.declaredMethods.firstOrNull { method ->
                        method.name == "onLayout" &&
                                method.parameterTypes.contentEquals(layoutParameterTypes)
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

    private fun buildSegmentedTimeline(result: Any?): Any? {
        if (result == null) return null
        val lists = result.instanceFieldValues().filterIsInstance<List<*>>()
        val texts = lists.firstOrNull { it.isNotEmpty() && it.all { value -> value is CharSequence } } ?: return null
        val times = lists.filter { it.isNotEmpty() && it.all { value -> value is Number } }
        if (times.size < 2) return null
        val (starts, ends) = orderedTimelineTimeLists(times[0], times[1])
        val count = minOf(starts.size, ends.size, texts.size)
        val nativeEvents = TimedTextDecoder.fromNative(
            starts.take(count).map { (it as Number).toLong() }, ends.take(count).map { (it as Number).toLong() },
            texts.take(count).map { it.toString() },
        )
        val events = rawDecoderEvents.get()?.takeIf { it.isNotEmpty() } ?: nativeEvents
        val language = CaptionLanguageState.currentSourceLanguage()
        val cues = pipeline.load(events, language)
        if (cues.isEmpty()) return null
        val intervals = SegmentedCaptionPresentation.intervals(SubtitleProcessing.prepare(events, language).words, cues)
        val constructor = result.javaClass.declaredConstructors.firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(List::class.java, List::class.java, List::class.java))
        } ?: return null
        constructor.isAccessible = true
        val segmented = constructor.newInstance(intervals.map { it.startMs }, intervals.map { it.endMs }, intervals.map { it.text })
        logD(name, "Built kiss display timeline, events=${events.size}, cues=${cues.size}, nativeIntervals=${intervals.size}, source=kiss")
        return segmented
    }

    private fun installRawCaptionDecoderHooks(dexkit: DexKitBridge) {
        val webVtt = dexkit.findWebVttDecoderMethod() ?: return
        val accumulator = webVtt.paramTypeNames.firstOrNull() ?: return
        val decoderMethods = dexkit.findMethod {
            matcher { returnType("void"); paramTypes(accumulator, webVtt.paramTypeNames.getOrNull(1), "long", "int") }
        }.filter { it.isMethod && !Modifier.isAbstract(it.modifiers) }.map { it.toMethod() }
        for (method in decoderMethods) {
            hook(method) { chain ->
                val previous = rawDecoderEvents.get()
                try {
                    if (module.config.enabled) {
                        val buffer = chain.getArg(1)
                        val bytes = buffer?.instanceFieldValues()?.filterIsInstance<ByteArray>()?.firstOrNull()
                        val length = (chain.getArg(3) as? Number)?.toInt() ?: 0
                        if (bytes != null && length in 1..5_000_000 && bytes.size >= length) {
                            // The byte reader maintains an offset; try its integer fields without consuming it.
                            val offsets = listOf(0) + buffer.instanceFieldValues().filterIsInstance<Int>()
                            val decoded = offsets.distinct().asSequence().filter { it >= 0 && it <= bytes.size - length }.mapNotNull { offset ->
                                val first = bytes[offset].toInt().toChar()
                                if (first == '{' || first == '<') TimedTextDecoder.decode(String(bytes, offset, length, Charsets.UTF_8)) else null
                            }.firstOrNull()
                            if (decoded != null) rawDecoderEvents.set(decoded)
                        }
                    }
                    chain.proceed()
                } finally { rawDecoderEvents.set(previous) }
            }
        }
        logD(name, "Hooked ${decoderMethods.size} raw subtitle decoder(s)")
    }

    private fun installVideoContextHooks() {
        // Optional metadata bridges in patched YouTube; standard tracks use their timedtext URL instead.
        val clazz = listOf("app.morphe.extension.youtube.patches.VideoInformation", "app.revanced.extension.youtube.patches.VideoInformation").firstNotNullOfOrNull {
            runCatching { Class.forName(it, false, module.hostClassLoader) }.getOrNull()
        } ?: return
        bridgeVideoTime = clazz.declaredMethods.firstOrNull {
            it.name == "getVideoTime" && Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType
        }
        val setId = clazz.declaredMethods.firstOrNull { it.name == "setVideoId" && it.parameterTypes.contentEquals(arrayOf(String::class.java)) }
        setId?.let { method ->
            hook(method) { chain ->
                val result = chain.proceed()
                val id = chain.getArg(0) as? String
                if (module.config.enabled && id != null && id != TranslationManager.document.videoId) {
                    onVideoContextChanged(icu.nullptr.polyglot.translate.VideoContext(videoId = id))
                    VideoContextLoader.load(id) { applyLoadedVideoContext(it) }
                }
                result
            }
        }
        clazz.declaredMethods.firstOrNull { it.name == "setVideoTitle" && it.parameterTypes.contentEquals(arrayOf(String::class.java)) }?.let { method ->
            hook(method) { chain ->
                val result = chain.proceed()
                val title = chain.getArg(0) as? String
                if (module.config.enabled && title != null && title != TranslationManager.document.title) {
                    onVideoContextChanged(TranslationManager.document.copy(title = title))
                    VideoContextLoader.load(TranslationManager.document.videoId) { applyLoadedVideoContext(it) }
                }
                result
            }
        }
    }

    private fun applyLoadedVideoContext(document: icu.nullptr.polyglot.translate.VideoContext) {
        synchronized(captionStateLock) {
            if (!module.config.enabled || document.videoId != TranslationManager.document.videoId) return
            onVideoContextChanged(document)
        }
    }

    private fun onVideoContextChanged(document: icu.nullptr.polyglot.translate.VideoContext) {
        if (document == TranslationManager.document) return
        val videoChanged = document.videoId != TranslationManager.document.videoId
        synchronized(captionStateLock) {
            captionGeneration.incrementAndGet()
            session.clearTranslations()
            if (videoChanged) {
                captionTrackEpoch.incrementAndGet()
                session.clear()
                pipeline.reset()
                playbackPositionMs = Double.NaN
            }
        }
        pipeline.updateDocument(document)
    }

    private fun installNativeEventClock(clazz: Class<*>) {
        val transition = arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        for (owner in listOfNotNull(clazz, clazz.superclass)) {
            if (!clockClasses.add(owner)) continue
            // Protected ticks and activation callbacks receive playback position. Public long setters
            // also change event end times, so they must not feed the clock.
            val methods = owner.declaredMethods.filter {
                Modifier.isProtected(it.modifiers) && it.returnType == Void.TYPE &&
                    (it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) || it.parameterTypes.contentEquals(transition))
            }
            for (method in methods) {
                hook(method) { chain ->
                    val result = chain.proceed()
                    if (module.config.enabled) {
                        val time = (chain.getArg(method.parameterCount - 1) as? Number)?.toDouble()
                        if (time != null && time >= 0) onPlaybackPosition(time)
                    }
                    result
                }
                logD(name, "Hooked subtitle playback clock: ${method.shortName()}")
            }
        }
    }

    private fun onPlaybackPosition(timeMs: Double) {
        playbackPositionMs = timeMs
        val texts = pipeline.playback(timeMs)
        if (texts.isNotEmpty()) queueCaptionTranslations(texts, "playback", pipeline.current(timeMs)?.text.orEmpty())
        if (pendingDisplayRefresh.compareAndSet(false, true)) mainHandler.post {
            pendingDisplayRefresh.set(false)
            refreshProcessedCaptionDisplay()
        }
    }

    private fun onPipelineChanged() {
        mainHandler.post {
            if (!module.config.enabled || !module.config.autoTranslate) return@post
            if (bridgeVideoTime != null && !bridgePolling) {
                bridgePolling = true
                mainHandler.post(bridgeClock)
            }
            pipeline.cues().forEach { cue ->
                if (!cue.draft && cue.translation.isNotBlank()) session.putTranslation(cue.text, cue.translation)
            }
            val time = playbackPositionMs.takeIf { it.isFinite() } ?: pipeline.cues().firstOrNull()?.start ?: return@post
            val texts = pipeline.playback(time, force = true)
            if (texts.isNotEmpty()) queueCaptionTranslations(texts, "processed", pipeline.current(time)?.text.orEmpty())
            refreshProcessedCaptionDisplay()
        }
    }

    private fun refreshProcessedCaptionDisplay() {
        // Source and translation can both change after AI segmentation or a playback seek.
        replayNativeCaptionWindowUpdates(onlyChanged = true)
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
        logV(
            name,
            "Observed caption window, rolling=${window.isRolling}, " +
                "id=${window.cue.windowId}, time=${window.cue.startMs}, " +
                "currentLength=${window.currentText.length}, fullLength=${window.fullText.length}, " +
                "sourceLines=${window.fullText.count { character -> character == '\n' } + 1}, " +
                "segmentLength=${window.displayText.length}, " +
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
            installNativeEventClock(event.javaClass)
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
        val values = instanceFieldValues()
        val texts = values.filterIsInstance<CharSequence>()
        if (texts.isEmpty()) return null
        // Supported native models store current text before full text. Keep their references
        // and whitespace; current/full differences drive YouTube's rolling layout.
        val currentText = texts.first()
        val fullText = texts.getOrElse(1) { currentText }
        val startMs = values.filterIsInstance<Long>().firstOrNull() ?: CaptionCue.UNKNOWN_TIME_MS
        return CaptionWindow(
            cue = CaptionCue(videoId = "", startMs = startMs, endMs = CaptionCue.UNKNOWN_TIME_MS,
                text = fullText.toString(), windowId = values.filterIsInstance<Int>().firstOrNull() ?: CaptionCue.UNKNOWN_WINDOW_ID),
            currentText = currentText, fullText = fullText, displayText = currentText.toString(),
            isRolling = currentText.toString() != fullText.toString(),
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
        if (!module.config.autoTranslate) return
        if (pipeline.cues().isNotEmpty()) {
            val time = playbackPositionMs.takeIf { it.isFinite() } ?: window.cue.startMs.toDouble()
            queueCaptionTranslations(pipeline.playback(time), source, window.displayText)
            return
        }
        // Prefer native full text so progressive fragments do not each start a new request.
        queueCaptionTranslations(listOf(window.fullText.toString()), source, window.displayText)
    }

    private fun queueCaptionTranslations(
        texts: Iterable<String>,
        source: String,
        currentText: String,
        expectedGeneration: Long? = null,
    ) {
        if (!module.config.autoTranslate) return
        val candidates = texts.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return
        val scheduled = requestTranslations(candidates, source, expectedGeneration)
        if (scheduled > 0) logV(name, "Queued $scheduled caption translation(s) from $source, currentHash=${currentText.logHash()}")
    }

    private fun requestTranslations(
        texts: Iterable<String>,
        source: String,
        expectedGeneration: Long? = null,
    ): Int {
        val pending = texts.filter { text -> !session.hasFinalTranslation(text) }
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
            onPartial = { original, translated ->
                mainHandler.post {
                    if (module.config.enabled && trackEpoch == captionTrackEpoch.get() && generation == captionGeneration.get()) {
                        session.putDraftTranslation(original, translated)
                        refreshProcessedCaptionDisplay()
                    }
                }
            },
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
                refreshProcessedCaptionDisplay()
            }
        }
    }

    private fun observeCaptionTrack(track: Any?, source: String) {
        if (!CaptionLanguageState.updateFromCaptionTrack(track, source)) return
        val previousDocument = TranslationManager.document
        TranslationManager.onTrackChanged()
        if (previousDocument.videoId.isNotEmpty()) TranslationManager.updateDocument(previousDocument.copy(summary = ""))
        pipeline.reset()
        playbackPositionMs = Double.NaN

        synchronized(captionStateLock) {
            captionTrackEpoch.incrementAndGet()
            captionGeneration.incrementAndGet()
            session.clear()
        }
        synchronized(captionWindows) {
            captionWindows.clear()
        }
        synchronized(nativeCaptionWindowUpdates) {
            nativeCaptionWindowUpdates.clear()
        }
        synchronized(appliedPresentations) { appliedPresentations.clear() }
        val videoId = VideoContextLoader.videoId(track)
        if (videoId != null) {
            onVideoContextChanged(icu.nullptr.polyglot.translate.VideoContext(videoId = videoId))
            VideoContextLoader.load(videoId) { applyLoadedVideoContext(it) }
        }
    }

    private fun rememberNativeCaptionWindowUpdate(owner: Any?, argument: Any, method: Method) {
        if (owner == null) return
        method.isAccessible = true
        synchronized(nativeCaptionWindowUpdates) {
            nativeCaptionWindowUpdates[owner] = NativeCaptionWindowUpdate(method, argument)
        }
    }

    private fun presentation(window: CaptionWindow): RenderedCaption {
        val enabled = module.config.enabled && module.config.autoTranslate
        val time = playbackPositionMs.takeIf { it.isFinite() } ?: window.cue.startMs.toDouble()
        val cue = if (enabled) pipeline.current(time) else null
        val segmented = enabled && pipeline.cues().isNotEmpty()
        val source = SegmentedCaptionPresentation.source(window.currentText, cue, segmented)
        val translated = if (!enabled || source.isBlank()) null else if (cue != null) {
            session.translationFor(cue.text) ?: cue.translation.takeIf { it.isNotBlank() }
        } else if (!segmented) session.translationFor(window.fullText.toString()) else null
        return RenderedCaption(source, translated, module.config.subtitleMode, segmented)
    }

    private fun replayNativeCaptionWindowUpdates(onlyChanged: Boolean = false) {
        val updates = synchronized(nativeCaptionWindowUpdates) {
            nativeCaptionWindowUpdates.entries.map { (owner, update) -> owner to update }
        }
        for ((owner, update) in updates) {
            val view = owner as? View ?: continue
            if (!view.isAttachedToWindow || !view.isShown) continue
            val window = synchronized(captionWindows) { captionWindows[owner] } ?: continue
            if (onlyChanged && synchronized(appliedPresentations) { appliedPresentations[owner] == presentation(window) }) continue
            runCatching { update.method.invoke(owner, update.argument) }
                .onFailure { logW(name, "Unable to replay native caption window", it) }
        }
    }

    private fun replaceCaptionWindowTextFields(owner: Any?, arg: Any): List<RestorableFieldValue> {
        val window = synchronized(captionWindows) { captionWindows[owner] } ?: arg.toCaptionWindow() ?: return emptyList()
        val presentation = presentation(window)
        if (owner != null) synchronized(appliedPresentations) { appliedPresentations[owner] = presentation }
        if (!presentation.segmented && presentation.translation == null) return emptyList()
        val fields = captionWindowTextFields.getOrPut(arg.javaClass) {
            arg.instanceFields().filter { CharSequence::class.java.isAssignableFrom(it.type) }
        }
        val values = ArrayList<RestorableFieldValue>(fields.size)
        for (field in fields) {
            val original = field.getValueOrNull(arg) as? CharSequence ?: continue
            val source = if (presentation.segmented) presentation.source else original
            val replacement = BilingualFormatter.format(source, presentation.translation, presentation.mode)
            if (replacement === original) continue
            val replaced = runCatching { field.isAccessible = true; field.set(arg, replacement) }.isSuccess
            if (!replaced) {
                values.asReversed().forEach { it.restore(arg) }
                if (captionWindowRewriteFailures.add(arg.javaClass)) logV(name, "Unable to render kiss caption in native window ${arg.javaClass.name}")
                return emptyList()
            }
            values += RestorableFieldValue(field, original)
        }
        return values
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
    private const val CAPTION_BOTTOM_GAP_REDUCTION = 1f / 3f

    private fun String.logHash(): String =
        Integer.toHexString(CaptionCue.normalize(this).hashCode())

    private data class CaptionWindow(
        val cue: CaptionCue,
        val currentText: CharSequence,
        val fullText: CharSequence,
        val displayText: String,
        val isRolling: Boolean,
    )

    private data class RenderedCaption(
        val source: CharSequence,
        val translation: String?,
        val mode: String,
        val segmented: Boolean,
    )

    private data class NativeCaptionWindowUpdate(
        val method: Method,
        val argument: Any,
    )

    private data class CaptionBottomOffset(var offset: Int = 0)

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
