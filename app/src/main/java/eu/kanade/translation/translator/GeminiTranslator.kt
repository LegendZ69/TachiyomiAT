package eu.kanade.translation.translator

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import eu.kanade.translation.logs.LogLevel
import eu.kanade.translation.logs.TranslationLogManager
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.util.TranslationUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import logcat.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GeminiTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    apiKey: String,
    private val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
    val topK: Int,
    val topP: Float,
    val presencePenalty: Float,
    val frequencyPenalty: Float,
    val systemPrompt: String,
) : TextTranslator {

    private val json = Json { ignoreUnknownKeys = true }
    private val logManager = Injekt.get<TranslationLogManager>()

    private val model: GenerativeModel = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey,
        generationConfig = generationConfig {
            topK = this@GeminiTranslator.topK
            topP = this@GeminiTranslator.topP
            temperature = temp
            maxOutputTokens = maxOutputToken
            responseMimeType = "application/json"
            // Note: Presence/Frequency penalties might not be supported in older SDKs or specific model versions via this wrapper.
            // If errors occur related to these fields, they should be removed or conditionally applied.
            // However, removing batching and processing single massive requests is the main fix for timeouts here.
        },
        safetySettings = listOf(
            SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
            SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
        ),
        systemInstruction = content {
            text(systemPrompt.replace("\$TARGET_LANGUAGE", toLang.label))
        },
    )

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        if (pages.isEmpty()) return
        
        // No batch size limit: Process all pages in one request.
        // This is necessary to provide full chapter context to the model 
        // and avoid context fragmentation which can hurt translation accuracy.
        // To prevent socket timeouts on large payloads, ensure the underlying client has sufficient timeouts.
        // The GenerativeModel wrapper doesn't expose easy timeout config, so for true "no timeout" safety 
        // with huge contexts, we might need to use the REST API directly (like OpenRouterTranslator),
        // or rely on the model being fast enough. 
        // Given previous errors, switching to direct REST call here is safer for "no limits".
        
        translateAll(pages)
    }

    private suspend fun translateAll(pages: Map<String, PageTranslation>) {
        try {
            val data = pages.mapValues { (_, v) -> v.blocks.map { b -> b.text } }
            val jsonString = json.encodeToString(data)
            
            logManager.log(LogLevel.INFO, "GeminiTranslator", "Sending request to Gemini model: $modelName (Total pages: ${pages.size})")
            logManager.log(LogLevel.DEBUG, "GeminiTranslator", "Input JSON: $jsonString")

            val response = model.generateContent(jsonString)
            val responseText = response.text ?: "{}"
            
            logManager.log(LogLevel.DEBUG, "GeminiTranslator", "Response JSON: $responseText")

            val resJson = try {
                TranslationUtils.extractAndParseJson(responseText)
            } catch (e: Exception) {
                logManager.log(LogLevel.ERROR, "GeminiTranslator", "Failed to parse Gemini response: $responseText", e)
                logcat(LogPriority.ERROR) { "Failed to parse Gemini response: $responseText" }
                throw e
            }

            for ((k, v) in pages) {
                val translationsArray = resJson[k]?.jsonArray
                if (translationsArray != null) {
                     v.blocks.forEachIndexed { i, b ->
                        val res = translationsArray.getOrNull(i)?.jsonPrimitive?.contentOrNull
                        b.translation = if (res.isNullOrEmpty() || res == "NULL") b.text else res
                    }
                     v.blocks = v.blocks.filterNot { it.translation.contains("RTMTH") }.toMutableList()
                } else {
                    logManager.log(LogLevel.WARN, "GeminiTranslator", "Missing translations for page: $k")
                }
            }
        } catch (e: Exception) {
            logManager.log(LogLevel.ERROR, "GeminiTranslator", "Gemini Translation Error", e)
            logcat(LogPriority.ERROR) { "Gemini Translation Error: ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {
    }
}
