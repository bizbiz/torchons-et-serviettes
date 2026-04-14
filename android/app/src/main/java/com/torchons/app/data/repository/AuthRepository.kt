package com.torchons.app.data.repository

import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.LoginRequest
import com.torchons.app.data.model.LoginResponse
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

    /**
     * Auto-login via Tailscale. Si IP inconnue, retourne une erreur CHOOSE_USER.
     */
    suspend fun autoLogin(): Result<LoginResponse> {
        return try {
            val response = apiService.autoLogin()
            if (response.isSuccessful) {
                val body = response.body() ?: return Result.failure(Exception("Reponse vide"))

                // Verifier si c'est un unknown_ip
                val status = body["status"] as? String
                if (status == "unknown_ip") {
                    return Result.failure(Exception("CHOOSE_USER:unknown_ip"))
                }

                val token = body["token"] as? String ?: return Result.failure(Exception("Token manquant"))
                @Suppress("UNCHECKED_CAST")
                val userMap = body["user"] as? Map<String, Any> ?: return Result.failure(Exception("User manquant"))

                val userId = userMap["id"] as? String ?: ""
                val username = userMap["username"] as? String ?: ""
                val displayName = userMap["display_name"] as? String ?: ""
                val foyerId = body["foyer_id"] as? String

                tokenManager.saveAuthData(token, userId, username, displayName, foyerId)

                Result.success(LoginResponse(
                    token = token,
                    user = User(id = userId, username = username, displayName = displayName)
                ))
            } else {
                Result.failure(Exception("Auto-login echoue: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Sauvegarde directe des donnees d'auth (apres claim IP). */
    suspend fun saveAuthDirect(
        token: String,
        userId: String,
        username: String,
        displayName: String,
        foyerId: String? = null,
    ) {
        tokenManager.saveAuthData(token, userId, username, displayName, foyerId)
    }

    suspend fun login(username: String, deviceId: String): Result<LoginResponse> {
        return try {
            val response = apiService.login(LoginRequest(username, deviceId))
            if (response.isSuccessful) {
                val body = response.body()!!
                tokenManager.saveAuthData(
                    body.token,
                    body.user.id,
                    body.user.username,
                    body.user.displayName,
                    foyerId = body.foyerId,
                )
                Result.success(body)
            } else {
                Result.failure(Exception("Erreur de connexion: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
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
                Result.failure(Exception("Switch echoue: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMe(): Result<User> {
        return try {
            val response = apiService.getMe()
            if (response.isSuccessful) Result.success(response.body()!!)
            else Result.failure(Exception("Erreur: ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun logout() { tokenManager.clearAuthData() }
}
