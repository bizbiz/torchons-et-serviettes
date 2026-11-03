package com.torchons.app.data.api

import kotlinx.coroutines.CancellationException
import retrofit2.Response
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Guide-page endpoint, kept out of [ApiService] (that file is at its size cap).
 * Built from the same Retrofit instance, so auth and base URL are shared.
 */
interface ModuleGuideApi {
    /** Record that this member has seen a module's guide (204, idempotent). */
    @POST("modules/{key}/guide-seen")
    suspend fun markGuideSeen(@Path("key") key: String): Response<Unit>
}

/**
 * Guarded call: returns false on any failure (offline, 4xx/5xx, server that
 * does not know the route yet) and never throws, except for cancellation.
 * A failed call is simply retried at the next occasion (the server keeps
 * `guide_seen=false`, so the guide shows again at most once more).
 */
suspend fun ModuleGuideApi.markGuideSeenSafe(key: String): Boolean =
    try {
        markGuideSeen(key).isSuccessful
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
