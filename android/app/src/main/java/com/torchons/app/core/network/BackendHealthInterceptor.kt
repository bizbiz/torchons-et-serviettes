package com.torchons.app.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intercepteur OkHttp qui notifie [BackendHealthMonitor] de la reussite ou
 * de l'echec de chaque appel API. Permet au moniteur de basculer Online/Offline
 * en temps reel sans avoir besoin de poller en permanence.
 *
 * Regle :
 * - Code HTTP 2xx, 3xx, 4xx => succes (le backend repond, meme si la requete
 *   est rejetee : auth invalide, validation, etc.)
 * - Code HTTP 5xx ou IOException => echec (backend injoignable ou plante)
 *
 * On considere les 4xx comme "online" car ce sont des erreurs metier, pas
 * des problemes d'infra. L'utilisateur doit corriger sa requete, pas attendre
 * que le backend revienne.
 */
@Singleton
class BackendHealthInterceptor @Inject constructor(
    private val monitor: BackendHealthMonitor,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        try {
            val response = chain.proceed(request)
            if (response.code in 500..599) {
                monitor.reportFailure()
            } else {
                monitor.reportSuccess()
            }
            return response
        } catch (e: IOException) {
            monitor.reportFailure()
            throw e
        }
    }
}
