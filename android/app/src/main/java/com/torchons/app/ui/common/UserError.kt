package com.torchons.app.ui.common

import android.content.Context
import android.util.Log
import com.torchons.app.R
import com.torchons.app.data.api.ApiHttpException
import retrofit2.Response
import java.io.IOException

/**
 * What a screen shows for a failure: a readable [message], the server
 * [errorId] when one was received, and whether the "Send to Quentin" button
 * goes with it ([reportable]). Plain messages the screen wrote itself
 * ("title required"...) use the one-argument constructor: no button.
 */
data class UserError(
    val message: String,
    val errorId: String? = null,
    val reportable: Boolean = false,
)

/** The four texts of the translator, so the logic runs in JVM tests without Android resources. */
interface UserErrorTexts {
    fun unreachable(): String
    fun serverWithCode(errorId: String): String
    fun serverWithStatus(status: Int): String
    fun unexpected(): String
}

/**
 * The single translator from a failure to what the user sees. Nothing here
 * ever puts an exception's own message on screen.
 *
 *  1. no contact with the server (IOException and cousins) -> unreachable, button, no code
 *  2. a server answer the screen can name ([known]) -> that message, no button
 *  3. any other server answer -> "(code E-XXXXX)", or "(erreur 500)" without an id, button
 *  4. any other local exception -> unexpected, button
 */
object UserErrors {

    fun translate(
        t: Throwable,
        texts: UserErrorTexts,
        known: (ApiHttpException) -> String? = { null },
    ): UserError {
        val http = t.causeChain().filterIsInstance<ApiHttpException>().firstOrNull()
        if (http != null) {
            known(http)?.let { return UserError(it) }
            val id = http.errorId
            return if (id != null) {
                UserError(texts.serverWithCode(id), errorId = id, reportable = true)
            } else {
                UserError(texts.serverWithStatus(http.httpCode), reportable = true)
            }
        }
        if (t.causeChain().any { it is IOException }) {
            return UserError(texts.unreachable(), reportable = true)
        }
        return UserError(texts.unexpected(), reportable = true)
    }

    /** Android entry point for a caught exception. The raw message only goes to the log. */
    fun from(
        context: Context,
        t: Throwable,
        known: (ApiHttpException) -> String? = { null },
    ): UserError {
        val result = translate(t, ContextTexts(context.applicationContext), known)
        Log.e("UserErrors", "shown as \"${result.message}\"", t)
        return result
    }

    /** Android entry point for a failed (non-2xx) Retrofit response that was not thrown. */
    fun fromResponse(
        context: Context,
        resp: Response<*>,
        known: (ApiHttpException) -> String? = { null },
    ): UserError = from(context, ApiHttpException.from(resp), known)

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause.takeIf { c -> c !== it } }.take(8)

    private class ContextTexts(private val context: Context) : UserErrorTexts {
        override fun unreachable() = context.getString(R.string.user_error_unreachable)
        override fun serverWithCode(errorId: String) = context.getString(R.string.user_error_server_code, errorId)
        override fun serverWithStatus(status: Int) = context.getString(R.string.user_error_server_status, status)
        override fun unexpected() = context.getString(R.string.user_error_unexpected)
    }
}
