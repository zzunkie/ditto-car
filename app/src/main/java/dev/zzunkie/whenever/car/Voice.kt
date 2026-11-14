package dev.zzunkie.whenever.car

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.tts.TextToSpeech
import android.content.Context
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener as SttListener
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/** 음성 안내. 초기화가 끝나기 전에 들어온 문장은 모아 두었다가 말한다. */
class Speaker(ctx: Context) {
    private val tts = TextToSpeech(ctx, TtsMode.ON_DEVICE)
    private var ready = false
    private val pending = mutableListOf<String>()

    fun start() {
        tts.initialize(object : TtsInit {
            override fun onSuccess() {
                Log.i(TAG, "tts ready")
                // addEventListener 는 초기화 성공 뒤에 붙인다(spike 5절)
                tts.addEventListener(object : TtsListener {
                    override fun onStart() { Log.i(TAG, "tts start") }
                    override fun onReady() {}
                    override fun onDone() { Log.i(TAG, "tts done") }
                    override fun onError(type: TtsError) { Log.w(TAG, "tts error $type") }
                    override fun onStop() {}
                    override fun onUpdatedRms(rms: Double) {}
                })
                tts.setLocale(Locale.KOREA)
                synchronized(pending) {
                    ready = true
                    pending.forEach { tts.speak(it, false) }
                    pending.clear()
                }
            }
            override fun onFailure(type: TtsError) { Log.w(TAG, "tts init failed $type") }
        })
    }

    fun say(text: String) {
        Log.i(TAG, "say: $text")
        synchronized(pending) { if (ready) tts.speak(text, false) else pending += text }
    }

    fun release() = runCatching { tts.release() }
}

/** 음성 입력(기기 안 인식, 한국어). 결과 문장을 onText 로 넘긴다. */
class Listener(ctx: Context, private val onState: (String) -> Unit, private val onText: (String) -> Unit) {
    private val stt = SpeechToText(ctx, SttMode.ON_DEVICE)
    var ready = false
        private set

    fun start() {
        stt.addListener(object : SttListener {
            override fun onUpdated(text: String, isFinal: Boolean) {
                Log.i(TAG, "stt '$text' final=$isFinal")
                if (isFinal && text.isNotBlank()) onText(text) else if (text.isNotBlank()) onState("partial:$text")
            }
            override fun onUpdatedEpdData(a: Long, b: Long) {}
            override fun onStartedRecognition() = onState("listening")
            override fun onEndedRecognition() = onState("ended")
            override fun onError(type: SttError) { Log.w(TAG, "stt error $type"); onState("error") }
            override fun onReady() { Log.i(TAG, "stt listening ready"); onState("ready") }
            override fun onUpdatedRms(rms: Double) {}
        })
        stt.initialize(object : SttInit {
            override fun onSuccess() {
                Log.i(TAG, "stt init ok")
                // Locale.KOREAN 은 "unsupported locale" 오류가 나서 KOREA 를 쓴다(spike 5절)
                stt.setLocale(Locale.KOREA)
                ready = true
            }
            override fun onFailure(type: SttError) { Log.w(TAG, "stt init failed $type"); onState("error") }
        })
    }

    fun listen() {
        if (!ready) { onState("error"); return }
        runCatching { stt.request() }.onFailure { Log.w(TAG, "stt request $it"); onState("error") }
    }

    fun stop() = runCatching { stt.stop() }
    fun release() = runCatching { stt.release() }
}
