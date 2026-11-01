package za.co.dielys.data.voice

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import za.co.dielys.data.remote.DielysJson
import za.co.dielys.data.remote.VoiceCorrectionRecord
import za.co.dielys.domain.VoiceDictionary
import javax.inject.Inject
import javax.inject.Singleton

/** What voice wrote for one row it added, so that row can offer "Correct dictation". */
@Serializable
data class Dictated(
    val text: String,
    val language: String,
)

/**
 * This phone's memory for voice (ADR 0016), in plain preferences: which rows
 * voice added and what it wrote for each, the person's corrections as last
 * fetched from their account, and corrections made here that have not reached
 * the server yet. The server is the record; this is a cache that lets a
 * correction take effect at once and survive being offline.
 */
@Singleton
class VoiceMemory
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )
        private val dictatedSerializer = MapSerializer(String.serializer(), Dictated.serializer())
        private val recordsSerializer = ListSerializer(VoiceCorrectionRecord.serializer())

        private val _dictated = MutableStateFlow(read(KEY_DICTATED, dictatedSerializer, emptyMap()))

        /** Rows voice added, by task id. */
        val dictated: StateFlow<Map<String, Dictated>> = _dictated.asStateFlow()

        /** Newest first. */
        var corrections: List<VoiceCorrectionRecord>
            get() = read(KEY_CORRECTIONS, recordsSerializer, emptyList())
            set(value) = write(KEY_CORRECTIONS, recordsSerializer, value.take(MAX_CORRECTIONS))

        /** Made on this phone, not yet accepted by the server. Oldest first. */
        var pending: List<VoiceCorrectionRecord>
            get() = read(KEY_PENDING, recordsSerializer, emptyList())
            set(value) = write(KEY_PENDING, recordsSerializer, value)

        /** Milliseconds since boot-independent epoch of the last fetch from the server. */
        var fetchedAt: Long
            get() = prefs.getLong(KEY_FETCHED_AT, 0L)
            set(value) = prefs.edit().putLong(KEY_FETCHED_AT, value).apply()

        fun rememberDictated(entries: Map<String, Dictated>) {
            if (entries.isEmpty()) return
            // Newest last; the oldest fall off once there are more than anyone corrects.
            val next =
                (_dictated.value + entries)
                    .entries
                    .toList()
                    .takeLast(
                        MAX_DICTATED,
                    ).associate {
                        it.toPair()
                    }
            write(KEY_DICTATED, dictatedSerializer, next)
            _dictated.value = next
        }

        /** Files a correction locally at once: the cache (replacing the same mishearing) and the upload queue. */
        fun addCorrection(record: VoiceCorrectionRecord) {
            val key = VoiceDictionary.normalise(record.heard)
            corrections =
                listOf(record) +
                corrections.filterNot {
                    VoiceDictionary.normalise(it.heard) == key &&
                        it.language == record.language
                }
            pending = pending + record
        }

        private fun <T> read(
            key: String,
            serializer: kotlinx.serialization.KSerializer<T>,
            default: T,
        ): T {
            val raw = prefs.getString(key, null) ?: return default
            return runCatching {
                DielysJson.wire.decodeFromString(
                    serializer,
                    raw,
                )
            }.getOrDefault(default)
        }

        private fun <T> write(
            key: String,
            serializer: kotlinx.serialization.KSerializer<T>,
            value: T,
        ) {
            prefs.edit().putString(key, DielysJson.wire.encodeToString(serializer, value)).apply()
        }

        private companion object {
            const val PREFS = "voice_memory"
            const val KEY_DICTATED = "dictated"
            const val KEY_CORRECTIONS = "corrections"
            const val KEY_PENDING = "pending"
            const val KEY_FETCHED_AT = "fetched_at"
            const val MAX_DICTATED = 500
            const val MAX_CORRECTIONS = 500
        }
    }
