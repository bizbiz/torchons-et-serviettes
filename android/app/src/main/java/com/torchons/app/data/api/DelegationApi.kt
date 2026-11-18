package com.torchons.app.data.api

import com.torchons.app.data.model.Delegation
import retrofit2.Response
import retrofit2.http.GET

/**
 * « À faire aujourd'hui » read, kept out of [ApiService] (that file is at its size cap). Built
 * from the same Retrofit instance. Writes go through /sync (table `task_delegations`).
 */
interface DelegationApi {
    /** Active delegations of the current household's tasks I see; empty when switched off. */
    @GET("delegations")
    suspend fun getDelegations(): Response<List<Delegation>>
}
