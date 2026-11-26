package no.lionflow.app.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord

/**
 * Speech from the car's own microphone, recognised in the language of the country the car is in
 * (Norwegian in Norway, Swedish in Sweden, …; English elsewhere). Needs Android 13 and a car that lets apps record.
 */
object CarVoice {
    fun possible(ctx: CarContext): Boolean = Build.VERSION.SDK_INT >= 33 &&
        try { ctx.carAppApiLevel >= 5 && SpeechRecognizer.isRecognitionAvailable(ctx) } catch (_: Exception) { false }

    /** Listens once; [onText] gets what was said, or null when nothing was understood. */
    fun listen(ctx: CarContext, onText: (String?) -> Unit) {
        if (!possible(ctx)) { onText(null); return }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ctx.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
                if (Manifest.permission.RECORD_AUDIO in granted) start(ctx, onText) else onText(null)
            }
            return
        }
        start(ctx, onText)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun start(ctx: CarContext, onText: (String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val rec = try { CarAudioRecord.create(ctx) } catch (_: Exception) { onText(null); return }
        val pipe = ParcelFileDescriptor.createPipe()
        var running = true
        var done = false
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
        fun finish(text: String?) {
            if (done) return
            done = true; running = false
            try { rec.stopRecording() } catch (_: Exception) { }
            try { sr.destroy() } catch (_: Exception) { }
            try { pipe[1].close() } catch (_: Exception) { }
            try { pipe[0].close() } catch (_: Exception) { }
            onText(text)
        }
        try { rec.startRecording() } catch (_: Exception) { finish(null); return }
        Thread {
            val buf = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                try {
                    while (running) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n < 0) break
                        if (n > 0) out.write(buf, 0, n)
                    }
                } catch (_: Exception) { }
            }
        }.start()
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                main.post { finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }) }
            }
            override fun onError(error: Int) { main.post { finish(null) } }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val lang = Where.speechLanguage(ctx)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
        try { sr.startListening(i) } catch (_: Exception) { finish(null); return }
        main.postDelayed({ if (!done) { try { sr.stopListening() } catch (_: Exception) { }; main.postDelayed({ finish(null) }, 3000) } }, 9000)
    }
}
