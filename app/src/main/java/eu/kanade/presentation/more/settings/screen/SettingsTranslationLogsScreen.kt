package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.translation.logs.TranslationLogsScreen

object SettingsTranslationLogsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        TranslationLogsScreen(
            navigateUp = navigator::pop,
        )
    }
}
