package za.co.dielys.data.voice

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import za.co.dielys.BuildConfig
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

@PublishedApi internal const val NANOS_PER_MS = 1_000_000L
private const val BYTES_PER_KB = 1024

private fun nowMs(): Long = System.nanoTime() / NANOS_PER_MS

/** One stage of a hold, in the order it happened. [group] is where the time went. */
data class VoiceStage(
    val group: String,
    val name: String,
    val ms: Long,
)

/** One finished hold: every stage, plus counts that explain them. Never what was said (D4). */
data class VoiceRun(
    val atMillis: Long,
    val outcome: String,
    val totalMs: Long,
    val stages: List<VoiceStage>,
    val info: Map<String, String>,
    /** Benchmark runs only: what came back, shown on screen, never logged (D4). */
    val detail: String = "",
) {
    /** One logcat line, `adb logcat -s VoiceTiming`. */
    fun line(): String =
        "total=${totalMs}ms outcome=$outcome " +
            stages.joinToString(" ") { "${it.name}=${it.ms}" } + " " +
            info.entries.joinToString(" ") { "${it.key}=${it.value}" }
}

/**
 * Where the time of one hold goes, from press to items saved (docs/VOICE.md
 * "Timing"). The controller marks its own stages, [VoiceNetListener] the
 * network ones, and the Worker's `Server-Timing` header its own.
 */
class VoiceTrace(
    /** Dev benchmark only: the speech model to ask the Worker for. */
    val variant: String? = null,
) {
    private val pressedAt = nowMs()

    @Volatile private var micReadyAt = 0L
    private var releasedAt = 0L
    private val phone = mutableListOf<VoiceStage>()
    private val info = linkedMapOf<String, String>()
    private val net = mutableMapOf<String, Long>()
    private var serverTiming: String? = null
    private var retried = false

    /** The first audio arrived from the mic. */
    fun micReady() {
        if (micReadyAt == 0L) micReadyAt = nowMs()
    }

    fun released() {
        releasedAt = nowMs()
    }

    @Synchronized
    fun note(
        key: String,
        value: Any,
    ) {
        info[key] = value.toString()
    }

    /** Times [block] as a phone stage ("before" the request or "after" it). */
    inline fun <T> stage(
        group: String,
        name: String,
        block: () -> T,
    ): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            add(group, name, (System.nanoTime() - started) / NANOS_PER_MS)
        }
    }

    @Synchronized
    fun add(
        group: String,
        name: String,
        ms: Long,
    ) {
        phone += VoiceStage(group, name, ms)
    }

    @Synchronized
    internal fun mark(event: String) {
        // A 401 retry repeats the call; keep the second one, which is the one that counted.
        if (event == "callStart" && net.containsKey("callStart")) {
            retried = true
            net.clear()
        }
        net[event] = nowMs()
    }

    @Synchronized
    internal fun serverTiming(header: String?) {
        serverTiming = header
    }

    /** Hands the finished hold to [VoiceTimings]. */
    @Synchronized
    fun finish(
        outcome: String,
        detail: String = "",
    ) {
        val done = nowMs()
        val stages = mutableListOf<VoiceStage>()
        if (micReadyAt > 0) stages += VoiceStage("press", "mic ready", micReadyAt - pressedAt)
        stages += phone.filter { it.group == "before" }
        stages += network()
        stages += server()
        stages += phone.filter { it.group == "after" }
        val notes = LinkedHashMap(info)
        notes["held"] = "${releasedAt - pressedAt}ms"
        if (retried) notes["retried"] = "401"
        if (variant != null) notes["variant"] = variant
        VoiceTimings.add(
            VoiceRun(
                atMillis = System.currentTimeMillis(),
                outcome = outcome,
                totalMs = done - releasedAt,
                stages = stages,
                info = notes,
                detail = detail,
            ),
        )
    }

    private fun span(
        from: String,
        to: String,
    ): Long? {
        val a = net[from] ?: return null
        val b = net[to] ?: return null
        return b - a
    }

    private fun network(): List<VoiceStage> {
        if (net.isEmpty()) return emptyList()
        info["connection"] = if (net.containsKey("connectStart")) "new" else "reused"
        return listOfNotNull(
            span("dnsStart", "dnsEnd")?.let { VoiceStage("network", "dns", it) },
            span(
                "connectStart",
                net["secureConnectStart"]?.let { "secureConnectStart" } ?: "connectEnd",
            )?.let { VoiceStage("network", "tcp", it) },
            span(
                "secureConnectStart",
                "secureConnectEnd",
            )?.let { VoiceStage("network", "tls", it) },
            span(
                "requestHeadersStart",
                "requestBodyEnd",
            )?.let { VoiceStage("network", "upload", it) },
            span(
                "requestBodyEnd",
                "responseHeadersStart",
            )?.let { VoiceStage("network", "wait", it) },
            span(
                "responseHeadersStart",
                "responseBodyEnd",
            )?.let { VoiceStage("network", "download", it) },
        )
    }

    /**
     * The Worker's stages, plus what is left of the round trip once they are taken
     * away: latency both ways, upload, download and Cloudflare's own routing.
     */
    private fun server(): List<VoiceStage> {
        val header = serverTiming ?: return emptyList()
        val stages = mutableListOf<VoiceStage>()
        var total: Long? = null
        for (entry in header.split(",")) {
            val parts = entry.trim().split(";")
            val name = parts.first().trim()
            val dur =
                parts
                    .firstOrNull {
                        it.trim().startsWith("dur=")
                    }?.substringAfter("dur=")
                    ?.toDoubleOrNull()
            val desc =
                parts
                    .firstOrNull {
                        it.trim().startsWith(
                            "desc=",
                        )
                    }?.substringAfter("desc=")
                    ?.trim('"')
            when {
                dur != null && name == "total" -> total = dur.toLong()
                dur != null -> stages += VoiceStage("server", name, dur.toLong())
                desc != null -> info[name] = desc
            }
        }
        val roundTrip = span("callStart", "responseBodyEnd")
        if (total != null && roundTrip != null) {
            stages += VoiceStage("server", "worker total", total)
            stages += VoiceStage("network", "round trip minus worker", roundTrip - total)
        }
        return stages
    }
}

/** The last holds, newest first, for the debug timing screen. In memory only. */
object VoiceTimings {
    private const val KEEP = 100
    private const val TAG = "VoiceTiming"

    private val _runs = MutableStateFlow<List<VoiceRun>>(emptyList())
    val runs: StateFlow<List<VoiceRun>> = _runs.asStateFlow()

    fun add(run: VoiceRun) {
        _runs.update { (listOf(run) + it).take(KEEP) }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, run.line())
            // Benchmark runs only: scripted phrases, not anyone's own dictation (D4).
            if (run.detail.isNotEmpty()) Log.i(TAG, "  " + run.detail)
        }
    }

    fun clear() {
        _runs.value = emptyList()
    }
}

/** Marks OkHttp's phases of one voice call on its [VoiceTrace]. */
class VoiceNetListener(
    private val trace: VoiceTrace,
) : EventListener() {
    override fun callStart(call: Call) = trace.mark("callStart")

    override fun dnsStart(
        call: Call,
        domainName: String,
    ) = trace.mark("dnsStart")

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<java.net.InetAddress>,
    ) = trace.mark("dnsEnd")

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
    ) = trace.mark("connectStart")

    override fun secureConnectStart(call: Call) = trace.mark("secureConnectStart")

    override fun secureConnectEnd(
        call: Call,
        handshake: okhttp3.Handshake?,
    ) = trace.mark("secureConnectEnd")

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: okhttp3.Protocol?,
    ) = trace.mark("connectEnd")

    override fun requestHeadersStart(call: Call) = trace.mark("requestHeadersStart")

    override fun requestBodyEnd(
        call: Call,
        byteCount: Long,
    ) {
        trace.mark("requestBodyEnd")
        trace.note("sentKb", byteCount / BYTES_PER_KB)
    }

    override fun responseHeadersStart(call: Call) = trace.mark("responseHeadersStart")

    override fun responseHeadersEnd(
        call: Call,
        response: Response,
    ) {
        trace.serverTiming(response.header("Server-Timing"))
        trace.note("protocol", response.protocol)
    }

    override fun responseBodyEnd(
        call: Call,
        byteCount: Long,
    ) = trace.mark("responseBodyEnd")

    override fun callFailed(
        call: Call,
        ioe: IOException,
    ) = trace.note("failed", ioe.javaClass.simpleName)
}
