package za.co.dielys.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_SERVER_ERROR = 500

/** Parses the configured base URL once, at wiring time, so a typo fails at start-up. */
fun baseUrlOf(value: String): HttpUrl = value.toHttpUrl()

/**
 * OkHttp implementation of [SyncApi].
 *
 * Every request carries the access token as `Authorization: Bearer <jwt>`. A 401 is
 * retried exactly once after a refresh: the Worker authorises before anything
 * reaches a `ListRoom` (L3), so an access token expiring part-way through a long
 * drain is normal and should not fail the batch. A second 401 means the session is
 * gone, and looping on it would be a hot retry against the auth endpoint.
 */
@Singleton
class HttpSyncApi
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val baseUrl: HttpUrl,
        private val tokens: AccessTokens,
    ) : SyncApi {
        private val jsonMedia = "application/json".toMediaType()

        override suspend fun mutate(
            listId: String,
            body: String,
        ): MutationAck {
            val text =
                request(
                    url = url("lists", listId, "mutate"),
                    body = body.toRequestBody(jsonMedia),
                )
            return decode(MutationAck.serializer(), text)
        }

        override suspend fun changes(
            listId: String,
            since: Long,
        ): CatchUpResponse {
            val url =
                url("lists", listId, "changes")
                    .newBuilder()
                    .addQueryParameter("since", since.toString())
                    .build()
            return decode(CatchUpResponse.serializer(), request(url, body = null))
        }

        override suspend fun claimList(listId: String) {
            // The response is a membership record the caller does not need: claiming
            // a list this account already holds is a no-op, so a retry is harmless.
            request(url("lists", listId), "{}".toRequestBody(jsonMedia))
        }

        override suspend fun memberships(): List<Membership> =
            decode(
                MembershipsResponse.serializer(),
                request(url("auth", "memberships"), body = null),
            ).memberships

        private fun url(vararg segments: String): HttpUrl =
            baseUrl.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

        /** Sends the request, refreshing once on a 401. */
        private suspend fun request(
            url: HttpUrl,
            body: RequestBody?,
        ): String =
            withContext(Dispatchers.IO) {
                val first = send(url, body, tokens.current())
                if (first.code != HTTP_UNAUTHORIZED) {
                    return@withContext readOrThrow(first)
                }

                first.close()
                val refreshed = tokens.refreshed() ?: throw ApiException.Unauthorized(null)
                readOrThrow(send(url, body, refreshed))
            }

        private fun send(
            url: HttpUrl,
            body: RequestBody?,
            token: String?,
        ): Response {
            val builder = Request.Builder().url(url)
            if (body == null) builder.get() else builder.post(body)
            if (token != null) builder.header("Authorization", "Bearer $token")
            return try {
                client.newCall(builder.build()).execute()
            } catch (error: IOException) {
                throw ApiException.Transport(error)
            }
        }

        private fun readOrThrow(response: Response): String {
            response.use {
                val text =
                    try {
                        it.body.string()
                    } catch (error: IOException) {
                        throw ApiException.Transport(error)
                    }
                if (it.isSuccessful) return text
                throw failure(it.code, errorCode(text))
            }
        }

        private fun failure(
            status: Int,
            code: String?,
        ): ApiException =
            when {
                status == HTTP_UNAUTHORIZED -> ApiException.Unauthorized(code)
                status >= HTTP_SERVER_ERROR -> ApiException.Unavailable(status, code)
                else -> ApiException.Rejected(status, code)
            }

        /** Errors carry a stable code and never a stack or an internal message (D4). */
        private fun errorCode(body: String): String? =
            try {
                DielysJson.wire.decodeFromString(ServerError.serializer(), body).code
            } catch (_: SerializationException) {
                null
            }

        private fun <T> decode(
            serializer: DeserializationStrategy<T>,
            body: String,
        ): T =
            try {
                DielysJson.wire.decodeFromString(serializer, body)
            } catch (error: SerializationException) {
                // A 200 this build cannot read is a server problem, not a client
                // bug, so it is retried rather than dropped on the floor.
                throw ApiException.Unavailable(HTTP_SERVER_ERROR, error.message, error)
            }
    }
