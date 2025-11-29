package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.logs.LogLevel
import eu.kanade.translation.logs.TranslationLogManager
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.util.TranslationUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class GeminiTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    val apiKey: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
    val topK: Int,
    val topP: Float,
    val presencePenalty: Float,
    val frequencyPenalty: Float,
    val systemPrompt: String,
) : TextTranslator {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.MINUTES)
        .readTimeout(6, TimeUnit.MINUTES)
        .writeTimeout(6, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val logManager = Injekt.get<TranslationLogManager>()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        if (pages.isEmpty()) return

        try {
            val data = pages.mapValues { (_, v) -> v.blocks.map { b -> b.text } }
            val jsonString = json.encodeToString(data)
            
            logManager.log(LogLevel.INFO, "GeminiTranslator", "Preparing translation for ${pages.size} pages using $modelName")
            logManager.log(LogLevel.DEBUG, "GeminiTranslator", "Input Data Size: ${jsonString.length} chars")

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBodyJson = buildJsonObject {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", systemPrompt.replace("\$TARGET_LANGUAGE", toLang.label))
                        }
                    }
                }
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") {
                            addJsonObject {
                                put("text", jsonString)
                            }
                        }
                    }
                }
                putJsonObject("generationConfig") {
                    put("temperature", temp)
                    put("topK", topK)
                    put("topP", topP)
                    put("maxOutputTokens", maxOutputToken)
                    put("responseMimeType", "application/json")
                    put("presencePenalty", presencePenalty)
                    put("frequencyPenalty", frequencyPenalty)
                }
                putJsonArray("safetySettings") {
                    listOf(
                        "HARM_CATEGORY_HARASSMENT",
                        "HARM_CATEGORY_HATE_SPEECH",
                        "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                        "HARM_CATEGORY_DANGEROUS_CONTENT"
                    ).forEach { category ->
                        addJsonObject {
                            put("category", category)
                            put("threshold", "BLOCK_NONE")
                        }
                    }
                }
            }

            val body = requestBodyJson.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey")
                .post(body)
                .build()

            logManager.log(LogLevel.INFO, "GeminiTranslator", "Sending request...")
            val startTime = System.currentTimeMillis()
            val response = okHttpClient.newCall(request).await()
            val endTime = System.currentTimeMillis()
            val duration = (endTime - startTime) / 1000.0
            
            logManager.log(LogLevel.INFO, "GeminiTranslator", "Response received. Status: ${response.code}. Time: ${duration}s")
            
            val responseBody = response.body.string()
            logManager.log(LogLevel.DEBUG, "GeminiTranslator", "Raw Response Body: $responseBody")

            if (!response.isSuccessful) {
                logManager.log(LogLevel.ERROR, "GeminiTranslator", "HTTP Request Failed: ${response.code} - $responseBody")
                throw Exception("Gemini API Error ${response.code}")
            }

            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            val candidates = responseJson["candidates"]?.jsonArray
            
            if (candidates.isNullOrEmpty()) {
                logManager.log(LogLevel.ERROR, "GeminiTranslator", "No candidates returned. Safety ratings or error?")
                if (responseJson.containsKey("promptFeedback")) {
                    logManager.log(LogLevel.WARN, "GeminiTranslator", "Prompt Feedback: ${responseJson["promptFeedback"]}")
                }
                // Try to throw more descriptive error
                throw Exception("No translation candidates returned. Check logs for details.")
            }

            val contentString = candidates[0].jsonObject
                .get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.get(0)?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull

            if (contentString != null) {
                val resJson = try {
                    TranslationUtils.extractAndParseJson(contentString)
                } catch (e: Exception) {
                    logManager.log(LogLevel.ERROR, "GeminiTranslator", "Failed to parse JSON from response content: $contentString", e)
                    throw e
                }

                var successCount = 0
                for ((k, v) in pages) {
                    val translationsArray = resJson[k]?.jsonArray
                    if (translationsArray != null) {
                         v.blocks.forEachIndexed { i, b ->
                            val res = translationsArray.getOrNull(i)?.jsonPrimitive?.contentOrNull
                            b.translation = if (res.isNullOrEmpty() || res == "NULL") b.text else res
                        }
                         // Filter out "RTMTH" or similar artifacts if your logic requires it.
                         // Keeping it as is from user code, assuming RTMTH is some placeholder used in prompts or errors.
                         v.blocks = v.blocks.filterNot { it.translation.contains("RTMTH") }.toMutableList()
                         successCount++
                    } else {
                        logManager.log(LogLevel.WARN, "GeminiTranslator", "No translation found for page key: $k")
                    }
                }
                logManager.log(LogLevel.INFO, "GeminiTranslator", "Successfully applied translations to $successCount pages")
            } else {
                logManager.log(LogLevel.ERROR, "GeminiTranslator", "Content string is null in candidate")
                throw Exception("Empty content in response")
            }
        } catch (e: Exception) {
            logManager.log(LogLevel.ERROR, "GeminiTranslator", "Translation Process Error", e)
            logcat(LogPriority.ERROR) { "Gemini Translation Error: ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {
    }
}
