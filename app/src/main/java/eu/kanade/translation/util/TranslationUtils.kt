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
        
        val start = text.indexOf('{')
        if (start == -1) return text.trim()
        
        // Brace counting to find the matching closing brace
        var braceCount = 0
        var inString = false
        var isEscaped = false
        
        for (i in start until text.length) {
            val char = text[i]
            
            if (isEscaped) {
                isEscaped = false
                continue
            }
            
            if (char == '\\') {
                isEscaped = true
                continue
            }
            
            if (char == '"') {
                inString = !inString
                continue
            }
            
            if (!inString) {
                if (char == '{') {
                    braceCount++
                } else if (char == '}') {
                    braceCount--
                    if (braceCount == 0) {
                        return text.substring(start, i + 1)
                    }
                }
            }
        }
        
        // Fallback: look for the last } if counting failed (e.g. malformed or unbalanced)
        val end = text.lastIndexOf('}')
        if (end != -1 && start < end) {
            return text.substring(start, end + 1)
        }
        
        return text.substring(start)
    }
}
