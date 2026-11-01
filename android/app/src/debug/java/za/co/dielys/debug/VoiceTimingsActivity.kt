package za.co.dielys.debug

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import za.co.dielys.data.voice.VoiceRun
import za.co.dielys.data.voice.VoiceTimings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the time of a hold goes, by group, so the bar adds up to the total. */
private val GROUPS =
    listOf(
        "before" to Color(0xFF5B8DEF),
        "network" to Color(0xFFE8A33D),
        "server" to Color(0xFF4CAF7A),
        "after" to Color(0xFF9C6ADE),
        "other" to Color(0xFF9E9E9E),
    )

/**
 * The voice timing screen (docs/VOICE.md "Timing"). Debug builds only. Use the
 * app as normal, hold to talk a few times, then open this from the launcher:
 * the table says which stage costs most at the median and the 90th percentile,
 * and each run below shows its own breakdown. Also in `adb logcat -s VoiceTiming`.
 */
class VoiceTimingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val all by VoiceTimings.runs.collectAsState()
                    var benchOnly by remember { mutableStateOf(false) }
                    val runs = if (benchOnly) all.filter { "bench" in it.info } else all
                    Column(
                        modifier =
                            Modifier
                                .safeDrawingPadding()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Voice timings", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${runs.size} holds this session, newest first. Times run from " +
                                "release to items saved.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = runs.isNotEmpty(), onClick = { copy(csv(runs)) }) {
                                Text("Copy CSV")
                            }
                            OutlinedButton(onClick = { VoiceTimings.clear() }) { Text("Clear") }
                            OutlinedButton(onClick = { benchOnly = !benchOnly }) {
                                Text(if (benchOnly) "Bench only" else "All runs")
                            }
                        }
                        Bench()
                        Legend()
                        if (runs.isEmpty()) {
                            Text("Hold to talk in the app, then come back here.")
                        } else {
                            Summary(runs)
                            HorizontalDivider()
                            runs.forEach { Run(it) }
                        }
                    }
                }
            }
        }
    }

    /**
     * The benchmark set: hold a row's button and say its phrase to record it once,
     * then Replay sends the saved audio through the real client three times.
     */
    @Composable
    private fun Bench() {
        val scope = rememberCoroutineScope()
        var saved by remember { mutableIntStateOf(0) }
        var recording by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val permission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val api =
            remember {
                EntryPointAccessors
                    .fromApplication(applicationContext, VoiceBenchEntryPoint::class.java)
                    .voiceApi()
            }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Benchmark", fontWeight = FontWeight.Bold)
            Text(
                "Hold a row's Rec and say the phrase, once. Replay sends the same audio " +
                    "every time, so a server change is measured like for like.",
                style = MaterialTheme.typography.bodySmall,
            )
            BENCH_PROMPTS.forEach { prompt ->
                val file = benchFile(this@VoiceTimingsActivity, prompt)
                // Read again whenever a sample is saved.
                val status = saved.let { if (file.exists()) "%.1fs".format(seconds(file)) else "—" }
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("${prompt.id} · \"${prompt.say}\"", fontSize = 13.sp)
                        Text(
                            status,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val active = recording == prompt.id
                    Box(
                        Modifier
                            .width(64.dp)
                            .height(36.dp)
                            .background(
                                if (active) {
                                    Color(
                                        0xFFD9534F,
                                    )
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            ).pointerInput(prompt.id) {
                                // Consumes every move so the scrolling screen cannot take
                                // the gesture over and end the recording mid-phrase.
                                awaitEachGesture {
                                    awaitFirstDown().consume()
                                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        permission.launch(Manifest.permission.RECORD_AUDIO)
                                        return@awaitEachGesture
                                    }
                                    val recorder = BenchRecorder(file)
                                    if (!recorder.start()) return@awaitEachGesture
                                    recording = prompt.id
                                    do {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                    recorder.stop()
                                    recording = null
                                    saved++
                                }
                            },
                    ) {
                        Text(
                            if (active) "…" else "Rec",
                            Modifier.padding(8.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            repeat(REPLAYS) {
                                BENCH_PROMPTS.forEach { prompt ->
                                    val file = benchFile(this@VoiceTimingsActivity, prompt)
                                    if (file.exists()) replay(api, prompt, file)
                                }
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Replaying…" else "Replay recorded ×$REPLAYS") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            // Interleaved, so a slow patch on Cloudflare's side hits every model alike.
                            repeat(COMPARE_ROUNDS) {
                                BENCH_PROMPTS.forEach { prompt ->
                                    val file = benchFile(this@VoiceTimingsActivity, prompt)
                                    if (file.exists()) {
                                        STT_VARIANTS.forEach { replay(api, prompt, file, it) }
                                    }
                                }
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Compare speech models ×$COMPARE_ROUNDS") }
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Voice timings", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}

private const val REPLAYS = 3
private const val COMPARE_ROUNDS = 2

/** The run split into the bar's groups; "other" is whatever no stage covered. */
private fun groups(run: VoiceRun): List<Pair<String, Long>> {
    fun sum(group: String) = run.stages.filter { it.group == group }.sumOf { it.ms }
    val worker = run.stages.firstOrNull { it.name == "worker total" }?.ms
    val network =
        run.stages.firstOrNull { it.name == "round trip minus worker" }?.ms
            ?: run.stages.filter { it.group == "network" }.sumOf { it.ms }
    val parts =
        listOf(
            "before" to sum("before"),
            "network" to network,
            "server" to (worker ?: 0L),
            "after" to sum("after"),
        )
    val other = (run.totalMs - parts.sumOf { it.second }).coerceAtLeast(0)
    return parts + ("other" to other)
}

private fun percentile(
    values: List<Long>,
    p: Double,
): Long {
    val sorted = values.sorted()
    return sorted[((sorted.size - 1) * p).toInt()]
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GROUPS.forEach { (name, color) ->
            Row {
                Box(Modifier.width(10.dp).height(10.dp).background(color))
                Spacer(Modifier.width(4.dp))
                Text(name, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Bar(parts: List<Pair<String, Long>>) {
    val total = parts.sumOf { it.second }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(14.dp)) {
        parts.filter { it.second > 0 }.forEach { (group, ms) ->
            val color = GROUPS.first { it.first == group }.second
            Box(Modifier.weight(ms.toFloat() / total).fillMaxHeight().background(color))
        }
    }
}

/** Median, p90 and worst per stage, in the order stages happen. */
@Composable
private fun Summary(runs: List<VoiceRun>) {
    val names = runs.reversed().flatMap { r -> r.stages.map { it.group to it.name } }.distinct()
    val totals = runs.map { it.totalMs }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Across ${runs.size} holds", fontWeight = FontWeight.Bold)
        Bar(
            GROUPS.map { (g, _) ->
                g to
                    percentile(runs.map { r -> groups(r).first { it.first == g }.second }, 0.5)
            },
        )
        TableRow("stage", "p50", "p90", "max", bold = true)
        TableRow(
            "TOTAL",
            "${percentile(totals, 0.5)}",
            "${percentile(totals, 0.9)}",
            "${totals.max()}",
            bold = true,
        )
        names.forEach { (group, name) ->
            val values =
                runs.mapNotNull { r ->
                    r.stages
                        .firstOrNull {
                            it.group == group &&
                                it.name == name
                        }?.ms
                }
            TableRow(
                "$group · $name",
                "${percentile(values, 0.5)}",
                "${percentile(values, 0.9)}",
                "${values.max()}",
            )
        }
    }
}

@Composable
private fun TableRow(
    name: String,
    p50: String,
    p90: String,
    max: String,
    bold: Boolean = false,
) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth()) {
        Text(name, Modifier.weight(1f), fontSize = 12.sp, fontWeight = weight)
        listOf(p50, p90, max).forEach {
            Text(
                it,
                Modifier.width(52.dp),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = weight,
            )
        }
    }
}

@Composable
private fun Run(run: VoiceRun) {
    val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(run.atMillis))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$time · ${run.totalMs} ms · ${run.outcome}", fontWeight = FontWeight.Bold)
        Bar(groups(run))
        run.stages.forEach {
            Row(Modifier.fillMaxWidth()) {
                Text("${it.group} · ${it.name}", Modifier.weight(1f), fontSize = 12.sp)
                Text("${it.ms} ms", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
        if (run.detail.isNotEmpty()) Text(run.detail, fontSize = 12.sp)
        Text(
            run.info.entries.joinToString("  ") { "${it.key}=${it.value}" },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** One row per hold, one column per stage and note, for a spreadsheet. */
private fun csv(runs: List<VoiceRun>): String {
    val stageNames = runs.flatMap { r -> r.stages.map { "${it.group}.${it.name}" } }.distinct()
    val infoNames = runs.flatMap { it.info.keys }.distinct()
    val header = listOf("time", "outcome", "total") + stageNames + infoNames
    val rows =
        runs.reversed().map { run ->
            val stages = run.stages.associate { "${it.group}.${it.name}" to it.ms.toString() }
            listOf(run.atMillis.toString(), run.outcome, run.totalMs.toString()) +
                stageNames.map { stages[it].orEmpty() } +
                infoNames.map { run.info[it].orEmpty() }
        }
    return (listOf(header) + rows).joinToString("\n") { row ->
        row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
    }
}
