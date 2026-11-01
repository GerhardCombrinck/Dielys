package za.co.dielys.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import za.co.dielys.data.voice.VoiceNetListener
import za.co.dielys.data.voice.VoiceTrace
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Voice calls (ADR 0016). Null or false on any failure; callers treat that as an ordinary answer. */
interface VoiceApi {
    /** [trace], when given, collects where the time went (debug timing screen). */
    suspend fun command(
        request: VoiceCommandRequest,
        trace: VoiceTrace? = null,
    ): VoiceCommandResponse?

    /** `GET /voice/corrections`, newest first. */
    suspend fun corrections(): List<VoiceCorrectionRecord>?

    /** `POST /voice/corrections`. True once the server has it. */
    suspend fun addCorrection(record: VoiceCorrectionRecord): Boolean
}

private const val HTTP_UNAUTHORIZED = 401
private const val CALL_TIMEOUT_MS = 12_000L
private const val TAG = "Voice"

/**
 * Its own small client rather than more functions on [HttpSyncApi]: the calls
 * have a hard time limit, no retry, and failure is an ordinary answer here, not
 * an error to surface. Logs status codes only, never what was said.
 */
@Singleton
class HttpVoiceApi
    @Inject
    constructor(
        client: OkHttpClient,
        private val baseUrl: HttpUrl,
        private val tokens: AccessTokens,
    ) : VoiceApi {
        private val client =
            client
                .newBuilder()
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .eventListenerFactory { call ->
                    call.request().tag(VoiceTrace::class.java)?.let(::VoiceNetListener)
                        ?: EventListener.NONE
                }.build()
        private val json = "application/json".toMediaType()

        override suspend fun command(
            request: VoiceCommandRequest,
            trace: VoiceTrace?,
        ): VoiceCommandResponse? {
            val body =
                timed(trace, "before", "json") {
                    DielysJson.outbound
                        .encodeToString(VoiceCommandRequest.serializer(), request)
                        .toRequestBody(json)
                }
            val text = call("command", body, trace) ?: return null
            return timed(trace, "after", "decode") {
                decode {
                    DielysJson.wire.decodeFromString(VoiceCommandResponse.serializer(), text)
                }
            }
        }

        private inline fun <T> timed(
            trace: VoiceTrace?,
            group: String,
            name: String,
            block: () -> T,
        ): T = if (trace == null) block() else trace.stage(group, name, block)

        override suspend fun corrections(): List<VoiceCorrectionRecord>? =
            call("corrections", body = null)?.let {
                decode {
                    DielysJson.wire
                        .decodeFromString(
                            VoiceCorrectionsResponse.serializer(),
                            it,
                        ).corrections
                }
            }

        override suspend fun addCorrection(record: VoiceCorrectionRecord): Boolean =
            call(
                "corrections",
                DielysJson.wire
                    .encodeToString(
                        VoiceCorrectionRecord.serializer(),
                        record,
                    ).toRequestBody(json),
            ) != null

        private fun <T> decode(block: () -> T): T? =
            try {
                block()
            } catch (_: SerializationException) {
                Log.w(TAG, "answer unreadable")
                null
            }

        /** The body text on success (empty for 204), null on failure. Refreshes once on a 401. */
        private suspend fun call(
            path: String,
            body: RequestBody?,
            trace: VoiceTrace? = null,
        ): String? =
            withContext(Dispatchers.IO) {
                val url =
                    baseUrl
                        .newBuilder()
                        .addPathSegment("voice")
                        .addPathSegment(path)
                        .build()
                val started = System.currentTimeMillis()
                try {
                    val token = timed(trace, "before", "token") { tokens.current() }
                    var text = send(url, body, token, trace)
                    if (text == UNAUTHORIZED) {
                        val refreshed =
                            timed(trace, "before", "token refresh") { tokens.refreshed() }
                                ?: return@withContext null
                        text = send(url, body, refreshed, trace)
                    }
                    Log.i(TAG, "$path took ${System.currentTimeMillis() - started} ms")
                    text.takeUnless { it == UNAUTHORIZED }
                } catch (error: IOException) {
                    Log.w(TAG, "$path failed: ${error.javaClass.simpleName}")
                    null
                }
            }

        private fun send(
            url: HttpUrl,
            body: RequestBody?,
            token: String?,
            trace: VoiceTrace?,
        ): String? {
            val builder = Request.Builder().url(url)
            if (trace != null) builder.tag(VoiceTrace::class.java, trace)
            trace?.variant?.let { builder.header("X-Voice-Stt", it) }
            if (body == null) builder.get() else builder.post(body)
            if (token != null) builder.header("Authorization", "Bearer $token")
            client.newCall(builder.build()).execute().use {
                if (it.code == HTTP_UNAUTHORIZED) return UNAUTHORIZED
                if (!it.isSuccessful) Log.w(TAG, "${url.encodedPath} answered ${it.code}")
                return if (it.isSuccessful) it.body.string() else null
            }
        }

        private companion object {
            const val UNAUTHORIZED = "\u0000unauthorized"
        }
    }
