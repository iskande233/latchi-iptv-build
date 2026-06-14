package com.latchi.iptv.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiVoiceController {

    // 🔑 User's embedded Gemini API Keys (With Automatic Failover)
    // 🔒 Keys intentionally left empty for public repo safety.
    // Provide keys here (or via secure config) to enable Gemini smart parsing.
    // When empty, the engine silently falls back to the fast local VoiceCommandParser.
    private val GEMINI_KEYS = emptyList<String>()

    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key="

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class VoiceAction(
        val actionType: String,
        val target: String,
        val screen: String?,
        val extra: String = "",
        val confidence: Float = 1.0f
    ) {
        fun isConfident(): Boolean = confidence >= 0.5f
    }

    fun processVoiceCommand(
        context: Context,
        voiceText: String,
        onResult: (VoiceAction) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            val prompt = buildDarjaPrompt(voiceText)
            val requestBody = buildGeminiRequestBody(prompt)
            var successAction: VoiceAction? = null

            // 🔄 Automatic Silent Multi-Key Failover Engine
            for (apiKey in GEMINI_KEYS) {
                try {
                    val request = Request.Builder()
                        .url(GEMINI_URL + apiKey.trim())
                        .header("Content-Type", "application/json")
                        .post(requestBody)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (!body.isNullOrBlank()) {
                                val action = parseGeminiResponse(body, voiceText)
                                if (action.actionType != "unknown" || action.target.isNotBlank()) {
                                    successAction = action
                                    return@use
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GeminiVoice", "Key failover triggered: ${e.message}")
                }
                if (successAction != null) break
            }

            // ⚡ Fully silent fallback to our lightning-fast VoiceCommandParser if both keys hit quota limits
            val finalAction = successAction ?: convertToGeminiAction(VoiceCommandParser.parse(voiceText))
            Handler(Looper.getMainLooper()).post { onResult(finalAction) }
        }.start()
    }

    private fun buildDarjaPrompt(voiceText: String): String {
        return """
أنت مساعد ذكي مدمج في تطبيق "Latchi" لخدمات IPTV.
المستخدم سيتحدث معك بالدارجة الجزائرية، العربية، الفرنسية، أو الإنجليزية.

مهمتك الوحيدة هي فهم نية المستخدم وتحويلها إلى كائن JSON واحد فقط يطابق الهيكلة المطلوبة بدقة، بدون أي نص إضافي قبل أو بعد الـ JSON، وبدون استخدام علامات التنسيق.

كلام المستخدم:
[ ${voiceText} ]

الهيكلة الإلزامية للـ JSON:
{
  "actionType": "play|search|navigate|category|favorites|home|player_control|info|unknown",
  "target": "اسم القناة/الفيلم/الفئة، أو نوع التحكم مثل pause,next_channel,volume_up",
  "screen": "live|movies|series|matches|settings|users|prayer|pricing|about|null",
  "extra": "live|movie|series|عدد الثواني| أو اتركها فارغة",
  "confidence": 0.95
}

قواعد تعبئة الـ JSON:
- actionType: play للتشغيل المباشر، search للبحث، navigate لفتح الشاشات، category للفئات، favorites للمفضلة، home للرئيسية، player_control للتحكم في الفيديو والصوت.
- screen: استخدمها فقط مع navigate، وإلا اجعلها null.
- extra: استخدمها لتحديد النوع live/movie/series أو عدد الثواني في التقديم/التأخير.

أمثلة دارجة جزائرية مهمة:
"ديرلي بي ان سبورت واحد" => {"actionType":"play","target":"bein sport 1","screen":null,"extra":"live","confidence":0.95}
"حوسلي على فيلم أكشن" => {"actionType":"search","target":"action","screen":null,"extra":"movie","confidence":0.90}
"اديني للمسلسلات" أو "افتح المسلسلات" => {"actionType":"navigate","target":"","screen":"series","extra":"","confidence":0.95}
"روح للمفضلة" أو "وريني لي فافوري" => {"actionType":"favorites","target":"","screen":null,"extra":"live","confidence":0.95}
"بدل لاشين، دير القناة الجاية" => {"actionType":"player_control","target":"next_channel","screen":null,"extra":"","confidence":0.95}
"ياو حبس الفيديو دقيقة" أو "دير بوز" => {"actionType":"player_control","target":"pause","screen":null,"extra":"","confidence":0.95}
"زيد للصوت راهو ناقص" => {"actionType":"player_control","target":"volume_up","screen":null,"extra":"","confidence":0.95}
"قدم الفيديو بـ 30 ثانية" => {"actionType":"player_control","target":"seek_forward","screen":null,"extra":"30","confidence":0.90}
"فركتلي على كاش فيلم تاع رعب" => {"actionType":"search","target":"horror","screen":null,"extra":"movie","confidence":0.95}
"حوسلي على الماتش تاع اليوم" => {"actionType":"navigate","target":"","screen":"matches","extra":"","confidence":0.95}
"أقلب لاشين" أو "جوز هذي القناة" => {"actionType":"player_control","target":"next_channel","screen":null,"extra":"","confidence":0.95}
"رجع لاشين لي كنا نتفرجو فيها" => {"actionType":"player_control","target":"previous_channel","screen":null,"extra":"","confidence":0.90}
"قويلو شوية ماناش نسمعو" أو "زيدلو للخر" => {"actionType":"player_control","target":"volume_up","screen":null,"extra":"","confidence":0.95}
"كوبي الصوت" أو "ديرلو المييت" => {"actionType":"player_control","target":"mute","screen":null,"extra":"","confidence":0.95}
"طفي عليا هاد الفيلم" أو "حبس دقيقة" => {"actionType":"player_control","target":"pause","screen":null,"extra":"","confidence":0.95}
"اطلقو يمشي" أو "كمل الفيديو" => {"actionType":"player_control","target":"resume","screen":null,"extra":"","confidence":0.90}
"ديني للرئيسية" أو "رجعني للباج اللولة" => {"actionType":"home","target":"","screen":null,"extra":"","confidence":0.95}
"وريني لي فافوري تاوعي" => {"actionType":"favorites","target":"","screen":null,"extra":"","confidence":0.95}

أرجع JSON فقط.
""".trimIndent()
    }

    private fun buildGeminiRequestBody(promptText: String): okhttp3.RequestBody {
        val parts = JSONArray().put(JSONObject().put("text", promptText))
        val contents = JSONArray().put(JSONObject().put("parts", parts))
        val json = JSONObject().put("contents", contents)
        return json.toString().toRequestBody("application/json".toMediaTypeOrNull())
    }

    private fun parseGeminiResponse(response: String, originalText: String): VoiceAction {
        return try {
            val json = JSONObject(response)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) return fallbackAction(originalText)

            val text = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")?.getJSONObject(0)?.optString("text", "") ?: ""
            val cleanJson = text.replace("```json", "").replace("```", "").trim()
            val actionJson = JSONObject(cleanJson)

            VoiceAction(
                actionType = actionJson.optString("actionType", "unknown"),
                target = actionJson.optString("target", ""),
                screen = actionJson.optString("screen", null),
                extra = actionJson.optString("extra", ""),
                confidence = actionJson.optDouble("confidence", 1.0).toFloat()
            )
        } catch (e: Exception) {
            fallbackAction(originalText)
        }
    }

    private fun convertToGeminiAction(parsed: VoiceCommand): VoiceAction {
        return when (parsed) {
            is VoiceCommand.Play -> VoiceAction("play", parsed.channelName, null, "", 0.95f)
            is VoiceCommand.Navigate -> VoiceAction("navigate", "", when (parsed.screen) {
                VoiceCommand.Screen.LIVE -> "live"
                VoiceCommand.Screen.MOVIES -> "movies"
                VoiceCommand.Screen.SERIES -> "series"
                VoiceCommand.Screen.MATCHES -> "matches"
                VoiceCommand.Screen.SETTINGS -> "settings"
                VoiceCommand.Screen.USERS -> "users"
                VoiceCommand.Screen.PRICING -> "pricing"
                VoiceCommand.Screen.PRAYER -> "prayer"
                VoiceCommand.Screen.ABOUT -> "about"
            }, "", 0.95f)
            is VoiceCommand.Search -> VoiceAction("search", parsed.query, null, parsed.contentType ?: "", 0.9f)
            is VoiceCommand.Category -> VoiceAction("category", parsed.category, null, "", 0.9f)
            is VoiceCommand.PlayerControl -> VoiceAction("player_control", parsed.target, null, parsed.extra, 0.95f)
            VoiceCommand.Favorites -> VoiceAction("favorites", "", null, "", 0.95f)
            VoiceCommand.Home -> VoiceAction("home", "", null, "", 0.95f)
            VoiceCommand.Unknown -> fallbackAction("")
        }
    }

    private fun fallbackAction(text: String): VoiceAction = VoiceAction("unknown", text, null, "", 0.1f)
}
