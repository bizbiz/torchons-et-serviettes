package com.torchons.app.data.api

import com.torchons.app.data.local.TokenManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intercepteur OkHttp qui ajoute automatiquement le token JWT
 * dans le header Authorization de chaque requete.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Recuperer le token de maniere synchrone (necessaire pour l'intercepteur)
        val token = runBlocking {
            tokenManager.tokenFlow.first()
        }

        // Si pas de token, envoyer la requete sans authentification
        if (token.isNullOrEmpty()) {
            return chain.proceed(originalRequest)
        }

        // Ajouter le header Authorization avec le token Bearer
        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        return chain.proceed(authenticatedRequest)
    }
}
