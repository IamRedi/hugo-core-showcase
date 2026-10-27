package io.redi.robot.platform.voice.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import io.redi.robot.firstlife.voice.VoiceCaptureListener
import io.redi.robot.firstlife.voice.VoiceCapturePolicy
import io.redi.robot.firstlife.voice.VoiceCapturePort
import io.redi.robot.firstlife.voice.VoiceOnDeviceSupport
import io.redi.robot.firstlife.voice.VoiceRecognizerFailure
import io.redi.robot.firstlife.voice.VoiceSessionRequest
import io.redi.robot.firstlife.voice.VoiceSessionRequestId
import io.redi.robot.firstlife.voice.VoiceTranscriptCandidates
import io.redi.robot.firstlife.voice.isAlbanianLocaleTag
import java.util.concurrent.atomic.AtomicBoolean

/** Android-only adapter. It never exposes audio buffers, Android speech types, or transcript logs. */
class AndroidOnDeviceSpeechRecognizer(private val context: Context) : VoiceCapturePort {
    private var active: ActiveRecognizer? = null

    @MainThread
    override fun checkOnDeviceSupport(
        requestId: VoiceSessionRequestId,
        callback: (VoiceSessionRequestId, VoiceOnDeviceSupport) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            callback(requestId, VoiceOnDeviceSupport.RECOGNIZER_UNAVAILABLE)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            callback(requestId, VoiceOnDeviceSupport.SUPPORTED)
            return
        }

        val probe = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val closed = AtomicBoolean(false)
        fun finish(result: VoiceOnDeviceSupport) {
            if (!closed.compareAndSet(false, true)) return
            probe.destroy()
            callback(requestId, result)
        }
        probe.checkRecognitionSupport(
            recognitionIntent(VoiceCapturePolicy.LOCALE_TAG),
            ContextCompat.getMainExecutor(context),
            object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    val installed = recognitionSupport.installedOnDeviceLanguages
                    val downloadable = recognitionSupport.pendingOnDeviceLanguages +
                        recognitionSupport.supportedOnDeviceLanguages
                    finish(
                        when {
                            installed.any(String::isAlbanianLocaleTag) -> VoiceOnDeviceSupport.SUPPORTED
                            downloadable.any(String::isAlbanianLocaleTag) -> VoiceOnDeviceSupport.ALBANIAN_MODEL_DOWNLOAD_REQUIRED
                            else -> VoiceOnDeviceSupport.ALBANIAN_MODEL_UNAVAILABLE
                        },
                    )
                }

                override fun onError(error: Int) {
                    finish(VoiceOnDeviceSupport.ALBANIAN_MODEL_UNAVAILABLE)
                }
            },
        )
    }

    @MainThread
    override fun startOnDeviceCapture(request: VoiceSessionRequest, listener: VoiceCaptureListener) {
        if (active != null) {
            listener.onFailure(request.id, VoiceRecognizerFailure.BUSY)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            listener.onFailure(request.id, VoiceRecognizerFailure.OTHER)
            return
        }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val handle = ActiveRecognizer(request.id, recognizer, listener)
        active = handle
        recognizer.setRecognitionListener(handle)
        recognizer.startListening(recognitionIntent(request.localeTag, preferOffline = true))
    }

    @MainThread
    override fun startSystemCapture(request: VoiceSessionRequest, listener: VoiceCaptureListener) {
        if (active != null) {
            listener.onFailure(request.id, VoiceRecognizerFailure.BUSY)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onFailure(request.id, VoiceRecognizerFailure.OTHER)
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val handle = ActiveRecognizer(request.id, recognizer, listener)
        active = handle
        recognizer.setRecognitionListener(handle)
        recognizer.startListening(recognitionIntent(request.localeTag, preferOffline = false))
    }

    @MainThread
    override fun release(requestId: VoiceSessionRequestId) {
        active?.takeIf { it.requestId == requestId }?.finish(cancelFirst = true)
    }

    private fun recognitionIntent(
        localeTag: String,
        preferOffline: Boolean = true,
    ): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
        if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, VoiceCapturePolicy.MAX_ALTERNATIVES)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }

    private inner class ActiveRecognizer(
        val requestId: VoiceSessionRequestId,
        private val recognizer: SpeechRecognizer,
        private val listener: VoiceCaptureListener,
    ) : RecognitionListener {
        private val closed = AtomicBoolean(false)

        override fun onReadyForSpeech(params: Bundle?) = listener.onCaptureStarted(requestId)
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = listener.onSpeechEnded(requestId)
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val candidates = VoiceTranscriptCandidates.from(alternatives)
            finish(cancelFirst = false)
            listener.onResults(requestId, candidates)
        }

        override fun onError(error: Int) {
            val mapped = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> VoiceRecognizerFailure.NO_MATCH
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceRecognizerFailure.SPEECH_TIMEOUT
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceRecognizerFailure.BUSY
                SpeechRecognizer.ERROR_AUDIO -> VoiceRecognizerFailure.AUDIO
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER,
                -> VoiceRecognizerFailure.NETWORK
                SpeechRecognizer.ERROR_CLIENT -> VoiceRecognizerFailure.CLIENT_CANCELLED
                else -> VoiceRecognizerFailure.OTHER
            }
            finish(cancelFirst = false)
            listener.onFailure(requestId, mapped)
        }

        fun finish(cancelFirst: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            if (cancelFirst) recognizer.cancel()
            recognizer.destroy()
            if (active === this) active = null
        }
    }
}
