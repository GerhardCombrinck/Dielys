package za.co.dielys.debug

import android.content.Context
import android.util.Base64
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.co.dielys.data.remote.VoiceAction
import za.co.dielys.data.remote.VoiceApi
import za.co.dielys.data.remote.VoiceCommandRequest
import za.co.dielys.data.voice.PcmRecorder
import za.co.dielys.data.voice.SAMPLE_RATE
import za.co.dielys.data.voice.VoiceTrace
import za.co.dielys.domain.VoiceDictionary
import za.co.dielys.domain.Wav
import java.io.ByteArrayOutputStream
import java.io.File

/** The lists a benchmark prompt is spoken against, fixed so every replay sends the same request. */
data class BenchContext(
    val language: String,
    val listName: String,
    val lists: List<String>,
    val dictionary: List<String>,
)

/** One fixed thing to say, and the items it should become. [list] null means the list on screen. */
data class BenchPrompt(
    val id: String,
    val say: String,
    val expected: List<String>,
    val list: String?,
    val context: BenchContext,
)

private val EN =
    BenchContext(
        language = "en",
        listName = "Groceries",
        lists = listOf("Groceries", "Woolies", "Hardware"),
        dictionary =
            listOf(
                "Milk",
                "Bread",
                "Eggs",
                "Cheese",
                "Butter",
                "Coffee",
                "Tea",
                "Bananas",
                "Tomatoes",
                "Rice",
            ),
    )

private val AF =
    BenchContext(
        language = "af",
        listName = "Kruideniers",
        lists = listOf("Kruideniers", "Woolies", "Hardeware"),
        dictionary =
            listOf(
                "Melk",
                "Brood",
                "Eiers",
                "Kaas",
                "Botter",
                "Koffie",
                "Tee",
                "Piesangs",
                "Tamaties",
                "Rys",
            ),
    )

/**
 * The benchmark set (docs/VOICE.md "Timing"): per language one item already on
 * the list (the instant-match path), several items with one new (the model), and
 * items for another list. Recorded once, replayed for every server change.
 */
val BENCH_PROMPTS =
    listOf(
        BenchPrompt("en-1", "Milk", listOf("Milk"), null, EN),
        BenchPrompt(
            "en-2",
            "Eggs, bread and peanut butter",
            listOf("Eggs", "Bread", "Peanut butter"),
            null,
            EN,
        ),
        BenchPrompt(
            "en-3",
            "Add coffee and dishwasher tablets to Woolies",
            listOf("Coffee", "Dishwasher tablets"),
            "Woolies",
            EN,
        ),
        BenchPrompt("af-1", "Melk", listOf("Melk"), null, AF),
        BenchPrompt(
            "af-2",
            "Eiers, brood en grondboontjiebotter",
            listOf("Eiers", "Brood", "Grondboontjiebotter"),
            null,
            AF,
        ),
        BenchPrompt(
            "af-3",
            "Sit koffie en skottelgoedtablette op Woolies",
            listOf("Koffie", "Skottelgoedtablette"),
            "Woolies",
            AF,
        ),
    )

/** Speech models the dev Worker can be asked for (X-Voice-Stt); "turbo" is what ships. */
val STT_VARIANTS = listOf("turbo", "nova3")

/** The app's own voice client, for replaying through exactly what a real hold uses. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VoiceBenchEntryPoint {
    fun voiceApi(): VoiceApi
}

/**
 * Where samples live: the app's external files, so `adb pull
 * /sdcard/Android/data/za.co.dielys.debug/files/voice-bench` backs them up.
 * Never in git: they are someone's voice.
 */
fun benchFile(
    context: Context,
    prompt: BenchPrompt,
): File = File(context.getExternalFilesDir("voice-bench"), "${prompt.id}.wav")

/** Records one sample while held; [stop] trims the quiet and saves it as WAV. */
class BenchRecorder(
    private val file: File,
) {
    private val pcm = ByteArrayOutputStream()
    private val recorder = PcmRecorder(pcm)

    fun start(): Boolean = recorder.start()

    fun stop() {
        recorder.stop()
        val trimmed = Wav.trimSilence(pcm.toByteArray(), SAMPLE_RATE)
        file.parentFile?.mkdirs()
        file.writeBytes(Wav.pcm16Mono(trimmed, SAMPLE_RATE))
    }
}

private const val WAV_HEADER = 44

/** Seconds of audio in a saved sample. */
fun seconds(file: File): Double =
    (file.length() - WAV_HEADER).coerceAtLeast(0) / (SAMPLE_RATE * 2.0)

/** Sends one saved sample through the real client; the run lands in [za.co.dielys.data.voice.VoiceTimings]. */
suspend fun replay(
    api: VoiceApi,
    prompt: BenchPrompt,
    file: File,
    variant: String? = null,
) {
    val trace = VoiceTrace(variant)
    trace.released()
    trace.note("bench", prompt.id)
    trace.note("sent audio", "%.1fs".format(seconds(file)))
    val wav = withContext(Dispatchers.IO) { file.readBytes() }
    val audio = trace.stage("before", "base64") { Base64.encodeToString(wav, Base64.NO_WRAP) }
    val ctx = prompt.context
    val response =
        api.command(
            VoiceCommandRequest(
                language = ctx.language,
                listName = ctx.listName,
                lists = ctx.lists,
                audio = audio,
                dictionary = ctx.dictionary,
            ),
            trace,
        )
    if (response == null) {
        trace.finish("${prompt.id} unavailable" + (variant?.let { " [$it]" } ?: ""))
        return
    }
    val adds = response.actions.filter { it.type == "add" }
    val pass = matches(prompt, adds)
    trace.finish(
        outcome =
            "${prompt.id} ${if (pass) "pass" else "FAIL"}" + (
                variant?.let {
                    " [$it]"
                } ?: ""
            ),
        detail =
            "heard \"${response.heard}\" → " +
                adds.joinToString(", ") { a -> a.text + (a.list?.let { " @$it" } ?: "") },
    )
}

/** Same items, ignoring case and order, each on the expected list. */
private fun matches(
    prompt: BenchPrompt,
    adds: List<VoiceAction>,
): Boolean {
    val want = prompt.expected.map(VoiceDictionary::normalise).sorted()
    val got = adds.map { VoiceDictionary.normalise(it.text) }.sorted()
    val here = VoiceDictionary.normalise(prompt.context.listName)
    val target = prompt.list?.let(VoiceDictionary::normalise) ?: here
    val lists = adds.all { (it.list?.let(VoiceDictionary::normalise) ?: here) == target }
    return want == got && lists
}
