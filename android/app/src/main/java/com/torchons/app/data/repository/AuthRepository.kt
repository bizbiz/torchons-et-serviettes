package com.torchons.app.data.repository

import com.torchons.app.data.api.ApiHttpException
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.User
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val tokenManager: TokenManager
) {
    val tokenFlow: Flow<String?> = tokenManager.tokenFlow
    val usernameFlow: Flow<String?> = tokenManager.usernameFlow
    val displayNameFlow: Flow<String?> = tokenManager.displayNameFlow
    val userIdFlow: Flow<String?> = tokenManager.userIdFlow
    /** Flow du foyer actif (v0.7.0 multi-foyer). */
    val foyerIdFlow: Flow<String?> = tokenManager.foyerIdFlow

    /** Saves auth data returned by onboarding, pairing or recovery. */
    suspend fun saveAuthDirect(
        token: String,
        userId: String,
        username: String,
        displayName: String,
        foyerId: String? = null,
    ) {
        tokenManager.saveAuthData(token, userId, username, displayName, foyerId)
    }

    /**
     * Switch vers un autre foyer. Remplace le JWT et le foyer_id dans les prefs,
     * mais ne touche PAS aux autres données d'auth (username, display_name).
     *
     * Le caller doit ensuite wiper le cache Room local pour eviter de voir les
     * donnees du foyer precedent.
     */
    suspend fun switchFoyer(foyerId: String): Result<Unit> {
        return try {
            val response = apiService.switchFoyer(foyerId)
            if (response.isSuccessful) {
                val body = response.body()!!
                tokenManager.updateTokenAndFoyer(body.token, body.foyerId)
                Result.success(Unit)
            } else {
                Result.failure(ApiHttpException.from(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMe(): Result<User> {
        return try {
            val response = apiService.getMe()
            if (response.isSuccessful) Result.success(response.body()!!)
            else Result.failure(ApiHttpException.from(response))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun logout() { tokenManager.clearAuthData() }
}
