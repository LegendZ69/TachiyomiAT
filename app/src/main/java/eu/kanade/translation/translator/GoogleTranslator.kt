package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.logs.LogLevel
import eu.kanade.translation.logs.TranslationLogManager
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

class GoogleTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {
    private val client1 = "gtx"
    private val okHttpClient = OkHttpClient()
    private val logManager = Injekt.get<TranslationLogManager>()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // Collect all texts to translate first to maybe batch them? 
        // Google Translate free API (gtx) is limited and requests should be kept minimal.
        // However, batching might be complex due to response parsing. 
        // Stick to per-block translation but with some error handling/delay if needed.
        
        for ((_, page) in pages) {
            for (block in page.blocks) {
                if (block.text.isNotBlank()) {
                     // Adding a small delay might help avoid rate limits if we were processing many blocks fast
                     // delay(100) 
                     val translated = translateText(toLang.code, block.text)
                     block.translation = if (translated.isNotBlank()) translated else block.text
                }
            }
        }
    }

    private suspend fun translateText(lang: String, text: String): String {
        try {
            val encodedText = URLEncoder.encode(text, "utf-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=$client1&sl=auto&tl=$lang&dt=t&q=$encodedText"
            
            // logManager.log(LogLevel.DEBUG, "GoogleTranslator", "Translating: ${text.take(15)}...")
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build()
            
            val response = okHttpClient.newCall(request).await()
            val body = response.body
            val string = body.string()
            
            if (!response.isSuccessful) {
                 logManager.log(LogLevel.WARN, "GoogleTranslator", "Response not successful: ${response.code}")
                 return ""
            }

            val jsonArray = JSONArray(string)
            val sentences = jsonArray.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                 val sentence = sentences.getJSONArray(i)
                 if (sentence.length() > 0) {
                     val segment = sentence.getString(0)
                     if (segment != "null") {
                        sb.append(segment)
                     }
                 }
            }
            return sb.toString()

        } catch (e: Exception) {
            logManager.log(LogLevel.ERROR, "GoogleTranslator", "Google Translation Error for text: ${text.take(20)}...", e)
            logcat { "Google Translation Error: $e" }
        }
        return ""
    }
    
    override fun close() {
    }
}
