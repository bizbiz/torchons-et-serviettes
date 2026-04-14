package com.torchons.app.data.api

import com.torchons.app.data.model.AffinityRequest
import com.torchons.app.data.model.BadgeInfo
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.FrequencyVoteResponse
import com.torchons.app.data.model.TaskHistoryDto
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.CategoryCreateRequest
import com.torchons.app.data.model.CategoryUpdateRequest
import com.torchons.app.data.model.CompleteTaskRequest
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.LeaderboardEntry
import com.torchons.app.data.model.LoginRequest
import com.torchons.app.data.model.LoginResponse
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.StreakInfo
import com.torchons.app.data.model.Suggestion
import com.torchons.app.data.model.SyncRequest
import com.torchons.app.data.model.SyncResponse
import com.torchons.app.data.model.UserStats
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskInProgress
import com.torchons.app.data.model.UpdateTaskRequest
import com.torchons.app.data.model.User
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Interface Retrofit pour les appels a l'API backend.
 * Toutes les routes sont relatives a la base URL (/api).
 */
interface ApiService {

    // --- Authentification ---

    /** Connexion avec nom d'utilisateur et identifiant appareil. */
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    /** Auto-login via Tailscale (identification par IP). Retourne LoginResponse ou unknown_ip. */
    @GET("auth/auto")
    suspend fun autoLogin(): Response<Map<String, Any>>

    /** Associer l'IP courante a un utilisateur. */
    @POST("auth/claim")
    suspend fun claimIP(@Body body: Map<String, String>): Response<Map<String, Any>>

    /** Recupere les informations de l'utilisateur connecte. */
    @GET("auth/me")
    suspend fun getMe(): Response<User>

    /** Liste tous les utilisateurs du foyer. */
    @GET("auth/users")
    suspend fun getUsers(): Response<List<User>>

    // --- Taches ---

    /** Liste des taches avec filtres optionnels. */
    @GET("tasks")
    suspend fun getTasks(
        @Query("category_id") categoryId: String? = null,
        @Query("active_only") activeOnly: Boolean? = true
    ): Response<List<Task>>

    /** Recupere une tache par son identifiant. */
    @GET("tasks/{id}")
    suspend fun getTask(@Path("id") taskId: String): Response<Task>

    /** Cree une nouvelle tache. */
    @POST("tasks")
    suspend fun createTask(@Body request: CreateTaskRequest): Response<Task>

    /** Met a jour une tache existante. */
    @PUT("tasks/{id}")
    suspend fun updateTask(
        @Path("id") taskId: String,
        @Body request: UpdateTaskRequest
    ): Response<Task>

    /** Supprime une tache (soft delete). */
    @DELETE("tasks/{id}")
    suspend fun deleteTask(@Path("id") taskId: String): Response<Map<String, Any>>

    /** Marquer une tache comme completee. */
    @POST("tasks/{id}/complete")
    suspend fun completeTask(
        @Path("id") taskId: String,
        @Body request: CompleteTaskRequest = CompleteTaskRequest()
    ): Response<Completion>

    /** Modifier une completion (qui, quand). */
    @PUT("completions/{completionId}")
    suspend fun updateCompletion(
        @Path("completionId") completionId: String,
        @Body body: Map<String, String>
    ): Response<Map<String, Any>>

    /** Supprimer une completion (soft delete). */
    @DELETE("tasks/{taskId}/completions/{completionId}")
    suspend fun deleteCompletion(
        @Path("taskId") taskId: String,
        @Path("completionId") completionId: String
    ): Response<Map<String, Any>>

    // --- Categories ---

    /** Liste de toutes les categories. */
    @GET("categories")
    suspend fun getCategories(): Response<List<Category>>

    /** Creer une categorie. */
    @POST("categories")
    suspend fun createCategory(@Body request: CategoryCreateRequest): Response<Category>

    /** Modifier une categorie. */
    @PUT("categories/{id}")
    suspend fun updateCategory(
        @Path("id") catId: String,
        @Body request: CategoryUpdateRequest
    ): Response<Category>

    /** Supprimer une categorie. */
    @DELETE("categories/{id}")
    suspend fun deleteCategory(@Path("id") catId: String): Response<Map<String, Any>>

    // --- Pieces (rooms) ---

    /** Liste de toutes les pieces (tri alphabetique). */
    @GET("rooms")
    suspend fun getRooms(): Response<List<com.torchons.app.data.model.Room>>

    /** Creer une piece. */
    @POST("rooms")
    suspend fun createRoom(
        @Body request: com.torchons.app.data.model.RoomCreateRequest
    ): Response<com.torchons.app.data.model.Room>

    /** Modifier une piece. */
    @PUT("rooms/{id}")
    suspend fun updateRoom(
        @Path("id") roomId: String,
        @Body request: com.torchons.app.data.model.RoomUpdateRequest
    ): Response<com.torchons.app.data.model.Room>

    /** Supprimer une piece (soft delete, les taches passent en room_id=NULL). */
    @DELETE("rooms/{id}")
    suspend fun deleteRoom(@Path("id") roomId: String): Response<Map<String, Any>>

    // --- Foyers (multi-tenant) ---

    /** Liste les foyers auxquels l'user courant appartient. */
    @GET("foyers")
    suspend fun getMyFoyers(): Response<List<com.torchons.app.data.model.Foyer>>

    /** Cree un nouveau foyer (l'user devient admin). */
    @POST("foyers")
    suspend fun createFoyer(
        @Body request: com.torchons.app.data.model.FoyerCreateRequest
    ): Response<com.torchons.app.data.model.Foyer>

    /** Switch le foyer actif. Retourne un nouveau JWT a remplacer cote client. */
    @POST("foyers/{id}/switch")
    suspend fun switchFoyer(
        @Path("id") foyerId: String
    ): Response<com.torchons.app.data.model.SwitchFoyerResponse>

    /** Update un foyer (nom, home_freeze_enabled). Admin uniquement. */
    @PUT("foyers/{id}")
    suspend fun updateFoyer(
        @Path("id") foyerId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): Response<com.torchons.app.data.model.Foyer>

    /** Statut home freeze pour le foyer actif (tooltip adaptatif). */
    @GET("foyers/home-freeze-status")
    suspend fun getHomeFreezeStatus(): Response<com.torchons.app.data.model.HomeFreezeStatus>

    // --- Presence (v0.7.4 home freeze) ---

    /** Report de presence — declenche par PresenceWorker ou LifecycleResume. */
    @POST("presence")
    suspend fun postPresence(
        @Body body: com.torchons.app.data.model.PresenceRequest,
    ): Response<com.torchons.app.data.model.PresenceResponse>

    // --- Completions ---

    /** Liste des completions avec filtres optionnels. */
    @GET("completions")
    suspend fun getCompletions(
        @Query("user_id") userId: String? = null,
        @Query("task_id") taskId: String? = null,
        @Query("limit") limit: Int? = null
    ): Response<List<Completion>>

    // --- Gamification ---

    /** Suggestions de taches. */
    @GET("suggestions")
    suspend fun getSuggestions(
        @Query("freshness_threshold") freshnessThreshold: Double = 80.0
    ): Response<List<Suggestion>>

    /** Recuperer les affinites du user courant pour toutes les taches. */
    @GET("my-affinities")
    suspend fun getMyAffinities(): Response<List<Map<String, Any>>>

    /** Tache en attente de notation d'affinite/effort. */
    @GET("pending-affinity")
    suspend fun getPendingAffinity(
        @Query("expiry_months") expiryMonths: Int = 6
    ): Response<List<PendingAffinity>>

    /** Tache avec la notation la plus ancienne (mode manuel). */
    @GET("oldest-rating")
    suspend fun getOldestRating(): Response<List<PendingAffinity>>

    /** Recuperer les ratings (affinite+effort) de tous les users pour une tache. */
    @GET("tasks/{id}/ratings")
    suspend fun getTaskRatings(@Path("id") taskId: String): Response<Map<String, Any>>

    /** Contexte de notation pour une tache donnee (preremplissage du modal unifie). */
    @GET("tasks/{id}/rating-context")
    suspend fun getRatingContext(@Path("id") taskId: String): Response<PendingAffinity>

    /** Definir l'affinite pour une tache. */
    @PUT("tasks/{id}/affinity")
    suspend fun setAffinity(
        @Path("id") taskId: String,
        @Body request: AffinityRequest
    ): Response<Map<String, Any>>

    /** Definir l'effort estime pour une tache (1pt=5min). */
    @PUT("tasks/{id}/effort")
    suspend fun setEffort(
        @Path("id") taskId: String,
        @Body request: Map<String, Int>
    ): Response<Map<String, Any>>

    /** Definir le vote de frequence (every_n_days) et recalculer le consensus. */
    @PUT("tasks/{id}/frequency")
    suspend fun setFrequency(
        @Path("id") taskId: String,
        @Body request: FrequencyRequest
    ): Response<FrequencyVoteResponse>

    /** Historique de modification d'une tache (votes + modifs directes). */
    @GET("tasks/{id}/history")
    suspend fun getTaskHistory(
        @Path("id") taskId: String,
        @Query("limit") limit: Int = 100
    ): Response<List<TaskHistoryDto>>

    /** Marquer une tache comme en cours. */
    @POST("tasks/{id}/start")
    suspend fun startTask(@Path("id") taskId: String): Response<TaskInProgress>

    /** Annuler une tache en cours. */
    @DELETE("tasks/{id}/start")
    suspend fun cancelTaskInProgress(@Path("id") taskId: String): Response<Map<String, Any>>

    /** Taches en cours de l'utilisateur. */
    @GET("in-progress")
    suspend fun getInProgress(): Response<List<TaskInProgress>>

    /** Statut fair-share. */
    @GET("fair-share/status")
    suspend fun getFairShareStatus(): Response<List<FairShareStatus>>

    /** Mettre a jour la config fair-share. */
    @PUT("fair-share/config")
    suspend fun updateFairShareConfig(@Body config: List<Map<String, Any>>): Response<Map<String, Any>>

    // --- Stats ---

    /** Historique global. */
    @GET("history")
    suspend fun getHistory(
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null
    ): Response<List<Map<String, Any>>>

    /** Historique personnel d'un utilisateur. */
    @GET("users/{user_id}/history")
    suspend fun getUserHistory(
        @Path("user_id") userId: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null
    ): Response<List<Map<String, Any>>>

    /** Statistiques par utilisateur. */
    @GET("stats")
    suspend fun getStats(): Response<List<UserStats>>

    /** Leaderboard par periode. */
    @GET("leaderboard")
    suspend fun getLeaderboard(@Query("period") period: String = "week"): Response<List<LeaderboardEntry>>

    /** Streaks. */
    @GET("streaks")
    suspend fun getStreaks(): Response<List<StreakInfo>>

    /** Badges. */
    @GET("badges")
    suspend fun getBadges(): Response<List<BadgeInfo>>

    // --- Settings ---

    /** Recuperer les reglages utilisateur. */
    @GET("settings")
    suspend fun getSettings(): Response<Map<String, Any>>

    /** Mettre a jour les reglages utilisateur. */
    @PUT("settings")
    suspend fun updateSettings(@Body body: Map<String, @JvmSuppressWildcards Any>): Response<Map<String, Any>>

    // --- Notifications ---

    /** Enregistrer un token FCM. */
    @POST("notifications/register")
    suspend fun registerNotificationToken(@Body body: Map<String, String>): Response<Map<String, Any>>

    // --- Contenu (changelog / roadmap) ---

    /** Notes de version de l'app (markdown). */
    @GET("changelog")
    suspend fun getChangelog(): Response<Map<String, Any>>

    /** Roadmap de l'app (markdown). */
    @GET("roadmap")
    suspend fun getRoadmap(): Response<Map<String, Any>>

    // --- Projection (v0.8.0 magic buttons) ---

    /** Retard actuel du foyer (minutes + liste des taches overdue). */
    @GET("retard")
    suspend fun getRetard(): Response<com.torchons.app.data.model.RetardResponse>

    /** Rythme 14j par user du foyer + done today + objectif perso. */
    @GET("pace")
    suspend fun getPace(): Response<com.torchons.app.data.model.PaceResponse>

    /** Min/j combine pour maintenir zero retard en regime permanent. */
    @GET("maintenance-rate")
    suspend fun getMaintenanceRate(): Response<com.torchons.app.data.model.MaintenanceRateResponse>

    /** Recupere l'objectif perso + unite d'affichage de l'user courant. */
    @GET("objective")
    suspend fun getObjective(): Response<com.torchons.app.data.model.ObjectiveResponse>

    /** Set l'objectif perso et/ou l'unite d'affichage de l'user courant. */
    @PUT("objective")
    suspend fun updateObjective(
        @Body body: com.torchons.app.data.model.ObjectiveUpdate
    ): Response<com.torchons.app.data.model.ObjectiveResponse>

    // --- Sync offline ---

    @POST("sync")
    suspend fun sync(@Body request: SyncRequest): Response<SyncResponse>

    // --- Sante ---

    /** Verification de l'etat du serveur. */
    @GET("health")
    suspend fun health(): Response<Map<String, Any>>
}
