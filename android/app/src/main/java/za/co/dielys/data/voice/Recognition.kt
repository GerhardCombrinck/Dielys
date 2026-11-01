package za.co.dielys.data.voice

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.OutputStream
import kotlin.coroutines.resume

/** What the recognizer came back with: guesses best first, confidences where it gave them. */
data class Heard(
    val guesses: List<String>,
    val confidences: List<Float>,
)

/** The recognizer's failure, as a code the debug panel can show. */
data class RecognitionFailure(
    val code: Int,
)

/**
 * Finds Google's recognizer by component name rather than trusting the phone's
 * default (docs/VOICE.md "The recognizer"). Samsung phones default to Samsung's
 * own engine, which has neither South African English nor Afrikaans.
 */
object RecognizerLocator {
    const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"

    /** Every recognition service on the phone, for the debug panel and for [google]. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun all(context: Context): List<ComponentName> =
        context.packageManager
            .queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE),
                PackageManager.ResolveInfoFlags.of(0),
            ).map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }

    /** The component Google's own app registers, tried by name when it is installed. */
    val GOOGLE_SERVICE =
        ComponentName(
            GOOGLE_PACKAGE,
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
        )

    /**
     * Google's recognizer: the Google app's service when it is installed (it may not
     * show in the query, which only lists services visible to this app), otherwise a
     * listed one from the Google app, otherwise null so the phone's default is used.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun google(context: Context): ComponentName? {
        val installed =
            runCatching {
                context.packageManager.getPackageInfo(
                    GOOGLE_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            }.isSuccess
        return if (installed) {
            GOOGLE_SERVICE
        } else {
            all(context).firstOrNull {
                it.packageName ==
                    GOOGLE_PACKAGE
            }
        }
    }
}

/** The extras every request carries; [source] is audio the app feeds instead of the mic. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun recognizerIntent(
    tag: String,
    biasing: List<String>,
    maxResults: Int,
    source: ParcelFileDescriptor?,
    preferOffline: Boolean,
): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        if (biasing.isNotEmpty()) {
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing))
        }
        if (source != null) {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
        }
    }

const val SAMPLE_RATE = 16_000

/** Creates the recognizer for [component], or the phone's default when there is none. Main thread only. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun createRecognizer(
    context: Context,
    component: ComponentName?,
): SpeechRecognizer =
    if (component != null) {
        SpeechRecognizer.createSpeechRecognizer(context, component)
    } else {
        SpeechRecognizer.createSpeechRecognizer(context)
    }

/**
 * One recognition, start to final result. Main thread only; the recognizer is
 * destroyed when it ends or the caller is cancelled. [onPartial] sees in-progress
 * guesses, which is what lets an instant match answer before the final arrives.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
suspend fun SpeechRecognizer.listenOnce(
    intent: Intent,
    onStarted: () -> Unit = {},
    onPartial: (List<String>) -> Unit = {},
): Result<Heard> =
    suspendCancellableCoroutine { cont ->
        val recognizer = this
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val guesses =
                        results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION,
                            ).orEmpty()
                    val scores =
                        results
                            ?.getFloatArray(
                                SpeechRecognizer.CONFIDENCE_SCORES,
                            )?.toList()
                            .orEmpty()
                    if (cont.isActive) {
                        cont.resume(
                            if (guesses.isEmpty()) {
                                Result.failure(NoSpeech)
                            } else {
                                Result.success(Heard(guesses, scores))
                            },
                        )
                    }
                }

                override fun onError(error: Int) {
                    if (cont.isActive) cont.resume(Result.failure(RecognizerError(error)))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    onPartial(
                        partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            .orEmpty(),
                    )
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) = Unit
            },
        )
        cont.invokeOnCancellation { recognizer.cancel() }
        recognizer.startListening(intent)
        onStarted()
    }

object NoSpeech : Exception("no speech")

class RecognizerError(
    val code: Int,
) : Exception("recognizer error $code")

/**
 * Records 16 kHz mono PCM from the mic into [sink] from the moment it starts, so
 * the first syllable is not lost while the recognizer spins up. [stop] ends the
 * audio and closes the sink, which is what the recognizer sees as the end.
 */
class PcmRecorder(
    private val sink: OutputStream,
    private val tap: ((ByteArray, Int) -> Unit)? = null,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    /** False when the mic could not be opened (permission, or something else holds it). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val size =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (size <= 0) return false
        val rec =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    size * 2,
                )
            } catch (_: SecurityException) {
                return false
            }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        record = rec
        running = true
        rec.startRecording()
        thread =
            Thread {
                val buffer = ByteArray(size)
                try {
                    while (running) {
                        val n = rec.read(buffer, 0, buffer.size)
                        if (n > 0) {
                            sink.write(buffer, 0, n)
                            tap?.invoke(buffer, n)
                        } else if (n < 0) {
                            break
                        }
                    }
                } catch (_: java.io.IOException) {
                    // The reader went away (cancelled); nothing left to feed.
                } finally {
                    runCatching { sink.close() }
                }
            }.also { it.start() }
        return true
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        thread?.join(JOIN_MS)
        record?.release()
        record = null
    }

    private companion object {
        const val JOIN_MS = 500L
    }
}
