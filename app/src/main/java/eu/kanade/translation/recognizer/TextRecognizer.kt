package eu.kanade.translation.recognizer

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

class TextRecognizer(val language: TextRecognizerLanguage) : Closeable {

    private val recognizer = TextRecognition.getClient(
        when (language) {
            TextRecognizerLanguage.ENGLISH -> TextRecognizerOptions.DEFAULT_OPTIONS
            TextRecognizerLanguage.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            TextRecognizerLanguage.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            TextRecognizerLanguage.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
        },
    )

    fun recognize(image: InputImage): Text {
        // Adding a timeout can prevent hanging if the recognizer gets stuck, though Tasks.await is blocking.
        // For production, consider using suspendCancellableCoroutine with Tasks.addOnSuccessListener/addOnFailureListener
        return try {
            Tasks.await(recognizer.process(image), 30, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw Exception("Text recognition timed out", e)
        }
    }

    override fun close() {
        recognizer.close()
    }
}
