package app.shilex.android.control

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import app.shilex.android.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Permitted visual image understanding via Gemini Flash: used ONLY when the user asks to
 * understand what is shown in an open image/photo/screen and either approves screen capture or
 * selects the photo explicitly through Android's system Photo Picker. Never runs on blocked
 * or banking/UPI screens.
 */
class ScreenVision(private val http: OkHttpClient, private val apiKey: String) {
    sealed interface Outcome {
        data class Described(val summary: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun describeBitmap(bitmap: Bitmap, question: String): Outcome = withContext(Dispatchers.IO) {
        val scaled = scaleDown(bitmap)
        val encoded = Base64.encodeToString(jpeg(scaled), Base64.NO_WRAP)
        val prompt = buildString {
            append("You are helping a mobile voice assistant describe a visible image or photo for the user. ")
            append("Be accurate, concise (2-4 sentences), describe only what is clearly visible, and do not invent details. ")
            if (question.isNotBlank()) append("User question: ").append(question)
            else append("Describe the photo, objects, people/scene (without guessing identities), and any visible readable text.")
        }
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", encoded)))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.2))
        val request = Request.Builder()
            .url("$ENDPOINT/$MODEL:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Diag.w("vision: HTTP ${response.code}")
                    return@withContext Outcome.Failed("Vision analysis returned HTTP ${response.code}.")
                }
                val text = JSONObject(response.body?.string().orEmpty())
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .optJSONObject(0)?.optString("text")?.trim().orEmpty()
                if (text.isEmpty()) {
                    Outcome.Failed("Gemini returned an empty image description.")
                } else {
                    Outcome.Described(text)
                }
            }
        }.getOrElse {
            Diag.w("vision: ${it.javaClass.simpleName}")
            Outcome.Failed("Could not analyze that image right now.")
        }
    }

    suspend fun describeUri(context: Context, uri: Uri, question: String): Outcome = withContext(Dispatchers.IO) {
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        }.getOrNull() ?: return@withContext Outcome.Failed("Could not read the selected photo.")
        describeBitmap(bitmap, question)
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height).toFloat()
        if (maxDim <= MAX_SIDE) return bitmap
        val scale = MAX_SIDE / maxDim
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun jpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()

    private companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        const val MODEL = "gemini-flash-latest"
        const val MAX_SIDE = 1280f
    }
}
