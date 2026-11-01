package za.co.dielys.debug

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import za.co.dielys.data.voice.PcmRecorder
import za.co.dielys.data.voice.RecognizerLocator
import za.co.dielys.data.voice.createRecognizer
import za.co.dielys.data.voice.listenOnce
import za.co.dielys.data.voice.recognizerIntent
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "VoiceChecks"
private const val CLIP_MS = 4_000L
private val BIASING = listOf("Melk", "Brood", "Botter", "Rolls", "Eiers", "Kaas")

/**
 * The three checks that decide whether hold-to-talk works as specced, run on the
 * phone (docs/VOICE.md "Phase 0"). Debug builds only. Every line is also logged
 * under `VoiceChecks` so it can be read with `adb logcat -s VoiceChecks`.
 */
class VoiceChecksActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Panel()
                    } else {
                        Text("Needs Android 13+", modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Composable
    private fun Panel() {
        val scope = rememberCoroutineScope()
        val lines = remember { mutableStateListOf<String>() }
        var busy by remember { mutableStateOf(false) }
        val log: (String) -> Unit = {
            Log.i(TAG, it)
            lines.add(it)
        }
        val permission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

        fun run(block: suspend () -> Unit) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permission.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            scope.launch {
                busy = true
                try {
                    block()
                } finally {
                    busy = false
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text("Voice checks", style = MaterialTheme.typography.titleLarge)
            Row {
                Button(enabled = !busy, onClick = { run { support(log) } }) { Text("1. Support") }
                Button(enabled = !busy, onClick = { run { fed(log, "en-ZA") } }) { Text("2+3 EN") }
                Button(enabled = !busy, onClick = { run { fed(log, "af-ZA") } }) { Text("2+3 AF") }
            }
            Row {
                Button(
                    enabled = !busy,
                    onClick = { run { direct(log, "af-ZA", "melk en brood") } },
                ) {
                    Text("4. Mic AF")
                }
                Button(
                    enabled = !busy,
                    onClick = { run { direct(log, "en-ZA", "milk and bread") } },
                ) {
                    Text("4. Mic EN")
                }
            }
            Text("4: recognizer opens the mic itself; say the phrase each time it asks (3 times).")
            Text("2+3: tap, then say: melk en brood (4 seconds).")
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    /** Check 1: which recognizers exist, and what each language gets from Google's. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun support(log: (String) -> Unit) {
        val all = RecognizerLocator.all(this)
        log("Recognizers: " + all.joinToString { it.flattenToShortString() })
        val google = RecognizerLocator.google(this)
        log("Google's: ${google?.flattenToShortString() ?: "NONE FOUND"}")
        val candidates =
            listOf(RecognizerLocator.GOOGLE_SERVICE, null) +
                all.filter { it != RecognizerLocator.GOOGLE_SERVICE }
        for (candidate in candidates) {
            for (tag in listOf("en-ZA", "af-ZA")) {
                val intent = recognizerIntent(tag, emptyList(), 1, null, false)
                val answer =
                    withContext(Dispatchers.Main) {
                        val recognizer = createRecognizer(this@VoiceChecksActivity, candidate)
                        try {
                            withTimeoutOrNull(5_000) {
                                suspendCancellableCoroutine { cont ->
                                    recognizer.checkRecognitionSupport(
                                        intent,
                                        Executors.newSingleThreadExecutor(),
                                        object : RecognitionSupportCallback {
                                            override fun onSupportResult(
                                                recognitionSupport: RecognitionSupport,
                                            ) {
                                                val r = recognitionSupport
                                                val installed = r.installedOnDeviceLanguages
                                                val downloadable = r.supportedOnDeviceLanguages
                                                val pending = r.pendingOnDeviceLanguages
                                                if (cont.isActive) {
                                                    cont.resume(
                                                        "installed offline=$installed " +
                                                            "downloadable=$downloadable " +
                                                            "pending=$pending " +
                                                            "online=${r.onlineLanguages}",
                                                    )
                                                }
                                            }

                                            override fun onError(error: Int) {
                                                if (cont.isActive) cont.resume("error $error")
                                            }
                                        },
                                    )
                                }
                            } ?: "NO ANSWER in 5 s (cannot bind?)"
                        } finally {
                            recognizer.destroy()
                        }
                    }
                log("${candidate?.packageName ?: "DEFAULT"} $tag: $answer")
            }
        }
    }

    /**
     * Checks 2 and 3: record four seconds, feed them to Google's recognizer online
     * (check 2), then feed the same clip again with biasing strings (check 3).
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun fed(
        log: (String) -> Unit,
        tag: String,
    ) {
        val engines =
            listOf(
                RecognizerLocator.GOOGLE_SERVICE,
                null,
                ComponentName(
                    "com.google.android.tts",
                    "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService",
                ),
            )
        log("Recording $CLIP_MS ms for $tag, speak now")
        val clip = ByteArrayOutputStream()
        val recorder =
            PcmRecorder(OutputStream.nullOutputStream()) { buffer, n -> clip.write(buffer, 0, n) }
        if (!recorder.start()) {
            log("mic would not start")
            return
        }
        delay(CLIP_MS)
        recorder.stop()
        val pcm = clip.toByteArray()
        log("Recorded ${pcm.size} bytes")
        for (engine in engines) {
            for (biasing in listOf(emptyList(), BIASING)) {
                val label =
                    (engine?.packageName ?: "DEFAULT") +
                        if (biasing.isEmpty()) " no biasing" else " with biasing"
                val started = System.nanoTime()
                val result =
                    withContext(Dispatchers.Main) {
                        val pipe = ParcelFileDescriptor.createPipe()
                        Thread {
                            val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                            runCatching { out.use { it.write(pcm) } }
                        }.start()
                        val recognizer = createRecognizer(this@VoiceChecksActivity, engine)
                        try {
                            withTimeoutOrNull(8_000) {
                                recognizer.listenOnce(
                                    recognizerIntent(tag, biasing, 5, pipe[0], false),
                                )
                            } ?: Result.failure(Exception("NO ANSWER in 8 s"))
                        } finally {
                            recognizer.destroy()
                            runCatching { pipe[0].close() }
                        }
                    }
                val ms = (System.nanoTime() - started) / 1_000_000
                result
                    .onSuccess { log("$tag $label ($ms ms): ${it.guesses} ${it.confidences}") }
                    .onFailure { log("$tag $label ($ms ms) FAILED: ${it.message}") }
            }
        }
    }

    /** Check 4: the recognizer opens the mic itself, no fed audio, once per engine. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun direct(
        log: (String) -> Unit,
        tag: String,
        phrase: String,
    ) {
        val engines =
            listOf(
                RecognizerLocator.GOOGLE_SERVICE,
                null,
                ComponentName(
                    "com.google.android.tts",
                    "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService",
                ),
            )
        for (engine in engines) {
            val name = engine?.packageName ?: "DEFAULT"
            log("SAY NOW ($tag, $name): $phrase")
            val started = System.nanoTime()
            val result =
                withContext(Dispatchers.Main) {
                    val recognizer = createRecognizer(this@VoiceChecksActivity, engine)
                    try {
                        withTimeoutOrNull(9_000) {
                            recognizer.listenOnce(
                                recognizerIntent(tag, emptyList(), 5, null, false),
                            )
                        } ?: Result.failure(Exception("NO ANSWER in 9 s"))
                    } finally {
                        recognizer.destroy()
                    }
                }
            val ms = (System.nanoTime() - started) / 1_000_000
            result
                .onSuccess { log("$tag $name direct ($ms ms): ${it.guesses} ${it.confidences}") }
                .onFailure { log("$tag $name direct ($ms ms) FAILED: ${it.message}") }
            delay(1_500)
        }
    }
}
