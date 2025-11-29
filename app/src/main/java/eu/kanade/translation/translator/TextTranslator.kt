package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable

interface TextTranslator : Closeable {
    val fromLang: TextRecognizerLanguage
    val toLang: TextTranslatorLanguage
    suspend fun translate(pages: MutableMap<String, PageTranslation>)
}

enum class TextTranslators(val label: String) {
    MLKIT("MlKit (On Device)"),
    GOOGLE("Google Translate"),
    GEMINI("Gemini AI [API KEY]"),
    OPENROUTER("OpenRouter [API KEY]");

    fun build(
        pref: TranslationPreferences = Injekt.get(),
        fromLang: TextRecognizerLanguage = TextRecognizerLanguage.fromPref(pref.translateFromLanguage()),
        toLang: TextTranslatorLanguage = TextTranslatorLanguage.fromPref(pref.translateToLanguage()),
    ): TextTranslator {
        val maxOutputTokens = pref.translationEngineMaxOutputTokens().get().toIntOrNull() ?: 8192
        val temperature = pref.translationEngineTemperature().get().toFloatOrNull() ?: 0.5f
        val topK = pref.translationEngineTopK().get().toIntOrNull() ?: 30
        val topP = pref.translationEngineTopP().get().toFloatOrNull() ?: 0.95f
        val presencePenalty = pref.translationEnginePresencePenalty().get().toFloatOrNull() ?: 0.0f
        val frequencyPenalty = pref.translationEngineFrequencyPenalty().get().toFloatOrNull() ?: 0.0f
        val modelName = pref.translationEngineModel().get()
        val apiKey = pref.translationEngineApiKey().get()
        val systemPrompt = pref.translationEngineSystemPrompt().get()

        return when (this) {
            MLKIT -> MLKitTranslator(fromLang, toLang)
            GOOGLE -> GoogleTranslator(fromLang, toLang)
            GEMINI -> GeminiTranslator(fromLang, toLang, apiKey, modelName, maxOutputTokens, temperature, topK, topP, presencePenalty, frequencyPenalty, systemPrompt)
            OPENROUTER -> OpenRouterTranslator(fromLang, toLang, apiKey, modelName, maxOutputTokens, temperature, topK, topP, presencePenalty, frequencyPenalty, systemPrompt)
        }
    }

    companion object {
        fun fromPref(pref: Preference<Int>): TextTranslators {
            val translator = entries.getOrNull(pref.get())
            if (translator == null) {
                pref.set(0)
                return MLKIT
            }
            return translator
        }
    }
}
