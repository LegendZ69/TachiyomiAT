package eu.kanade.translation.translator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.logs.LogLevel
import eu.kanade.translation.logs.TranslationLogManager
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MLKitTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {

    private val logManager = Injekt.get<TranslationLogManager>()

    private var translator = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(fromLang.code)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(toLang.code) ?: TranslateLanguage.ENGLISH)
            .build(),
    )

    private var conditions = DownloadConditions.Builder().build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            logManager.log(LogLevel.INFO, "MLKitTranslator", "Downloading model if needed...")
            Tasks.await(translator.downloadModelIfNeeded(conditions))
            
            pages.mapValues { (_, v) ->
                v.blocks.map { b ->
                    try {
                        b.translation = b.text.split("\n").mapNotNull {
                            Tasks.await(translator.translate(it)).takeIf { it.isNotEmpty() }
                        }.joinToString("\n")
                    } catch (e: Exception) {
                         logManager.log(LogLevel.ERROR, "MLKitTranslator", "Failed to translate block: ${b.text.take(20)}...", e)
                    }
                }
            }
        } catch (e: Exception) {
             logManager.log(LogLevel.ERROR, "MLKitTranslator", "MLKit Translation Error", e)
             throw e
        }
    }

    override fun close() {
        translator.close()
    }
}
