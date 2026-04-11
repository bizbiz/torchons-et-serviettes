package com.torchons.app.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intercepteur OkHttp qui retry une fois les requetes GET en cas
 * d'IOException (timeout, connexion refusee, etc.).
 *
 * Strategie volontairement minimaliste : 1 seul retry, uniquement sur GET
 * (idempotent), sans backoff. Le but est d'absorber un blip reseau passager
 * sans transformer chaque requete en saga.
 */
@Singleton
class RetryGetInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET") {
            return chain.proceed(request)
        }
        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            // Retry une fois, en construisant une nouvelle requete pour eviter
            // les problemes de body deja consomme.
            chain.proceed(request.newBuilder().build())
        }
    }
}
