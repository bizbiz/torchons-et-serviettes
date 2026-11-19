package com.torchons.app.data.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import retrofit2.Response

/**
 * A server answer with status >= 400, carried as an exception so that the
 * UI layer ([com.torchons.app.ui.common.UserErrors]) can tell "the server
 * said no" from "the server was never reached".
 *
 * The message is technical and must never reach the screen: the UI shows
 * what the translator builds from [httpCode], [errorId] and [detail].
 */
open class ApiHttpException(
    val httpCode: Int,
    val errorBody: String = "",
    val errorId: String? = null,
) : Exception("HTTP $httpCode: $errorBody") {

    /** The `detail` key of a JSON error body, when it is a plain string. */
    val detail: String? by lazy { jsonObject(errorBody)?.stringField("detail") }

    companion object {
        const val ERROR_ID_HEADER = "X-Error-Id"

        // Server contract: "E-" + 5 chars of this alphabet (no 0, 1, I, L, N, O, U).
        private val ERROR_ID_FORMAT = Regex("^E-[23456789ABCDEFGHJKMNPQRSTVWXYZ]{5}$")

        /** Header first, JSON body `error_id` as a fallback; null when absent or malformed. */
        fun extractErrorId(header: String?, body: String): String? {
            header?.trim()?.takeIf { ERROR_ID_FORMAT.matches(it) }?.let { return it }
            val fromBody = jsonObject(body)?.stringField("error_id")?.trim()
            return fromBody?.takeIf { ERROR_ID_FORMAT.matches(it) }
        }

        /** Builds the exception from a failed Retrofit response (consumes its error body). */
        fun from(resp: Response<*>): ApiHttpException {
            val body = runCatching { resp.errorBody()?.string().orEmpty() }.getOrDefault("")
            return ApiHttpException(
                httpCode = resp.code(),
                errorBody = body,
                errorId = extractErrorId(resp.headers()[ERROR_ID_HEADER], body),
            )
        }

        private fun jsonObject(body: String): JsonObject? = runCatching {
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()

        private fun JsonObject.stringField(name: String): String? =
            get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }
}
