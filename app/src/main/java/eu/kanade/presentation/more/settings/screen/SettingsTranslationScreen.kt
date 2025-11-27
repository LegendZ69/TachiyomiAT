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
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.system.toast
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
        
        // This is a temporary simple model listing if dynamic fetching fails or isn't triggered
        val defaultModels = listOf(
            "gemini-3-pro-preview",
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite", 
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
                                    // Gemini doesn't have a direct "list models" API in the simple GenerativeModel client easily accessible 
                                    // without the full Google AI SDK or making a raw HTTP request.
                                    // However, we can simulate or we'd need to implement a manual fetcher.
                                    // For now, let's allow users to type it in if they want, OR switch back to EditText if listing fails.
                                    // But to fulfill "allow user to choose from list of available models", we should likely fetch it.
                                    // Since we don't have a direct fetcher in the codebase, we'll implement a basic one via REST if possible or fallback.
                                    // For Gemini specifically, it's GET https://generativelanguage.googleapis.com/v1beta/models?key=API_KEY
                                    
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

    // Helper to fetch Gemini models using OkHttp or similar if available, otherwise just use a hardcoded list update
    // Since we don't have a network client exposed here easily, we'll use a simpler approach or rely on what's available.
    // Assuming we can use OkHttp since it's in the project.
    private suspend fun fetchGeminiModels(apiKey: String): List<String> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                    .build()
                
                val response = client.newCall(request).execute()
                val body = response.body.string()
                
                if (!response.isSuccessful) return@withContext emptyList()
                
                // Parse JSON manually or using regex for simplicity to avoid huge dependency usage here if not needed
                // Structure: { "models": [ { "name": "models/gemini-pro", ... } ] }
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val element = json.parseToJsonElement(body)
                val modelsArray = element.kotlinx.serialization.json.jsonObject["models"]?.kotlinx.serialization.json.jsonArray
                
                modelsArray?.mapNotNull { 
                    it.kotlinx.serialization.json.jsonObject["name"]?.kotlinx.serialization.json.jsonPrimitive?.content?.removePrefix("models/")
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
