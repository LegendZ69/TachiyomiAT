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
        pages.mapValues { (_, v) ->
            v.blocks.map { b ->
                if (b.text.isNotBlank()) {
                    val translated = translateText(toLang.code, b.text)
                    b.translation = if (translated.isNotBlank()) translated else b.text
                }
            }
        }
    }

    private suspend fun translateText(lang: String, text: String): String {
        try {
            val access = getTranslateUrl(lang, text)
            logManager.log(LogLevel.DEBUG, "GoogleTranslator", "Translating text: ${text.take(20)}...")
            
            val request = Request.Builder().url(access).build()
            val response = okHttpClient.newCall(request).await()
            val body = response.body
            val string = body.string()
            
            val jsonArray = JSONArray(string)
            val sentences = jsonArray.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                 val sentence = sentences.getJSONArray(i)
                 // The first element is usually the translated text
                 if (sentence.length() > 0) {
                     sb.append(sentence.getString(0))
                 }
            }
            return sb.toString()

        } catch (e: Exception) {
            logManager.log(LogLevel.ERROR, "GoogleTranslator", "Google Translation Error for text: ${text.take(20)}...", e)
            logcat { "Google Translation Error: $e" }
        }
        return ""
    }

    private fun getTranslateUrl(lang: String, text: String): String {
        try {
            val encodedText = URLEncoder.encode(text, "utf-8")
            return "https://translate.googleapis.com/translate_a/single?client=$client1&sl=auto&tl=$lang&dt=t&q=$encodedText"
        } catch (e: UnsupportedEncodingException) {
             val encodedText = URLEncoder.encode(text, "utf-8")
             return "https://translate.googleapis.com/translate_a/single?client=$client1&sl=auto&tl=$lang&dt=t&q=$encodedText"
        }
    }
    
    override fun close() {
    }
}
