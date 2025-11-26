package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsTranslationScreen : SearchableSettings {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = ATMR.strings.pref_category_translations

    @Composable
    override fun getPreferences(): List<Preference> {
        val entries = TranslationFont.entries
        val translationPreferences = remember { Injekt.get<TranslationPreferences>() }
        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                pref = translationPreferences.autoTranslateAfterDownload(),
                title = stringResource(ATMR.strings.pref_translate_after_downloading),
            ),
            Preference.PreferenceItem.ListPreference(
                pref = translationPreferences.translationFont(),
                title = stringResource(ATMR.strings.pref_reader_font),
                entries = entries.withIndex().associate { it.index to it.value.label }.toImmutableMap(),
            ),
            getTranslationLangGroup(translationPreferences),
            getTranslatioEngineGroup(translationPreferences),
            getTranslatioAdvancedGroup(translationPreferences),
            getLogsGroup(),
        )
    }

    @Composable
    private fun getTranslationLangGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        val fromLangs = TextRecognizerLanguage.entries
        val toLangs = TextTranslatorLanguage.entries
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_setup),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translateFromLanguage(),
                    title = stringResource(ATMR.strings.pref_translate_from),
                    entries = fromLangs.associate { it.name to it.label }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translateToLanguage(),
                    title = stringResource(ATMR.strings.pref_translate_to),
                    entries = toLangs.associate { it.name to it.label }.toImmutableMap(),
                ),
            ),
        )
    }

    @Composable
    private fun getTranslatioEngineGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        val engines = TextTranslators.entries
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_engine),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translationEngine(),
                    title = stringResource(ATMR.strings.pref_translator_engine),
                    entries = engines.withIndex().associate { it.index to it.value.label }.toImmutableMap(),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineApiKey(),
                    subtitle = stringResource(ATMR.strings.pref_sub_engine_api_key),
                    title = stringResource(ATMR.strings.pref_engine_api_key),
                ),
            ),
        )
    }

    @Composable
    private fun getTranslatioAdvancedGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_advanced),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineModel(),
                    title = stringResource(ATMR.strings.pref_engine_model),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineTemperature(),
                    title = stringResource(ATMR.strings.pref_engine_temperature),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineMaxOutputTokens(),
                    title = stringResource(ATMR.strings.pref_engine_max_output),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineSystemPrompt(),
                    title = stringResource(ATMR.strings.pref_engine_system_prompt),
                ),
            ),
        )
    }

    @Composable
    private fun getLogsGroup(): Preference.PreferenceGroup {
        val navigator = LocalNavigator.currentOrThrow
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_logs),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(ATMR.strings.pref_view_logs),
                    onClick = {
                        navigator.push(SettingsTranslationLogsScreen)
                    },
                ),
            ),
        )
    }
}
