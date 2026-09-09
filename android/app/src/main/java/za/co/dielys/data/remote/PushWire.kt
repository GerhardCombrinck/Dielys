package za.co.dielys.data.remote

import kotlinx.serialization.Serializable

/**
 * Device registration, mirroring `protocol/src/push.ts`.
 *
 * There is deliberately no `deviceId` here. The server files the token under the
 * device id in the caller's access token, so a client cannot register a push
 * token against somebody else's phone (M2).
 */

const val MAX_FCM_TOKEN_LENGTH = 4096

@Serializable
data class RegisterDeviceRequest(
    val fcmToken: String,
)
