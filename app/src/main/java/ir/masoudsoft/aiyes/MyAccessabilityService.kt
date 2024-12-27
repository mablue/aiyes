package ir.masoudsoft.aiyes

import android.accessibilityservice.AccessibilityService
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

class MyAccessibilityService : AccessibilityService() {

    private lateinit var tts: TextToSpeech
    private var accessibilityManager: AccessibilityManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // TTS is initialized successfully

            }
        }
    }

    fun speak(text: String) {
        if (!isTtsPlaying()) {
            // Speak the text using TTS
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)

            // Send an AccessibilityEvent
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            event.text.add(text)
            event.packageName = packageName
            event.className = this::class.java.name
//            event.source = rootInActiveWindow
            accessibilityManager!!.sendAccessibilityEvent(event)
        }
    }

    private fun isTtsPlaying(): Boolean {
        // Check if TTS is currently speaking
        return tts.isSpeaking
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle accessibility events if needed
    }

    override fun onInterrupt() {
        // Handle interruptions if needed
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
    }
}
