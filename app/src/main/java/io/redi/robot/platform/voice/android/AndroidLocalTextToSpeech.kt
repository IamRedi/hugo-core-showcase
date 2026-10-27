package io.redi.robot.platform.voice.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.redi.robot.firstlife.voice.LOCAL_ALBANIAN_LOCALE
import io.redi.robot.firstlife.voice.LocalSpeechOutputPort
import io.redi.robot.firstlife.voice.LocalTtsSupport
import io.redi.robot.firstlife.voice.SpeechOutputBlockedReason
import io.redi.robot.firstlife.voice.SpeechOutputListener
import io.redi.robot.firstlife.voice.SpeechOutputRequest
import io.redi.robot.firstlife.voice.SpeechOutputRequestId
import java.util.Locale

class AndroidLocalTextToSpeech(context: Context) : LocalSpeechOutputPort {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var engine: TextToSpeech? = null
    private var owner: SpeechOutputRequestId? = null
    private var selectedVoiceName: String? = null
    private var focusRequest: AudioFocusRequest? = null
    private var activeListener: SpeechOutputListener? = null

    override fun checkSupport(requestId: SpeechOutputRequestId, callback: (SpeechOutputRequestId, LocalTtsSupport) -> Unit) {
        releaseCurrent()
        owner = requestId
        engine = TextToSpeech(appContext) { status ->
            val current = engine
            if (owner != requestId) return@TextToSpeech
            if (status != TextToSpeech.SUCCESS || current == null) {
                callback(requestId, LocalTtsSupport.ENGINE_UNAVAILABLE)
                return@TextToSpeech
            }
            val locale = Locale.forLanguageTag(LOCAL_ALBANIAN_LOCALE)
            val language = current.isLanguageAvailable(locale)
            val exact = current.voices.orEmpty()
                .filter { it.locale.toLanguageTag().equals(LOCAL_ALBANIAN_LOCALE, ignoreCase = true) }
            val installedLocal = exact
                .filterNot { it.isNetworkConnectionRequired }
                .filterNot { it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedBy { it.name }
                .firstOrNull()
            val support = when {
                language < TextToSpeech.LANG_AVAILABLE || exact.isEmpty() -> LocalTtsSupport.ALBANIAN_LANGUAGE_UNAVAILABLE
                installedLocal == null -> LocalTtsSupport.VOICE_DATA_MISSING
                current.setVoice(installedLocal) != TextToSpeech.SUCCESS -> LocalTtsSupport.VOICE_DATA_MISSING
                else -> {
                    selectedVoiceName = installedLocal.name
                    LocalTtsSupport.SUPPORTED
                }
            }
            callback(requestId, support)
        }
    }

    override fun speak(request: SpeechOutputRequest, listener: SpeechOutputListener) {
        val current = engine
        if (owner != request.id || current == null || selectedVoiceName == null) {
            listener.onFailed(request.id, SpeechOutputBlockedReason.ENGINE_UNAVAILABLE)
            return
        }
        if (!acquireAudioFocus()) {
            listener.onFailed(request.id, SpeechOutputBlockedReason.AUDIO_FOCUS_DENIED)
            return
        }
        activeListener = listener
        current.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (owner == request.id && utteranceId == request.id.value) listener.onStarted(request.id)
            }
            override fun onDone(utteranceId: String?) {
                if (owner == request.id && utteranceId == request.id.value) listener.onCompleted(request.id)
            }
            @Deprecated("Android callback")
            override fun onError(utteranceId: String?) {
                if (owner == request.id && utteranceId == request.id.value) {
                    listener.onFailed(request.id, SpeechOutputBlockedReason.SYNTHESIS_FAILED)
                }
            }
            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
        })
        if (current.speak(request.text.value, TextToSpeech.QUEUE_FLUSH, null, request.id.value) != TextToSpeech.SUCCESS) {
            listener.onFailed(request.id, SpeechOutputBlockedReason.SYNTHESIS_FAILED)
        }
    }

    override fun release(requestId: SpeechOutputRequestId) {
        if (owner != requestId) return
        releaseCurrent()
    }

    private fun releaseCurrent() {
        owner = null
        activeListener = null
        selectedVoiceName = null
        engine?.stop()
        engine?.shutdown()
        engine = null
        abandonAudioFocus()
    }

    private fun acquireAudioFocus(): Boolean {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                val requestId = owner
                val callback = activeListener
                if (requestId != null && callback != null) {
                    callback.onFailed(requestId, SpeechOutputBlockedReason.INTERRUPTED)
                } else {
                    releaseCurrent()
                }
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(listener)
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
    }
}
