package eu.kanade.translation.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object TranslationUtils {
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }

    fun extractAndParseJson(text: String): JsonObject {
        val cleanJson = extractJsonString(text)
        return json.parseToJsonElement(cleanJson) as JsonObject
    }

    private fun extractJsonString(text: String): String {
        // 1. Try matching markdown code blocks (most reliable for LLMs)
        val jsonPattern = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)
        val match = jsonPattern.find(text)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        
        // 2. Locate the outermost braces
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        
        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1)
        }
        
        // 3. Fallback: Return original text (will likely fail parsing if not JSON)
        return text.trim()
    }
}
