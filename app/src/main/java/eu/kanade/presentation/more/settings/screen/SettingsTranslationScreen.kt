package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
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
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var models by remember { mutableStateOf<List<String>?>(null) }
        
        // Updated default list for Gemini 2 and 3 series and modern models
        val defaultModels = listOf(
            "gemini-2.0-flash", 
            "gemini-2.0-flash-lite",
            "gemini-2.0-pro-exp-02-05",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
            "deepseek/deepseek-r1-distill-llama-70b:free",
            "deepseek/deepseek-r1-distill-llama-70b",
            "google/gemini-2.0-flash-lite-preview-02-05:free",
            "google/gemini-2.0-pro-exp-02-05:free",
            "deepseek/deepseek-r1:free",
            "google/gemini-2.0-flash-thinking-exp:free"
        )
        
        val apiKey = translationPreferences.translationEngineApiKey().get()
        val currentModel = translationPreferences.translationEngineModel().get()
        
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_advanced),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translationEngineModel(),
                    title = stringResource(ATMR.strings.pref_engine_model),
                    entries = (models ?: (if (defaultModels.contains(currentModel)) defaultModels else defaultModels + currentModel))
                        .associateWith { it }.toImmutableMap(),
                    onValueChanged = { true }
                ),
                 Preference.PreferenceItem.TextPreference(
                    title = stringResource(ATMR.strings.action_fetch_models),
                    subtitle = stringResource(ATMR.strings.pref_fetch_models_subtitle),
                    onClick = {
                        if (apiKey.isBlank()) {
                            context.toast(ATMR.strings.error_api_key_missing)
                        } else {
                            scope.launch {
                                try {
                                    context.toast(ATMR.strings.msg_fetching_models)
                                    val fetchedModels = fetchGeminiModels(apiKey)
                                    if (fetchedModels.isNotEmpty()) {
                                        models = fetchedModels
                                        context.toast(ATMR.strings.msg_models_fetched)
                                    } else {
                                        context.toast(ATMR.strings.error_no_models_found)
                                    }
                                } catch (e: Exception) {
                                    context.toast(ATMR.strings.error_fetching_models)
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineTemperature(),
                    title = stringResource(ATMR.strings.pref_engine_temperature),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineTopK(),
                    title = stringResource(ATMR.strings.pref_engine_top_k),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineTopP(),
                    title = stringResource(ATMR.strings.pref_engine_top_p),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEnginePresencePenalty(),
                    title = stringResource(ATMR.strings.pref_engine_presence_penalty),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineFrequencyPenalty(),
                    title = stringResource(ATMR.strings.pref_engine_frequency_penalty),
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

    private suspend fun fetchGeminiModels(apiKey: String): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                    .build()
                
                val response = client.newCall(request).execute()
                val body = response.body.string()
                
                if (!response.isSuccessful) return@withContext emptyList()
                
                val json = Json { ignoreUnknownKeys = true }
                val element = json.parseToJsonElement(body)
                val modelsArray = element.jsonObject["models"]?.jsonArray
                
                modelsArray?.mapNotNull { 
                    it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
                }?.filter { it.contains("gemini") } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
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
