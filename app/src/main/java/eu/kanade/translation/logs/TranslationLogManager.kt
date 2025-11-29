package eu.kanade.translation.logs

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TranslationLogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val exception: String? = null
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR;

    fun toLogPriority(): LogPriority = when (this) {
        DEBUG -> LogPriority.DEBUG
        INFO -> LogPriority.INFO
        WARN -> LogPriority.WARN
        ERROR -> LogPriority.ERROR
    }
}

class TranslationLogManager(private val context: Context) {
    
    private val _logs = MutableStateFlow<List<TranslationLogEntry>>(emptyList())
    val logs = _logs.asStateFlow()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val logFile: File by lazy {
        File(context.filesDir, "translation_logs.txt")
    }
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val maxLogLines = 2000 // Limit memory logs
    private val maxFileSize = 2 * 1024 * 1024L // 2 MB
    
    init {
        scope.launch {
            loadLogs()
        }
    }

    private suspend fun loadLogs() = withContext(Dispatchers.IO) {
        if (logFile.exists()) {
            try {
                // Read last N lines if file is big
                val entries = mutableListOf<TranslationLogEntry>()
                val lines = logFile.readLines()
                val start = (lines.size - maxLogLines).coerceAtLeast(0)
                
                for (i in start until lines.size) {
                    parseLogLine(lines[i])?.let { entries.add(it) }
                }
                
                _logs.value = entries.reversed() // Show newest first
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Failed to load logs: ${e.message}" }
            }
        }
    }

    private fun parseLogLine(line: String): TranslationLogEntry? {
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
        // Also log to system logcat
        logcat(level.toLogPriority()) { "[$tag] $message" }
        if (throwable != null) {
            logcat(level.toLogPriority()) { throwable.stackTraceToString() }
        }

        scope.launch {
            val timestamp = dateFormat.format(Date())
            
            val entry = TranslationLogEntry(
                timestamp = timestamp,
                level = level,
                tag = tag,
                message = message,
                exception = throwable?.stackTraceToString()
            )
            
            _logs.update { 
                val newList = listOf(entry) + it
                if (newList.size > maxLogLines) newList.take(maxLogLines) else newList
            }
            
            appendLogToFile(entry)
        }
    }

    private suspend fun appendLogToFile(entry: TranslationLogEntry) = withContext(Dispatchers.IO) {
        try {
            if (logFile.exists() && logFile.length() > maxFileSize) {
                val oldFile = File(context.filesDir, "translation_logs.old")
                if (oldFile.exists()) oldFile.delete()
                logFile.renameTo(oldFile)
            }
            
            val logLine = buildString {
                append("[${entry.timestamp}] [${entry.level}] [${entry.tag}]: ${entry.message}")
                if (entry.exception != null) {
                    append(" | ${entry.exception}")
                }
                append("\n")
            }
            logFile.appendText(logLine)
        } catch (e: Exception) {
            // Fails silently
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        scope.launch(Dispatchers.IO) {
            if (logFile.exists()) {
                logFile.delete()
            }
            val oldFile = File(context.filesDir, "translation_logs.old")
            if (oldFile.exists()) {
                oldFile.delete()
            }
        }
    }
}
