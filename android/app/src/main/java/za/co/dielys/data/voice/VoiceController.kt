package za.co.dielys.data.voice

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.co.dielys.data.DielysRepository
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.ListEntity
import za.co.dielys.data.local.NewTaskPlacement
import za.co.dielys.data.remote.VoiceAction
import za.co.dielys.data.remote.VoiceApi
import za.co.dielys.data.remote.VoiceCommandRequest
import za.co.dielys.data.remote.VoiceCorrection
import za.co.dielys.data.remote.VoiceCorrectionRecord
import za.co.dielys.domain.VoiceDictionary
import za.co.dielys.domain.Wav
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class VoiceState { IDLE, LISTENING, WORKING }

/** One item voice added, and the title of the list it went on. */
data class VoiceAdded(
    val text: String,
    val listTitle: String,
    val onCurrentList: Boolean,
)

/** Why a hold produced nothing. */
enum class VoiceFailure { NOT_HEARD, UNAVAILABLE }

/**
 * Hold to talk (ADR 0016). [begin] on press starts recording from the mic at once,
 * so the first word is never lost to a recognizer starting up. [finish] on release
 * uploads the clip once; the Worker transcribes it with Whisper and turns the
 * sentence into actions ("add coffee, bread and milk to Woolies"), which are
 * applied here as ordinary adds through the outbox.
 *
 * A singleton that holds a `Context`, which is why the screen's view model only
 * holds this and never the context itself (E1).
 */
@Singleton
class VoiceController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: DielysDatabase,
        private val api: VoiceApi,
        private val repo: DielysRepository,
        private val placement: NewTaskPlacement,
        private val memory: VoiceMemory,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private val _state = MutableStateFlow(VoiceState.IDLE)
        val state: StateFlow<VoiceState> = _state.asStateFlow()

        private val _language =
            MutableStateFlow(
                prefs.getString(KEY_LANGUAGE, null) ?: appLanguage(),
            )

        /** The speaker's language, remembered on this device; the app's language until chosen. */
        val language: StateFlow<String> = _language.asStateFlow()

        private var hold: Hold? = null

        /** Any phone with a microphone. */
        val available: Boolean
            get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

        fun toggleLanguage() {
            val next = if (_language.value == "af") "en" else "af"
            prefs.edit().putString(KEY_LANGUAGE, next).apply()
            _language.value = next
        }

        private fun appLanguage(): String =
            if (context.resources.configuration.locales[0]
                    .language == "af"
            ) {
                "af"
            } else {
                "en"
            }

        private class Known(
            val dictionary: List<String>,
            val lists: List<ListEntity>,
        )

        private class Hold(
            val listId: String,
            val language: String,
            val pcm: CappedBuffer,
            val recorder: PcmRecorder,
            val context: Deferred<Known>,
            val trace: VoiceTrace,
        )

        /** Press. False when the mic could not start, or one hold is already running. */
        fun begin(listId: String): Boolean {
            if (!available || hold != null) return false
            val trace = VoiceTrace()
            val pcm = CappedBuffer(MAX_PCM_BYTES)
            val recorder = PcmRecorder(pcm) { _, _ -> trace.micReady() }
            if (!recorder.start()) return false
            // Read while the person is still talking, from what Room already holds.
            val context = scope.async { load(listId) }
            hold = Hold(listId, _language.value, pcm, recorder, context, trace)
            // Also opens the connection and refreshes the token while the person
            // talks, so the upload on release does not pay for either.
            scope.launch { syncCorrections(force = true) }
            _state.value = VoiceState.LISTENING
            return true
        }

        /** Slid off the button, or a tap too short to be speech: throw it away. */
        fun cancel() {
            val current = hold ?: return
            hold = null
            current.recorder.stop()
            current.context.cancel()
            _state.value = VoiceState.IDLE
        }

        /** Release. The items added, or null with [onFailure] saying why. */
        suspend fun finish(onFailure: (VoiceFailure) -> Unit): List<VoiceAdded>? {
            val current = hold ?: return null
            hold = null
            val trace = current.trace
            trace.released()
            var outcome = "error"
            _state.value = VoiceState.WORKING
            trace.stage("before", "mic stop") { current.recorder.stop() }
            try {
                val known = trace.stage("before", "dictionary") { current.context.await() }
                val here = known.lists.firstOrNull { it.id == current.listId }
                val wav = trace.stage("before", "trim+wav") { clip(current.pcm, trace) }
                val audio =
                    trace.stage(
                        "before",
                        "base64",
                    ) { Base64.encodeToString(wav, Base64.NO_WRAP) }
                trace.note("dictionary", known.dictionary.size)
                val response =
                    api.command(
                        VoiceCommandRequest(
                            language = current.language,
                            listName = here?.title.orEmpty(),
                            lists = known.lists.map { it.title }.filter { it.isNotBlank() },
                            audio = audio,
                            dictionary = known.dictionary,
                            corrections = correctionsFor(current.language),
                        ),
                        trace,
                    )
                if (response == null) {
                    outcome = "unavailable"
                    onFailure(VoiceFailure.UNAVAILABLE)
                    return null
                }
                val adds = response.actions.filter { it.type == "add" && it.text.isNotBlank() }
                if (adds.isEmpty()) {
                    outcome = "not heard"
                    onFailure(VoiceFailure.NOT_HEARD)
                    return null
                }
                outcome = "${adds.size} added"
                return trace.stage("after", "save") { save(adds, known, here, current) }
            } finally {
                _state.value = VoiceState.IDLE
                trace.finish(outcome)
            }
        }

        /** Adds what voice heard through the outbox, in the order it was said. */
        private suspend fun save(
            adds: List<VoiceAction>,
            known: Known,
            here: ListEntity?,
            current: Hold,
        ): List<VoiceAdded> {
            // The person's own corrections, applied here too, so one made a moment
            // ago works even before the server has it.
            val fixes =
                memory.corrections
                    .filter { it.language == current.language }
                    .associate { VoiceDictionary.normalise(it.heard) to it.saved }
            val byName = known.lists.associateBy { VoiceDictionary.normalise(it.title) }
            val atTop = placement.newItemsOnTop.value
            val added =
                adds.map { action ->
                    val target =
                        action.list?.let { byName[VoiceDictionary.normalise(it)] } ?: here
                    VoiceAdded(
                        text =
                            fixes[VoiceDictionary.normalise(action.text)] ?: action.text.trim(),
                        listTitle = target?.title.orEmpty(),
                        onCurrentList = target == null || target.id == current.listId,
                    ) to (target?.id ?: current.listId)
                }
            // Added in the order spoken; with new items on top that means last first,
            // so the list reads top to bottom the way it was said.
            val dictated = mutableMapOf<String, Dictated>()
            for ((item, listId) in if (atTop) added.reversed() else added) {
                val id = repo.addTask(listId, item.text, atTop)
                dictated[id] = Dictated(item.text, current.language)
            }
            memory.rememberDictated(dictated)
            return added.map { it.first }
        }

        /** The recording as WAV, with the quiet either side cut off. */
        private fun clip(
            pcm: CappedBuffer,
            trace: VoiceTrace,
        ): ByteArray {
            val raw = pcm.toByteArray()
            val trimmed = Wav.trimSilence(raw, SAMPLE_RATE)
            trace.note("audio", "%.1fs".format(raw.size / BYTES_PER_SECOND))
            trace.note("sent audio", "%.1fs".format(trimmed.size / BYTES_PER_SECOND))
            return Wav.pcm16Mono(trimmed, SAMPLE_RATE)
        }

        private fun correctionsFor(language: String): List<VoiceCorrection> =
            memory.corrections
                .filter { it.language == language }
                .take(CORRECTIONS_SENT)
                .map { VoiceCorrection(it.heard, it.saved) }

        /** Rows voice added, by task id: the ones whose menu offers "Correct dictation". */
        val dictated: StateFlow<Map<String, Dictated>> = memory.dictated

        /**
         * "Correct dictation": renames the row and files what voice wrote against what
         * it should have written, in the person's own library, so the same mishearing
         * comes out right next time. Takes effect here at once; reaches the account
         * now or on the next voice use if offline.
         */
        suspend fun correct(
            taskId: String,
            text: String,
        ) {
            val entry = memory.dictated.value[taskId] ?: return
            val saved = text.trim()
            if (saved.isEmpty()) return
            repo.renameTask(taskId, saved)
            if (VoiceDictionary.normalise(saved) == VoiceDictionary.normalise(entry.text)) return
            memory.addCorrection(VoiceCorrectionRecord(entry.text, saved, entry.language))
            syncCorrections(force = true)
        }

        /** Sends corrections made here, then refreshes the cache from the account when stale. */
        private suspend fun syncCorrections(force: Boolean = false) {
            val queued = memory.pending
            if (queued.isNotEmpty()) {
                val sent = queued.takeWhile { api.addCorrection(it) }
                memory.pending = memory.pending.drop(sent.size)
                if (sent.size < queued.size) return
            }
            val now = System.currentTimeMillis()
            if (!force && now - memory.fetchedAt < FETCH_EVERY_MS) return
            val fetched = api.corrections() ?: return
            memory.corrections = fetched
            memory.fetchedAt = now
        }

        private suspend fun load(listId: String): Known =
            withContext(Dispatchers.IO) {
                val lists = db.lists().all()
                Known(
                    dictionary =
                        VoiceDictionary.build(
                            rows = db.tasks().dictionaryRows(),
                            currentListId = listId,
                            listNames = lists.map { it.title },
                        ),
                    lists = lists.filter { !it.archived || it.id == listId },
                )
            }

        private companion object {
            const val PREFS = "voice_prefs"
            const val KEY_LANGUAGE = "language"
            const val CORRECTIONS_SENT = 30
            const val FETCH_EVERY_MS = 10 * 60 * 1000L

            /** 30 seconds of 16 kHz 16-bit mono: room for a sentence of several items. */
            const val MAX_PCM_BYTES = 30 * SAMPLE_RATE * 2
            const val BYTES_PER_SECOND = SAMPLE_RATE * 2.0
        }
    }

/** Collects the recording and quietly drops anything past [limit] bytes. */
class CappedBuffer(
    private val limit: Int,
) : OutputStream() {
    private val bytes = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
        if (bytes.size() < limit) bytes.write(b)
    }

    @Synchronized
    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        val room = limit - bytes.size()
        if (room > 0) bytes.write(b, off, minOf(len, room))
    }

    @Synchronized
    fun toByteArray(): ByteArray = bytes.toByteArray()
}
