package com.torchons.app.ui.common

import com.torchons.app.data.api.ApiHttpException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/** What an online-only gesture ended in: the server's answer, or the error to show. */
sealed interface OnlineResult<out T> {
    /** A 2xx answer; [body] is null when the server sent none. */
    data class Done<T>(val body: T?) : OnlineResult<T>

    /** An exception or a non-2xx answer, already translated for the screen. */
    data class Failed(val error: UserError) : OnlineResult<Nothing>
}

/** The error to show, or null when the gesture went through. */
val OnlineResult<*>.errorOrNull: UserError? get() = (this as? OnlineResult.Failed)?.error

/**
 * The ONE way an online-only gesture talks to the server (2026-11-20 audit, D7: a dozen taps did
 * their request in a `try { ... } catch (_: Exception) {}` and told nobody when it failed).
 *
 * Runs [call]. A 2xx answer is [OnlineResult.Done]. An exception, or an answer that is not 2xx
 * (it is NOT an exception with Retrofit: `isSuccessful` has to be read, and was not), runs
 * [rollback] (the caller undoes its optimistic change there) and is [OnlineResult.Failed] with
 * the error [errorOf] builds, normally `UserErrors.from(context, it, known)`: translated, never
 * the raw message.
 *
 * Cancellation propagates untouched: the screen is gone, there is nobody to tell and nothing on
 * screen to undo. [rollback] must not throw, and must not wait on the network.
 */
suspend fun <T> runOnline(
    errorOf: (Throwable) -> UserError,
    rollback: suspend () -> Unit = {},
    call: suspend () -> Response<T>,
): OnlineResult<T> {
    val failure: Throwable = try {
        val response = call()
        if (response.isSuccessful) return OnlineResult.Done(response.body())
        ApiHttpException.from(response)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }
    rollback()
    return OnlineResult.Failed(errorOf(failure))
}

/** True when the failure is "the server was never reached" (no network, timeout), not an answer. */
fun Throwable.isNetworkFailure(): Boolean =
    generateSequence(this) { it.cause.takeIf { c -> c !== it } }.take(8).any { it is IOException }
