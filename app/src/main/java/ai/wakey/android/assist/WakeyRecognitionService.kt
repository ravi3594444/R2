package ai.wakey.android.assist

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android requires a digital assistant to declare a speech recognizer, and making Wakey the
 * assistant also makes this the system's default one. Wakey does its own recognition, so other
 * apps' voice input is forwarded to another installed recognizer (Google's when present) instead
 * of breaking.
 */
class WakeyRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener ?: return
        val target = otherRecognizer()
        if (target == null) {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        delegate?.destroy()
        delegate = SpeechRecognizer.createSpeechRecognizer(this, target).apply {
            setRecognitionListener(Forwarder(listener))
            startListening(recognizerIntent ?: Intent())
        }
    }

    override fun onStopListening(listener: Callback?) {
        delegate?.stopListening()
    }

    override fun onCancel(listener: Callback?) {
        delegate?.cancel()
    }

    override fun onDestroy() {
        delegate?.destroy()
        delegate = null
        super.onDestroy()
    }

    private fun otherRecognizer(): ComponentName? {
        val services = packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != packageName }
        return services.firstOrNull { it.packageName == GOOGLE_APP } ?: services.firstOrNull()
    }

    private class Forwarder(private val callback: Callback) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = callback.readyForSpeech(params ?: Bundle())
        override fun onBeginningOfSpeech() = callback.beginningOfSpeech()
        override fun onRmsChanged(rmsdB: Float) = callback.rmsChanged(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) = callback.bufferReceived(buffer ?: ByteArray(0))
        override fun onEndOfSpeech() = callback.endOfSpeech()
        override fun onError(error: Int) = callback.error(error)
        override fun onResults(results: Bundle?) = callback.results(results ?: Bundle())
        override fun onPartialResults(partialResults: Bundle?) = callback.partialResults(partialResults ?: Bundle())
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    }
}
