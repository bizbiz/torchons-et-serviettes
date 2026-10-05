package com.torchons.app.data.api

import com.torchons.app.data.model.ContextView
import com.torchons.app.data.model.ContextUpdateRequest
import com.torchons.app.data.model.ContextCreateRequest
import com.torchons.app.data.model.AffinityRequest
import com.torchons.app.data.model.BadgeInfo
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.FrequencyVoteResponse
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.data.model.TaskHistoryDto
import com.torchons.app.data.model.CollectiveCompleteRequest
import com.torchons.app.data.model.CollectiveCompletionResponse
import com.torchons.app.data.model.CompleteTaskRequest
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.LeaderboardEntry
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.StreakInfo
import com.torchons.app.data.model.DismissRequest
import com.torchons.app.data.model.Suggestion
import com.torchons.app.data.model.SuggestionsResponse
import com.torchons.app.data.model.SyncRequest
import com.torchons.app.data.model.SyncResponse
import com.torchons.app.data.model.UserStats
import com.torchons.app.data.model.Task
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
    @Query("context_id") contextId: String? = null,
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

    /** Marquer une tache comme completee par N participants ("fait ensemble"). */
    @POST("tasks/{id}/complete-collective")
    suspend fun completeTaskCollective(
        @Path("id") taskId: String,
        @Body request: CollectiveCompleteRequest,
    ): Response<CollectiveCompletionResponse>

    /**
     * Preview des points a gagner avant validation (bonus/malus applique).
     * Backend authoritative : meme formule qu'au POST /complete.
     *
     * @param completedAt ISO 8601 date (optionnel, defaut = maintenant)
     * @param numParticipants 1 pour solo, N pour 'fait ensemble' (pour preview du split)
     */
    @GET("tasks/{id}/completion-preview")
    suspend fun getCompletionPreview(
        @Path("id") taskId: String,
        @retrofit2.http.Query("completed_at") completedAt: String? = null,
        @retrofit2.http.Query("num_participants") numParticipants: Int = 1,
    ): Response<com.torchons.app.data.model.CompletionPreview>

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

    /** Supprimer un collectif entier : toutes les lignes partageant collective_id. */
    @DELETE("tasks/{taskId}/completions/collective/{collectiveId}")
    suspend fun deleteCollectiveCompletion(
        @Path("taskId") taskId: String,
        @Path("collectiveId") collectiveId: String
    ): Response<Map<String, Any>>

    // --- Contextes (remplacent pieces et categories) ---

    @GET("contexts")
    suspend fun getContexts(): Response<List<ContextView>>

    @POST("contexts")
    suspend fun createContext(@Body body: ContextCreateRequest): Response<ContextView>

    @PUT("contexts/{id}")
    suspend fun updateContext(
        @Path("id") id: String,
        @Body body: ContextUpdateRequest,
    ): Response<ContextView>

    @DELETE("contexts/{id}")
    suspend fun deleteContext(@Path("id") id: String): Response<Unit>

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

    /** Toggle gamification du foyer — tout membre (pas admin-only). v0.32. */
    @PUT("foyers/{id}/gamification")
    suspend fun updateFoyerGamification(
        @Path("id") foyerId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): Response<com.torchons.app.data.model.Foyer>

    // --- Presence (v2 home freeze — checkin WiFi positif uniquement) ---

    /** Checkin presence : signal positif "je suis a la maison". */
    @POST("presence/checkin")
    suspend fun presenceCheckin(): Response<Map<String, Boolean>>

    /** P2 — Jours de presence du foyer dans [from, to] (calendrier partage). */
    @GET("presence")
    suspend fun getPresence(
        @Query("from") from: String,
        @Query("to") to: String,
    ): Response<List<com.torchons.app.data.model.PresenceDayDto>>

    /** P2 — Pose / efface des jours de presence (edition manuelle). */
    @PUT("presence")
    suspend fun putPresence(
        @Body days: List<com.torchons.app.data.model.PresenceDayPut>,
    ): Response<List<com.torchons.app.data.model.PresenceDayDto>>

    /** P3 — Jours d'activite d'un membre dans [from, to] (couche etoile/streak
     *  du calendrier). Mirrors getPresence. */
    @GET("activity-days")
    suspend fun getActivityDays(
        @Query("user_id") userId: String,
        @Query("from") from: String,
        @Query("to") to: String,
    ): Response<com.torchons.app.data.model.ActivityDaysDto>

    // --- Completions ---

    /** Liste des completions avec filtres optionnels. */
    @GET("completions")
    suspend fun getCompletions(
        @Query("user_id") userId: String? = null,
        @Query("task_id") taskId: String? = null,
        @Query("limit") limit: Int? = null
    ): Response<List<Completion>>

    // --- Gamification ---

    /** Suggestions de taches (la plus urgente + meilleure affinite + sans cadence).
     *
     * @param exclude CSV d'UUIDs a exclure (dismiss list client) — v0.16+.
     */
    @GET("suggestions")
    suspend fun getSuggestions(
        @Query("exclude") exclude: String? = null,
    ): Response<SuggestionsResponse>

    /** Log analytics fire-and-forget : clic ✕ sur une carte Home (v0.16+). */
    @POST("suggestions/dismiss")
    suspend fun logSuggestionDismiss(
        @Body body: DismissRequest,
    ): Response<Unit>

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

    /** Etat des votes de frequence pour une tache (consensus, my_vote, others). */
    @GET("tasks/{id}/frequency-votes")
    suspend fun getFrequencyVotes(
        @Path("id") taskId: String
    ): Response<FrequencyVotesState>

    /** Etat des votes d'effort pour une tache (v0.28 — parallele freq). */
    @GET("tasks/{id}/effort-votes")
    suspend fun getEffortVotes(
        @Path("id") taskId: String
    ): Response<com.torchons.app.data.model.EffortVotesState>

    /** Demande explicite de (re)voter a un autre membre du foyer. v0.28. */
    @POST("prompts/request-revote")
    suspend fun requestRevote(
        @Body body: com.torchons.app.data.model.RequestRevoteBody,
    ): Response<Map<String, Any>>

    /** Historique de modification d'une tache (votes + modifs directes). */
    @GET("tasks/{id}/history")
    suspend fun getTaskHistory(
        @Path("id") taskId: String,
        @Query("limit") limit: Int = 100
    ): Response<List<TaskHistoryDto>>

    /** Statut fair-share. */
    @GET("fair-share/status")
    suspend fun getFairShareStatus(): Response<List<FairShareStatus>>

    /** Mettre a jour la config fair-share. */
    @PUT("fair-share/config")
    suspend fun updateFairShareConfig(@Body config: List<Map<String, Any>>): Response<Map<String, Any>>

    // --- Stats ---

    /** Historique global avec filtres optionnels (famille, acteur, periode, recherche). */
    @GET("history")
    suspend fun getHistory(
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
        @Query("since") since: String? = null,
        @Query("family") families: List<String> = emptyList(),
        @Query("actor_id") actorId: String? = null,
        @Query("q") q: String? = null,
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

    /** Course de points cumules (par user, sur la periode demandee). */
    @GET("stats/points-timeline")
    suspend fun getPointsTimeline(
        @Query("period") period: String = "week",
    ): Response<com.torchons.app.data.model.PointsTimelineResponse>

    /** Frequence des taches par jour de semaine, par user. */
    @GET("stats/weekday-frequency")
    suspend fun getWeekdayFrequency(
        @Query("period") period: String = "all",
    ): Response<com.torchons.app.data.model.WeekdayFrequencyResponse>

    /** Frequence des taches par categorie (top N), per-user. */
    @GET("stats/category-frequency")
    suspend fun getCategoryFrequency(
        @Query("period") period: String = "all",
    ): Response<com.torchons.app.data.model.CategoryFrequencyResponse>

    /** Historique journalier du retard du foyer (taches en commun).
     *  `period` : "all" (defaut, avec trim creation) ou "month" (30j sans trim). */
    @GET("stats/retard-timeline")
    suspend fun getRetardTimeline(
        @Query("period") period: String = "all",
    ): Response<com.torchons.app.data.model.RetardTimelineResponse>

    /**
     * Resume de fin de semaine (v0.31, item roadmap "Stats hebdomadaires").
     * `week_offset` : 0 = semaine en cours, -1 = derniere, ... -52 = il y a un an.
     */
    @GET("stats/weekly-summary")
    suspend fun getWeeklySummary(
        @Query("week_offset") weekOffset: Int = 0,
    ): Response<com.torchons.app.data.model.WeeklySummaryResponse>

    /**
     * Totaux foyer + streak couple (v0.31, item roadmap "Stats globales du
     * couple"). Affiche en complement des stats personnelles dans le
     * UserProfileDialog.
     */
    @GET("stats/couple-totals")
    suspend fun getCoupleTotals(): Response<com.torchons.app.data.model.CoupleTotalsResponse>

    /**
     * Stats long-terme par dimension (v0.31, item roadmap "Stats long-terme
     * et par dimension"). period = month / quarter / year.
     */
    @GET("stats/long-term")
    suspend fun getLongTermStats(
        @Query("period") period: String = "quarter",
    ): Response<com.torchons.app.data.model.LongTermResponse>

    // --- Objectifs (v0.31, item roadmap "Objectifs personnels et partages") ---

    @GET("objectives")
    suspend fun listObjectives(): Response<List<com.torchons.app.data.model.Objective>>

    @POST("objectives")
    suspend fun createObjective(
        @Body body: com.torchons.app.data.model.ObjectiveCreateRequest,
    ): Response<com.torchons.app.data.model.Objective>

    @DELETE("objectives/{id}")
    suspend fun deleteObjective(
        @Path("id") objectiveId: String,
    ): Response<Unit>

    // --- Settings ---

    /** Catalogue sous sa forme contextes. Public, comme /catalog : le wizard
     *  le lit AVANT la creation du foyer, donc avant d'avoir un JWT. */
    @GET("catalog/suggestions")
    suspend fun getCatalogSuggestions(
        @Query("limit") limit: Int = 30,
    ): Response<com.torchons.app.data.model.CatalogSuggestionsResponse>

    @GET("catalog/contexts")
    suspend fun getCatalogContexts(): Response<com.torchons.app.data.model.CatalogContextsResponse>

    // --- Modules (decouverte progressive) ---

    /** Registre des modules + etat effectif pour le foyer courant. */
    @GET("modules")
    suspend fun getModules(): Response<com.torchons.app.data.model.ModulesResponse>

    /** Activer / desactiver un module. 409 si une dependance l'interdit. */
    @PUT("modules/{key}")
    suspend fun toggleModule(
        @Path("key") key: String,
        @Body body: com.torchons.app.data.model.ModuleToggleRequest,
    ): Response<com.torchons.app.data.model.ModuleView>

    /** Recuperer les reglages utilisateur. */
    @GET("settings")
    suspend fun getSettings(): Response<Map<String, Any>>

    /** Mettre a jour les reglages utilisateur. */
    @PUT("settings")
    suspend fun updateSettings(@Body body: Map<String, @JvmSuppressWildcards Any>): Response<Map<String, Any>>

    // --- Config bonus/malus (Options avancees) ---

    /** Recuperer la config bonus/malus du foyer. */
    @GET("bonus-config")
    suspend fun getBonusConfig(): Response<com.torchons.app.data.model.BonusConfig>

    /** Mettre a jour la config bonus/malus du foyer (partiel). */
    @PUT("bonus-config")
    suspend fun updateBonusConfig(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): Response<com.torchons.app.data.model.BonusConfig>

    // --- Prompts (queue unifiee) ---

    /** Prochain prompt non-resolu (204 si rien). */
    @GET("prompts/next")
    suspend fun getNextPrompt(): Response<com.torchons.app.data.model.UserPrompt>

    /** Marque le prompt comme affiche. */
    @POST("prompts/{id}/shown")
    suspend fun markPromptShown(@Path("id") promptId: String): Response<Map<String, Any>>

    /** Resout le prompt (DISMISSED / ANSWERED). */
    @POST("prompts/{id}/resolve")
    suspend fun resolvePrompt(
        @Path("id") promptId: String,
        @Body body: com.torchons.app.data.model.PromptResolveRequest,
    ): Response<Map<String, Any>>

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

    // --- Propositions d'objectifs (v0.8.1 Feature 3) ---

    /** Creer une proposition multi-user pour le foyer actif. */
    @POST("objective-propositions")
    suspend fun createProposition(
        @Body body: com.torchons.app.data.model.PropositionCreate
    ): Response<com.torchons.app.data.model.PropositionView>

    /** Accepter ou refuser une proposition. */
    @POST("objective-propositions/{id}/respond")
    suspend fun respondToProposition(
        @Path("id") propositionId: String,
        @Body body: com.torchons.app.data.model.PropositionResponseBody
    ): Response<com.torchons.app.data.model.PropositionView>

    /** Proposition en cours adressee a l'user courant (poll de fallback). */
    @GET("objective-propositions/pending")
    suspend fun getPendingProposition(): Response<com.torchons.app.data.model.PropositionView>

    /** Detail d'une proposition par id (poll proposer). */
    @GET("objective-propositions/{id}")
    suspend fun getProposition(
        @Path("id") propositionId: String,
    ): Response<com.torchons.app.data.model.PropositionView>

    /** Annuler une proposition en cours (proposer uniquement). */
    @DELETE("objective-propositions/{id}")
    suspend fun cancelProposition(
        @Path("id") propositionId: String,
    ): Response<Map<String, Any>>

    // --- Catalogue de templates (v0.9.0 Feature 4) ---

    /** Resolve l'import : propose les contextes a creer ou a rattacher. */
    @POST("catalog/import/resolve")
    suspend fun resolveCatalogImport(
        @Body body: com.torchons.app.data.model.ImportResolveRequest,
    ): Response<com.torchons.app.data.model.ImportResolveResponse>

    /** Applique l'import apres validation du mapping. */
    @POST("catalog/import/apply")
    suspend fun applyCatalogImport(
        @Body body: com.torchons.app.data.model.ImportApplyRequest,
    ): Response<com.torchons.app.data.model.ImportApplyResponse>

    // --- Sync offline ---

    @POST("sync")
    suspend fun sync(@Body request: SyncRequest): Response<SyncResponse>

    // --- Onboarding sans compte (v0.30) ---

    /** Wizard "Creer un foyer" : cree User + Foyer + UserFoyer(admin) + recovery_code. Unauth. */
    @POST("onboarding/create-foyer")
    suspend fun onboardingCreateFoyer(
        @Body body: com.torchons.app.data.model.OnboardingCreateFoyerRequest,
    ): Response<com.torchons.app.data.model.OnboardingCreateFoyerResponse>

    /** Owner genere un invite_token pour un QR (TTL 15min). Auth. */
    @POST("pairing/init")
    suspend fun pairingInit(): Response<com.torchons.app.data.model.PairingInitResponse>

    /** v0.31 — Owner genere un token pour ajouter un de ses propres appareils. Auth. */
    @POST("pairing/init-add-device")
    suspend fun pairingInitAddDevice(): Response<com.torchons.app.data.model.PairingInitResponse>

    /** v0.31 — Probe le kind d'un invite_token (sans le consommer). Unauth. */
    @GET("pairing/info/{token}")
    suspend fun pairingInfo(
        @Path("token") token: String,
    ): Response<com.torchons.app.data.model.PairingInfoResponse>

    /** Joiner consomme un invite_token et rejoint le foyer comme membre. Unauth. */
    @POST("pairing/join")
    suspend fun pairingJoin(
        @Body body: com.torchons.app.data.model.PairingJoinRequest,
    ): Response<com.torchons.app.data.model.PairingJoinResponse>

    /** Etape 1 du flow restore : cherche l'user par recovery_code, retourne preview. Unauth. */
    @POST("recovery/restore")
    suspend fun recoveryRestore(
        @Body body: com.torchons.app.data.model.RecoveryRestoreRequest,
    ): Response<com.torchons.app.data.model.RecoveryRestoreResponse>

    /** Etape 2 du flow restore : confirme avec le restore_token et emet le JWT. Unauth. */
    @POST("recovery/restore/confirm")
    suspend fun recoveryRestoreConfirm(
        @Body body: com.torchons.app.data.model.RecoveryRestoreConfirmRequest,
    ): Response<com.torchons.app.data.model.RecoveryRestoreConfirmResponse>

    // --- Appareils (voir docs/appareils.fr.md) ---

    /** Liste les appareils de CE compte. Chacun ne voit que les siens. */
    @GET("devices")
    suspend fun listDevices(): Response<List<com.torchons.app.data.model.DeviceDto>>

    /**
     * Declare l'identite que cette installation detient reellement.
     * Appelee au demarrage : si elle differe de celle du jeton (restauration
     * sur un nouveau telephone), le serveur rebascule la session et rend un
     * nouveau jeton, sans rien demander a l'utilisateur.
     */
    @POST("devices/register")
    suspend fun registerDevice(
        @Body body: com.torchons.app.data.model.DeviceRegisterRequest
    ): Response<com.torchons.app.data.model.DeviceRegisterResponse>

    /** Coupe l'acces d'un AUTRE de ses appareils. */
    @POST("devices/{device_id}/revoke")
    suspend fun revokeDevice(@Path("device_id") deviceId: String): Response<Unit>

    /** Se deconnecter ET revoquer l'appareil courant. */
    @POST("devices/me/signout")
    suspend fun signOutThisDevice(): Response<Unit>

    /** Regenere un nouveau recovery_code (invalide l'ancien). Auth. */
    @POST("recovery/regenerate")
    suspend fun recoveryRegenerate(): Response<com.torchons.app.data.model.RecoveryRegenerateResponse>

    /** Self-leave d'un foyer (soft + auto-promote oldest member si dernier admin). Auth. */
    @POST("foyers/{foyer_id}/leave")
    suspend fun leaveFoyer(
        @Path("foyer_id") foyerId: String,
    ): Response<com.torchons.app.data.model.LeaveFoyerResponse>

    /** RGPD : soft-delete du compte courant. Auth. */
    @DELETE("me")
    suspend fun deleteMe(): Response<Unit>

    // --- Billing (v0.30.x plomberie premium, pas de paywall actif) ---

    /** Verifie un purchase_token Google Play et flippe foyer.is_premium. Auth admin. */
    @POST("billing/verify")
    suspend fun billingVerify(
        @Body body: com.torchons.app.data.model.BillingVerifyRequest,
    ): Response<com.torchons.app.data.model.BillingVerifyResponse>

    // --- Sante ---

    /** Verification de l'etat du serveur. */
    @GET("health")
    suspend fun health(): Response<Map<String, Any>>
}
