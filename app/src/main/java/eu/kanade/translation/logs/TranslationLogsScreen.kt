package eu.kanade.translation.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun TranslationLogsScreen(
    navigateUp: () -> Unit,
) {
    val logManager = Injekt.get<TranslationLogManager>()
    val logs by logManager.logs.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            AppBar(
                title = stringResource(ATMR.strings.pref_logs_screen_title),
                navigateUp = navigateUp,
                actions = {
                    IconButton(onClick = {
                        val logText = logs.joinToString("\n") { 
                            "[${it.timestamp}] [${it.level}] [${it.tag}]: ${it.message} ${it.exception?.let { exc -> "| $exc" } ?: ""}" 
                        }
                        clipboardManager.setText(AnnotatedString(logText))
                        context.toast(ATMR.strings.logs_copied_to_clipboard)
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(ATMR.strings.action_copy_logs))
                    }
                    IconButton(onClick = {
                        logManager.clearLogs()
                        context.toast(ATMR.strings.logs_cleared)
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(ATMR.strings.action_clear_logs))
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            items(logs) { entry ->
                LogItem(entry)
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun LogItem(entry: TranslationLogEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            LogLevelBadge(entry.level)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = entry.tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (entry.exception != null) {
            Text(
                text = entry.exception,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f))
                    .padding(4.dp)
            )
        }
    }
}

@Composable
fun LogLevelBadge(level: LogLevel) {
    val (color, textRes) = when (level) {
        LogLevel.DEBUG -> Color.Gray to ATMR.strings.log_level_debug
        LogLevel.INFO -> Color.Green to ATMR.strings.log_level_info
        LogLevel.WARN -> Color.Yellow to ATMR.strings.log_level_warn
        LogLevel.ERROR -> Color.Red to ATMR.strings.log_level_error
    }
    
    Box(
        modifier = Modifier
            .background(color = color.copy(alpha = 0.2f), shape = MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(textRes),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp
        )
    }
}
