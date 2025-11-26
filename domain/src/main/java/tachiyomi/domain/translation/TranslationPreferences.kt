package tachiyomi.domain.translation

import tachiyomi.core.common.preference.PreferenceStore

class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun autoTranslateAfterDownload() = preferenceStore.getBoolean("auto_translate_after_download", false)
    fun translateFromLanguage() = preferenceStore.getString("translate_language_from", "CHINESE")
    fun translateToLanguage() = preferenceStore.getString("translate_language_to", "ENGLISH")
    fun translationFont() = preferenceStore.getInt("translation_font", 0)

    fun translationEngine() = preferenceStore.getInt("translation_engine", 0)
    fun translationEngineModel() = preferenceStore.getString("translation_engine_model", "gemini-2.0-flash")
    fun translationEngineApiKey() = preferenceStore.getString("translation_engine_api_key", "")
    fun translationEngineTemperature() = preferenceStore.getString("translation_engine_temperature", "1.0")
    fun translationEngineMaxOutputTokens() = preferenceStore.getString("translation_engine_output_tokens", "8192")
    fun translationEngineSystemPrompt() = preferenceStore.getString("translation_engine_system_prompt", "## System Prompt for Manhwa/Manga/Manhua Translation\n\nYou are a highly skilled AI tasked with translating text from scanned images of comics (manhwa, manga, manhua) while preserving the original structure and removing any watermarks or site links. \n\n**Here's how you should operate:**\n\n1. **Input:** You'll receive a JSON object where keys are image filenames (e.g., \"001.jpg\") and values are lists of text strings extracted from those images.\n\n2. **Translation:** Translate all text strings to the target language `\$TARGET_LANGUAGE`. Ensure the translation is natural and fluent, adapting idioms and expressions to fit the target language's cultural context.\n\n3. **Watermark/Site Link Removal:** Replace any watermarks or site links (e.g., \"colamanga.com\") with the placeholder \"RTMTH\".\n\n4. **Structure Preservation:** Maintain the exact same structure as the input JSON. The output JSON should have the same number of keys (image filenames) and the same number of text strings within each list.\n\n**Example:**\n\n**Input:**\n\n```json\n{\"001.jpg\":[\"chinese1\",\"chinese2\"],\"002.jpg\":[\"chinese2\",\"colamanga.com\"]}\n```\n\n**Output (for `\$TARGET_LANGUAGE` = English):**\n\n```json\n{\"001.jpg\":[\"eng1\",\"eng2\"],\"002.jpg\":[\"eng2\",\"RTMTH\"]}\n```\n\n**Key Points:**\n\n* Prioritize accurate and natural-sounding translations.\n* Be meticulous in removing all watermarks and site links.\n* Ensure the output JSON structure perfectly mirrors the input structure.\nReturn {[key:string]:Array<String>}")
}
