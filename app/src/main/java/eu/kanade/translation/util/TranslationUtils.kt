package eu.kanade.translation.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object TranslationUtils {
    private val json = Json { ignoreUnknownKeys = true }

    fun extractAndParseJson(text: String): JsonObject {
        val cleanJson = extractJsonString(text)
        return json.parseToJsonElement(cleanJson) as JsonObject
    }

    private fun extractJsonString(text: String): String {
        // Try matching markdown code blocks first
        val jsonPattern = Regex("```json(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val match = jsonPattern.find(text)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        
        // Fallback: look for the first { and last }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end != -1 && start < end) {
            return text.substring(start, end + 1)
        }
        
        // Return original if no structure found, let parser fail
        return text.trim()
    }
}
