package com.torchons.app.data.api

import com.torchons.app.data.model.RatingOutcomeRequest
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Rating pop-up cadence endpoint, kept out of [ApiService] (that file is at its
 * size cap). Built from the same Retrofit instance, so auth and base URL are shared.
 */
interface RatingPromptApi {
    /** Report the outcome of one AUTOMATIC rating pop-up (answered or dismissed). */
    @POST("rating-prompt/outcome")
    suspend fun reportOutcome(@Body body: RatingOutcomeRequest): Response<Unit>
}

/**
 * Guarded call: returns false on any failure (offline, 4xx/5xx, server that does
 * not know the route yet) and never throws, except for cancellation. A failed
 * report is not queued: the server simply keeps its previous gap.
 */
suspend fun RatingPromptApi.reportOutcomeSafe(answered: Boolean): Boolean =
    try {
        reportOutcome(RatingOutcomeRequest(answered)).isSuccessful
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
