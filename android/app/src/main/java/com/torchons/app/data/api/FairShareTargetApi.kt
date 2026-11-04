package com.torchons.app.data.api

import com.torchons.app.data.model.FairShareHistoryEntry
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.FairShareTargetItem
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

/**
 * Target-share endpoints, kept out of [ApiService] (that file is at its size cap).
 * Built from the same Retrofit instance, so auth and base URL are shared.
 *
 * ONLINE only: the server is authoritative, nothing is cached in Room.
 */
interface FairShareTargetApi {
    /** Targets (`target_ratio`) and real shares of the active members. */
    @GET("fair-share/status")
    suspend fun getStatus(): Response<List<FairShareStatus>>

    /** Sets the shares of ALL active members; the server refuses a total other than 1. */
    @PUT("fair-share/config")
    suspend fun putConfig(@Body items: List<FairShareTargetItem>): Response<Unit>

    /** Who changed the shares, when, and the shares before and after. Newest first. */
    @GET("fair-share/history")
    suspend fun getHistory(): Response<List<FairShareHistoryEntry>>
}

/** Outcome of a save. [OFFLINE] = the call never got an answer. */
enum class FairShareSaveResult { SAVED, REJECTED, OFFLINE }

/**
 * The guarded calls below return null / [FairShareSaveResult.OFFLINE] on any failure
 * (offline, timeout, error answer) and never throw, except for cancellation. A screen
 * that gets null shows a message instead of waiting for an answer that will not come.
 */
suspend fun FairShareTargetApi.loadTargetsSafe(): List<FairShareStatus>? =
    try {
        getStatus().takeIf { it.isSuccessful }?.body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

suspend fun FairShareTargetApi.saveTargetsSafe(items: List<FairShareTargetItem>): FairShareSaveResult =
    try {
        if (putConfig(items).isSuccessful) FairShareSaveResult.SAVED else FairShareSaveResult.REJECTED
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FairShareSaveResult.OFFLINE
    }

suspend fun FairShareTargetApi.loadHistorySafe(): List<FairShareHistoryEntry>? =
    try {
        getHistory().takeIf { it.isSuccessful }?.body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
