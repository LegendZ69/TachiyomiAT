package eu.kanade.translation

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.logs.LogLevel
import eu.kanade.translation.logs.TranslationLogManager
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationHelper
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizer
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class ChapterTranslator(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
) {

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var translationJob: Job? = null
    
    private val logManager = Injekt.get<TranslationLogManager>()

    val isRunning: Boolean
        get() = translationJob?.isActive == true

    @Volatile
    var isPaused: Boolean = false

    private var textRecognizer: TextRecognizer? = null
    private var textTranslator: TextTranslator? = null

    private val json = Json { prettyPrint = true }

    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Translation.State.TRANSLATED }
        pending.forEach { if (it.status != Translation.State.QUEUE) it.status = Translation.State.QUEUE }
        isPaused = false
        launchTranslatorJob()
        return pending.isNotEmpty()
    }

    fun stop(reason: String? = null) {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.ERROR }
        if (reason != null) return
        isPaused = false
    }

    fun pause() {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.QUEUE }
        isPaused = true
    }

    fun clearQueue() {
        cancelTranslatorJob()
        internalClearQueue()
    }

    private fun launchTranslatorJob() {
        if (isRunning) return

        translationJob = scope.launch {
            val activeTranslationFlow = queueState.transformLatest { queue ->
                while (true) {
                    val activeTranslations =
                        queue.asSequence().filter { it.status.value <= Translation.State.TRANSLATING.value }
                            .groupBy { it.source }.toList().take(5).map { (_, translations) -> translations.first() }
                    emit(activeTranslations)

                    if (activeTranslations.isEmpty()) break
                    val activeTranslationsErroredFlow =
                        combine(activeTranslations.map(Translation::statusFlow)) { states ->
                            states.contains(Translation.State.ERROR)
                        }.filter { it }
                    activeTranslationsErroredFlow.first()
                }
            }.distinctUntilChanged()
            supervisorScope {
                val translationJobs = ConcurrentHashMap<Translation, Job>()

                activeTranslationFlow.collectLatest { activeTranslations ->
                    val translationJobsToStop = translationJobs.filter { !activeTranslations.contains(it.key) }
                    translationJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        translationJobs.remove(download)
                    }

                    val translationsToStart = activeTranslations.filter { !translationJobs.containsKey(it) }
                    translationsToStart.forEach { translation ->
                        translationJobs[translation] = launchTranslationJob(translation)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchTranslationJob(translation: Translation) = launchIO {
        try {
            logManager.log(LogLevel.INFO, "ChapterTranslator", "Starting translation for chapter: ${translation.chapter.name} (${translation.manga.title})")
            translateChapter(translation)
            if (translation.status == Translation.State.TRANSLATED) {
                logManager.log(LogLevel.INFO, "ChapterTranslator", "Translation completed for chapter: ${translation.chapter.name} (${translation.manga.title})")
                removeFromQueue(translation)
            }
            if (areAllTranslationsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logManager.log(LogLevel.ERROR, "ChapterTranslator", "Translation failed for chapter: ${translation.chapter.name} (${translation.manga.title})", e)
            logcat(LogPriority.ERROR, e)
            stop()
        }
    }

    private fun cancelTranslatorJob() {
        translationJob?.cancel()
        translationJob = null
    }

    fun queueChapter(manga: Manga, chapter: Chapter) {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        if (provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source) != null) return
        if (queueState.value.any { it.chapter.id == chapter.id }) return
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val engine = TextTranslators.fromPref(translationPreferences.translationEngine())
        if (engine == TextTranslators.MLKIT && !TextTranslatorLanguage.mlkitSupportedLanguages().contains(toLang)) {
            context.toast(ATMR.strings.error_mlkit_language_unsupported)
            return
        }
        val translation = Translation(source, manga, chapter, fromLang, toLang)
        addToQueue(translation)
    }

    private suspend fun translateChapter(translation: Translation) {
        translation.status = Translation.State.TRANSLATING
        try {
            // Re-init engines if needed, synchronized to avoid race conditions if multiple jobs start (though ChapterTranslator is singleton per Manager)
            // But let's keep it simple for now as this is called sequentially per worker usually
            if (textRecognizer == null || textRecognizer?.language != translation.fromLang) {
                textRecognizer?.close()
                textRecognizer = TextRecognizer(translation.fromLang)
            }

             if (textTranslator == null || textTranslator?.fromLang != translation.fromLang || textTranslator?.toLang != translation.toLang) {
                textTranslator?.close()
                textTranslator = TextTranslators.fromPref(translationPreferences.translationEngine())
                    .build(translationPreferences, translation.fromLang, translation.toLang)
            }

            val recognizer = textRecognizer!!
            val translator = textTranslator!!

            val translationMangaDir = provider.getMangaDir(translation.manga.title, translation.source)
            val saveFile = provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator)

            val chapterPath = downloadProvider.findChapterDir(
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            ) ?: throw Exception("Chapter images not found. Please download the chapter first.")

            val pages = mutableMapOf<String, PageTranslation>()
            val pageDataList = getChapterPages(chapterPath)
            
            // Create a temp file for image processing if needed
            val tmpFile = translationMangaDir.createFile("tmp_processing_image") ?: throw Exception("Could not create temp file")

            try {
                withContext(Dispatchers.IO) {
                    for (pageData in pageDataList) {
                        coroutineContext.ensureActive()
                        
                        // Optimized: If file exists on disk, use it directly. Otherwise extract stream to temp file.
                        val imageUri = when (pageData) {
                            is PageData.File -> pageData.uri
                            is PageData.Stream -> {
                                pageData.open().use { input ->
                                    tmpFile.openOutputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                tmpFile.uri
                            }
                        }

                        val image = InputImage.fromFilePath(context, imageUri)
                        val result = recognizer.recognize(image)
                        val blocks = result.textBlocks.filter { it.boundingBox != null && it.text.trim().length > 1 }
                        val pageTranslation = convertToPageTranslation(blocks, image.width, image.height)
                        if (pageTranslation.blocks.isNotEmpty()) {
                            pages[pageData.name] = pageTranslation
                        }
                    }
                }
            } finally {
                tmpFile.delete()
            }

            withContext(Dispatchers.IO) {
                if (pages.isNotEmpty()) {
                    logManager.log(LogLevel.DEBUG, "ChapterTranslator", "Starting text translation for ${pages.size} pages")
                    translator.translate(pages)
                    logManager.log(LogLevel.DEBUG, "ChapterTranslator", "Text translation completed")
                } else {
                    logManager.log(LogLevel.WARN, "ChapterTranslator", "No text detected in chapter images")
                }
            }
            
            val jsonString = json.encodeToString(pages)
            val outputFile = translationMangaDir.createFile(saveFile) ?: throw Exception("Could not create output file")
            outputFile.openOutputStream().use { it.write(jsonString.toByteArray()) }

            translation.status = Translation.State.TRANSLATED
        } catch (error: Throwable) {
            translation.status = Translation.State.ERROR
            logManager.log(LogLevel.ERROR, "ChapterTranslator", "Error translating chapter: ${error.message}", error)
            logcat(LogPriority.ERROR, error)
            throw error
        }
    }

    private fun convertToPageTranslation(blocks: List<Text.TextBlock>, width: Int, height: Int): PageTranslation {
        val translation = PageTranslation(imgWidth = width.toFloat(), imgHeight = height.toFloat())
        for (block in blocks) {
            val bounds = block.boundingBox ?: continue
            val lines = block.lines
            if (lines.isEmpty()) continue
            val firstLine = lines.first()
            val elements = firstLine.elements
            if (elements.isEmpty()) continue
            val firstSymbol = elements.first().symbols.firstOrNull() ?: continue
            val symBounds = firstSymbol.boundingBox ?: continue

            translation.blocks.add(
                TranslationBlock(
                    text = block.text,
                    width = bounds.width().toFloat(),
                    height = bounds.height().toFloat(),
                    symWidth = symBounds.width().toFloat(),
                    symHeight = symBounds.height().toFloat(),
                    angle = firstLine.angle,
                    x = bounds.left.toFloat(),
                    y = bounds.top.toFloat(),
                ),
            )
        }
        
        translation.blocks = PageTranslationHelper.smartMergeBlocks(translation.blocks)

        return translation
    }

    private fun getChapterPages(chapterPath: UniFile): List<PageData> {
        if (chapterPath.isFile) {
            val reader = chapterPath.archiveReader(context)
            return reader.useEntries { entries ->
                entries.filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }.map { entry ->
                        PageData.Stream(entry.name) { reader.getInputStream(entry.name)!! }
                    }.toList()
            }
        } else {
            return chapterPath.listFiles()!!.filter { ImageUtil.isImage(it.name) }
                .sortedWith { f1, f2 -> f1.name!!.compareToCaseInsensitiveNaturalOrder(f2.name!!) }
                .map { file ->
                    PageData.File(file.name!!, file.uri)
                }.toList()
        }
    }

    private sealed interface PageData {
        val name: String
        data class Stream(override val name: String, val open: () -> InputStream) : PageData
        data class File(override val name: String, val uri: Uri) : PageData
    }

    private fun areAllTranslationsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Translation.State.TRANSLATING.value }
    }

    private fun addToQueue(translation: Translation) {
        translation.status = Translation.State.QUEUE
        _queueState.update {
            it + translation
        }
    }

    private fun removeFromQueue(translation: Translation) {
        _queueState.update {
            if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
                translation.status = Translation.State.NOT_TRANSLATED
            }
            it - translation
        }
    }

    private inline fun removeFromQueueIf(predicate: (Translation) -> Boolean) {
        _queueState.update { queue ->
            val translations = queue.filter { predicate(it) }
            translations.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.QUEUE
                ) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            queue - translations
        }
    }

    fun removeFromQueue(chapter: Chapter) {
        removeFromQueueIf { it.chapter.id == chapter.id }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.QUEUE
                ) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            emptyList()
        }
    }
}
