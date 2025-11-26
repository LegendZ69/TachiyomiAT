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
    modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
    val systemPrompt: String,
) : TextTranslator {

    private val json = Json { ignoreUnknownKeys = true }
    private val logManager = Injekt.get<TranslationLogManager>()

    private val model: GenerativeModel = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey,
        generationConfig = generationConfig {
            topK = 30
            topP = 0.5f
            temperature = temp
            maxOutputTokens = maxOutputToken
            responseMimeType = "application/json"
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

        try {
            val data = pages.mapValues { (_, v) -> v.blocks.map { b -> b.text } }
            val jsonString = json.encodeToString(data)
            
            // Accessing modelName property of GenerativeModel is not directly available or might be private/internal depending on version.
            // Using the class property 'modelName' passed in constructor instead.
            logManager.log(LogLevel.INFO, "GeminiTranslator", "Sending request to Gemini model: $modelName")
            logManager.log(LogLevel.DEBUG, "GeminiTranslator", "Input JSON: $jsonString")

            val response = model.generateContent(jsonString)
            val responseText = response.text ?: "{}"
            
            logManager.log(LogLevel.DEBUG, "GeminiTranslator", "Response JSON: $responseText")

            val resJson = try {
                json.parseToJsonElement(responseText) as? JsonObject
            } catch (e: Exception) {
                logManager.log(LogLevel.ERROR, "GeminiTranslator", "Failed to parse Gemini response: $responseText", e)
                logcat(LogPriority.ERROR) { "Failed to parse Gemini response: $responseText" }
                // Try a cleaner approach to extract JSON if it's wrapped in markdown code blocks
                val cleanResponse = responseText.trim().removePrefix("```json").removeSuffix("```").trim()
                 try {
                    json.parseToJsonElement(cleanResponse) as? JsonObject
                } catch (e2: Exception) {
                    throw e
                }
            }

            if (resJson != null) {
                for ((k, v) in pages) {
                    val translationsArray = resJson[k]?.jsonArray
                    if (translationsArray != null) {
                         v.blocks.forEachIndexed { i, b ->
                            val res = translationsArray.getOrNull(i)?.jsonPrimitive?.contentOrNull
                            b.translation = if (res.isNullOrEmpty() || res == "NULL") b.text else res
                        }
                        // Filter out RTMTH or other markers if necessary, though ideally the prompt should handle this.
                         v.blocks = v.blocks.filterNot { it.translation.contains("RTMTH") }.toMutableList()
                    } else {
                        logManager.log(LogLevel.WARN, "GeminiTranslator", "Missing translations for page: $k")
                    }
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
