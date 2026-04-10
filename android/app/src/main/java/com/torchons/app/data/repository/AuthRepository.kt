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

                tokenManager.saveAuthData(token, userId, username, displayName)

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
    suspend fun saveAuthDirect(token: String, userId: String, username: String, displayName: String) {
        tokenManager.saveAuthData(token, userId, username, displayName)
    }

    suspend fun login(username: String, deviceId: String): Result<LoginResponse> {
        return try {
            val response = apiService.login(LoginRequest(username, deviceId))
            if (response.isSuccessful) {
                val body = response.body()!!
                tokenManager.saveAuthData(body.token, body.user.id, body.user.username, body.user.displayName)
                Result.success(body)
            } else {
                Result.failure(Exception("Erreur de connexion: ${response.code()}"))
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
