package eu.kanade.translation.translator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.logs.LogLevel
import eu.kanade.translation.logs.TranslationLogManager
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class MLKitTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {

    private val logManager = Injekt.get<TranslationLogManager>()

    private var translator: Translator? = null

    private fun getTranslatorClient(): Translator {
        if (translator == null) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(fromLang.code)
                .setTargetLanguage(TranslateLanguage.fromLanguageTag(toLang.code) ?: TranslateLanguage.ENGLISH)
                .build()
            translator = Translation.getClient(options)
        }
        return translator!!
    }

    private var conditions = DownloadConditions.Builder().build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val client = getTranslatorClient()
        try {
            logManager.log(LogLevel.INFO, "MLKitTranslator", "Downloading model if needed...")
            // Download model with a timeout or just wait indefinitely if needed, but in coroutines we should be careful.
            // Using withContext(Dispatchers.IO) to ensure we don't block main thread even if called from there (though should be background)
            withContext(Dispatchers.IO) {
                try {
                    Tasks.await(client.downloadModelIfNeeded(conditions), 2, TimeUnit.MINUTES)
                } catch (e: TimeoutException) {
                    throw Exception("Model download timed out", e)
                }
            }
            
            pages.mapValues { (_, v) ->
                v.blocks.map { b ->
                    try {
                        // Split by newlines to translate each line separately if needed, or translate whole block
                        // Translating line by line might preserve formatting better for speech bubbles
                        val translatedText = b.text.split("\n").mapNotNull { line ->
                             if (line.isBlank()) null else {
                                 try {
                                     // Tasks.await is blocking, so we are blocking the IO thread, which is fine for this operation
                                     Tasks.await(client.translate(line)).takeIf { it.isNotEmpty() }
                                 } catch (e: Exception) {
                                     logManager.log(LogLevel.WARN, "MLKitTranslator", "Failed to translate line: '${line.take(20)}...'", e)
                                     null
                                 }
                             }
                        }.joinToString("\n")
                        
                        b.translation = if (translatedText.isNotBlank()) translatedText else b.text
                    } catch (e: Exception) {
                         logManager.log(LogLevel.ERROR, "MLKitTranslator", "Failed to translate block: ${b.text.take(20)}...", e)
                         // Fallback to original text
                         b.translation = b.text
                    }
                }
            }
        } catch (e: Exception) {
             logManager.log(LogLevel.ERROR, "MLKitTranslator", "MLKit Translation Error", e)
             throw e
        }
    }

    override fun close() {
        translator?.close()
        translator = null
    }
}
