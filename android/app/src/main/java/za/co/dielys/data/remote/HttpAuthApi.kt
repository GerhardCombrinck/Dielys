package za.co.dielys.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_SERVER_ERROR = 500

/**
 * OkHttp implementation of [AuthApi].
 *
 * No bearer token on any of these: the client has none yet, or the one it has is
 * dead. So there is no refresh-once-on-401 path here — a 401 is the answer, not a
 * thing to retry.
 */
@Singleton
class HttpAuthApi
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val baseUrl: HttpUrl,
    ) : AuthApi {
        private val jsonMedia = "application/json".toMediaType()

        override suspend fun login(
            email: String,
            password: String,
            deviceId: String,
        ): TokenPair =
            post(
                "login",
                DielysJson.wire.encodeToString(
                    LoginRequest.serializer(),
                    LoginRequest(email, password, deviceId),
                ),
            )

        override suspend fun register(
            email: String,
            password: String,
            deviceId: String,
        ): TokenPair =
            post(
                "register",
                DielysJson.wire.encodeToString(
                    RegisterRequest.serializer(),
                    RegisterRequest(email, password, deviceId),
                ),
            )

        override suspend fun refresh(
            refreshToken: String,
            deviceId: String,
        ): TokenPair =
            post(
                "refresh",
                DielysJson.wire.encodeToString(
                    RefreshRequest.serializer(),
                    RefreshRequest(refreshToken, deviceId),
                ),
            )

        override suspend fun requestMagicLink(email: String): RequestMagicLinkResponse =
            postJson(
                listOf("auth", "magic", "request"),
                DielysJson.wire.encodeToString(
                    RequestMagicLinkRequest.serializer(),
                    RequestMagicLinkRequest(email),
                ),
                RequestMagicLinkResponse.serializer(),
            )

        override suspend fun verifyMagicLink(
            token: String,
            deviceId: String,
        ): TokenPair =
            postJson(
                listOf("auth", "magic", "verify"),
                DielysJson.wire.encodeToString(
                    VerifyMagicLinkRequest.serializer(),
                    VerifyMagicLinkRequest(token, deviceId),
                ),
                TokenPair.serializer(),
            )

        private suspend fun post(
            action: String,
            body: String,
        ): TokenPair = postJson(listOf("auth", action), body, TokenPair.serializer())

        private suspend fun <T> postJson(
            pathSegments: List<String>,
            body: String,
            serializer: KSerializer<T>,
        ): T =
            withContext(Dispatchers.IO) {
                var builder = baseUrl.newBuilder()
                for (segment in pathSegments) builder = builder.addPathSegment(segment)
                val request =
                    Request
                        .Builder()
                        .url(builder.build())
                        .post(body.toRequestBody(jsonMedia))
                        .build()

                val response =
                    try {
                        client.newCall(request).execute()
                    } catch (error: IOException) {
                        throw ApiException.Transport(error)
                    }

                response.use {
                    val text =
                        try {
                            it.body.string()
                        } catch (error: IOException) {
                            throw ApiException.Transport(error)
                        }
                    if (!it.isSuccessful) throw failure(it.code, text)
                    decode(text, serializer)
                }
            }

        private fun failure(
            status: Int,
            body: String,
        ): ApiException {
            val code =
                try {
                    DielysJson.wire.decodeFromString(ServerError.serializer(), body).code
                } catch (_: SerializationException) {
                    null
                }
            return if (status >= HTTP_SERVER_ERROR) {
                ApiException.Unavailable(status, code)
            } else {
                ApiException.Rejected(status, code)
            }
        }

        private fun <T> decode(
            body: String,
            serializer: KSerializer<T>,
        ): T =
            try {
                DielysJson.wire.decodeFromString(serializer, body)
            } catch (error: SerializationException) {
                throw ApiException.Unavailable(HTTP_SERVER_ERROR, error.message, error)
            }
    }
