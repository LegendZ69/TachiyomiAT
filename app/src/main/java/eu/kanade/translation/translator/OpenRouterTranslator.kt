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

class OpenRouterTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    val apiKey: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
    val systemPrompt: String,
) : TextTranslator {
    
    // Increased timeout for long-running API calls
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
        
    private val json = Json { ignoreUnknownKeys = true }
    private val logManager = Injekt.get<TranslationLogManager>()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        if (pages.isEmpty()) return

        // Batch processing to avoid timeouts and token limits
        val batchSize = 5
        val pageEntries = pages.entries.toList()

        for (batch in pageEntries.chunked(batchSize)) {
            val batchMap = batch.associate { it.key to it.value }
            translateBatch(batchMap)
        }
    }

    private suspend fun translateBatch(pages: Map<String, PageTranslation>) {
        try {
            val data = pages.mapValues { (_, v) -> v.blocks.map { b -> b.text } }
            val jsonString = json.encodeToString(data)
            
            logManager.log(LogLevel.INFO, "OpenRouterTranslator", "Sending request to OpenRouter model: $modelName (Batch size: ${pages.size})")
            logManager.log(LogLevel.DEBUG, "OpenRouterTranslator", "Input JSON: $jsonString")

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBodyJson = buildJsonObject {
                put("model", modelName)
                putJsonObject("response_format") { put("type", "json_object") }
                put("top_p", 0.5f)
                put("top_k", 30)
                put("temperature", temp)
                put("max_tokens", maxOutputToken)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put(
                            "content",
                            systemPrompt.replace("\$TARGET_LANGUAGE", toLang.label)
                        )
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", jsonString)
                    }
                }
            }

            val body = requestBodyJson.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://mihon.app") // Recommended by OpenRouter
                .header("X-Title", "Mihon") // Recommended by OpenRouter
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).await()
            val responseBody = response.body.string()
            
            logManager.log(LogLevel.DEBUG, "OpenRouterTranslator", "Response Body: $responseBody")

            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            val contentString = responseJson["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            
            if (contentString != null) {
                val resJson = try {
                    TranslationUtils.extractAndParseJson(contentString)
                } catch (e: Exception) {
                    logManager.log(LogLevel.ERROR, "OpenRouterTranslator", "Failed to parse OpenRouter response content: $contentString", e)
                    logcat(LogPriority.ERROR) { "Failed to parse OpenRouter response content: $contentString" }
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
                         logManager.log(LogLevel.WARN, "OpenRouterTranslator", "Missing translations for page: $k")
                    }
                }
            } else {
                 logManager.log(LogLevel.WARN, "OpenRouterTranslator", "Content string is null in response")
                 if (responseJson.containsKey("error")) {
                      val error = responseJson["error"]?.jsonObject
                      val message = error?.get("message")?.jsonPrimitive?.contentOrNull
                      logManager.log(LogLevel.ERROR, "OpenRouterTranslator", "API Error: $message")
                 }
            }
        } catch (e: Exception) {
            logManager.log(LogLevel.ERROR, "OpenRouterTranslator", "OpenRouter Translation Error", e)
            logcat(LogPriority.ERROR) { "OpenRouter Translation Error: ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {
    }
}
