package com.latchi.iptv.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.latchi.iptv.R

class VoiceHandler(
    private val context: Context,
    private val voiceOverlay: FrameLayout,
    private val onCommand: (VoiceCommand) -> Unit,
    private val onGeminiAction: (GeminiVoiceController.VoiceAction) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isSmartMode: Boolean = true

    private val glowContainer: View by lazy { voiceOverlay.findViewById(R.id.voiceGlowContainer) }
    private val statusText: TextView by lazy { voiceOverlay.findViewById(R.id.voiceStatusText) }
    private val micIcon: TextView by lazy { voiceOverlay.findViewById(R.id.voiceMicIcon) }

    init {
        voiceOverlay.setOnClickListener { stopListening() }
    }

    fun startListening(smartMode: Boolean = true) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "التعرف على الصوت غير متوفر على هذا الجهاز", Toast.LENGTH_SHORT).show()
            return
        }

        if (isListening) return
        isListening = true
        isSmartMode = smartMode

        showOverlay()
        initSpeechRecognizer()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-DZ")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        hideOverlay()
    }

    private fun showOverlay() {
        voiceOverlay.visibility = View.VISIBLE
        statusText.text = if (isSmartMode) "🧠 Gemini يستمع..." else "استمع..."
        micIcon.text = "🎙️"

        val pulseAnim = AnimationUtils.loadAnimation(context, R.anim.voice_pulse)
        glowContainer.startAnimation(pulseAnim)
    }

    private fun hideOverlay() {
        glowContainer.clearAnimation()
        voiceOverlay.visibility = View.GONE
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    statusText.text = if (isSmartMode) "🧠 Gemini مستعد..." else "استمع..."
                }
                override fun onBeginningOfSpeech() { statusText.text = "يسمع..." }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { statusText.text = if (isSmartMode) "🧠 يفكر..." else "يعالج..." }
                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "لم أفهم، حاول مرة أخرى"
                        SpeechRecognizer.ERROR_NETWORK -> "مشكلة في الاتصال"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "انتهى الوقت"
                        SpeechRecognizer.ERROR_AUDIO -> "مشكلة في الميكروفون"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "لم يتم التقاط صوت"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "صلاحيات الميكروفون غير ممنوحة"
                        else -> "خطأ في التعرف ($error)"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    stopListening()
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        processResult(matches[0])
                    } else {
                        Toast.makeText(context, "لم يتم التعرف على صوت", Toast.LENGTH_SHORT).show()
                        stopListening()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!partial.isNullOrEmpty()) statusText.text = partial[0]
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun processResult(text: String) {
        if (isSmartMode) {
            GeminiVoiceController.processVoiceCommand(context, text, onResult = { action ->
                stopListening()
                onGeminiAction(action)
            }, onError = { error ->
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                fallbackToFastMode(text)
            })
        } else {
            fallbackToFastMode(text)
        }
    }

    private fun fallbackToFastMode(text: String) {
        stopListening()
        val command = VoiceCommandParser.parse(text)
        onCommand(command)
    }

    fun destroy() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
