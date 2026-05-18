package com.medtroniclabs.microcoaching.ai.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import java.util.Locale

class BanglaTtsHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingText: String? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val locale = Locale("bn", "BD")
        when (tts?.isLanguageAvailable(locale)) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                tts?.language = locale
                isReady = true
                pendingText?.let { speak(it) }
                pendingText = null
            }
            TextToSpeech.LANG_MISSING_DATA -> {
                // Prompt user to install Bangla TTS voice data
                val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
            else -> { /* LANG_NOT_SUPPORTED — device cannot do bn-BD */ }
        }
    }

    fun speak(text: String) {
        if (!isReady) {
            pendingText = text
            return
        }
        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bn_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
