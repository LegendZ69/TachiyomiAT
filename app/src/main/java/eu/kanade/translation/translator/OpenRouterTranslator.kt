package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.logs.LogLevel
import eu.kanade.translation.logs.TranslationLogManager
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

class OpenRouterTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    val apiKey: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
    val systemPrompt: String,
) : TextTranslator {
    
    private val okHttpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val logManager = Injekt.get<TranslationLogManager>()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        if (pages.isEmpty()) return

        try {
            val data = pages.mapValues { (_, v) -> v.blocks.map { b -> b.text } }
            val jsonString = json.encodeToString(data)
            
            logManager.log(LogLevel.INFO, "OpenRouterTranslator", "Sending request to OpenRouter model: $modelName")
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
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).await()
            val responseBody = response.body.string()
            
            logManager.log(LogLevel.DEBUG, "OpenRouterTranslator", "Response Body: $responseBody")

            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            val contentString = responseJson["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            
            if (contentString != null) {
                val resJson = try {
                    json.parseToJsonElement(contentString).jsonObject
                } catch (e: Exception) {
                    logManager.log(LogLevel.ERROR, "OpenRouterTranslator", "Failed to parse OpenRouter response content: $contentString", e)
                    logcat(LogPriority.ERROR) { "Failed to parse OpenRouter response content: $contentString" }
                    throw e
                }

                for ((k, v) in pages) {
                    val translationsArray = resJson[k]?.jsonArray
                    v.blocks.forEachIndexed { i, b ->
                        val res = translationsArray?.getOrNull(i)?.jsonPrimitive?.contentOrNull
                        b.translation = if (res.isNullOrEmpty() || res == "NULL") b.text else res
                    }
                    v.blocks = v.blocks.filterNot { it.translation.contains("RTMTH") }.toMutableList()
                }
            } else {
                 logManager.log(LogLevel.WARN, "OpenRouterTranslator", "Content string is null in response")
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
