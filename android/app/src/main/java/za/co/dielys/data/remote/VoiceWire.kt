package za.co.dielys.data.remote

import kotlinx.serialization.Serializable

/** Mirrors `protocol/src/voice.ts` (ADR 0016). */
@Serializable
data class VoiceCorrection(
    val heard: String,
    val saved: String,
)

/**
 * `POST /voice/command`: one spoken instruction. [listName] is the list on
 * screen, [lists] every list the person has; [audio] is a base64 16 kHz mono
 * PCM WAV.
 */
@Serializable
data class VoiceCommandRequest(
    val language: String,
    val listName: String,
    val lists: List<String>,
    val audio: String,
    val dictionary: List<String>,
    val corrections: List<VoiceCorrection> = emptyList(),
)

/** One thing to do; only `add` exists. [list] is one of the sent names, or null for the list on screen. */
@Serializable
data class VoiceAction(
    val type: String,
    val text: String,
    val list: String? = null,
)

@Serializable
data class VoiceCommandResponse(
    val heard: String,
    val actions: List<VoiceAction>,
)

/** One deliberate correction (`POST /voice/corrections`), mirroring `VoiceCorrectionRecord`. */
@Serializable
data class VoiceCorrectionRecord(
    val heard: String,
    val saved: String,
    val language: String,
)

/** `GET /voice/corrections`, newest first. */
@Serializable
data class VoiceCorrectionsResponse(
    val corrections: List<VoiceCorrectionRecord>,
)
