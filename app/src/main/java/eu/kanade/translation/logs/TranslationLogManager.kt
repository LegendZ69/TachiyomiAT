package eu.kanade.translation.logs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class TranslationLogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val exception: String? = null
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

class TranslationLogManager(private val context: Context) {
    
    private val _logs = MutableStateFlow<List<TranslationLogEntry>>(emptyList())
    val logs = _logs.asStateFlow()
    
    private val logFile: File by lazy {
        File(context.filesDir, "translation_logs.txt")
    }
    
    init {
        loadLogs()
    }

    private fun loadLogs() {
        if (logFile.exists()) {
            try {
                val entries = logFile.readLines().mapNotNull { parseLogLine(it) }
                _logs.value = entries.reversed() // Show newest first
            } catch (e: Exception) {
                logcat { "Failed to load logs: ${e.message}" }
            }
        }
    }

    private fun parseLogLine(line: String): TranslationLogEntry? {
        // Simple parser, adjust based on how you write logs
        // Format: [TIMESTAMP] [LEVEL] [TAG]: Message | Exception
        return try {
            val parts = line.split(" | ", limit = 2)
            val metaAndMsg = parts[0]
            val exception = parts.getOrNull(1)
            
            val timestampEnd = metaAndMsg.indexOf(']')
            val levelEnd = metaAndMsg.indexOf(']', timestampEnd + 1)
            val tagEnd = metaAndMsg.indexOf(':', levelEnd + 1)
            
            if (timestampEnd == -1 || levelEnd == -1 || tagEnd == -1) return null

            val timestamp = metaAndMsg.substring(1, timestampEnd)
            val levelStr = metaAndMsg.substring(timestampEnd + 3, levelEnd)
            val tag = metaAndMsg.substring(levelEnd + 3, tagEnd)
            val message = metaAndMsg.substring(tagEnd + 2)
            
            TranslationLogEntry(
                timestamp = timestamp,
                level = LogLevel.valueOf(levelStr),
                tag = tag,
                message = message,
                exception = exception
            )
        } catch (e: Exception) {
            null
        }
    }

    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        val currentMoment = Clock.System.now()
        val datetime = currentMoment.toLocalDateTime(TimeZone.currentSystemDefault())
        val timestamp = "${datetime.date} ${datetime.time.toString().take(8)}"
        
        val entry = TranslationLogEntry(
            timestamp = timestamp,
            level = level,
            tag = tag,
            message = message,
            exception = throwable?.stackTraceToString()
        )
        
        _logs.update { listOf(entry) + it }
        
        // Write to file
        appendLogToFile(entry)
    }

    private fun appendLogToFile(entry: TranslationLogEntry) {
        try {
            val logLine = buildString {
                append("[${entry.timestamp}] [${entry.level}] [${entry.tag}]: ${entry.message}")
                if (entry.exception != null) {
                    append(" | ${entry.exception}")
                }
                append("\n")
            }
            logFile.appendText(logLine)
        } catch (e: Exception) {
            // Fails silently to avoid infinite recursion if logging fails
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        if (logFile.exists()) {
            logFile.delete()
        }
    }
}
