package com.torchons.app.ui.onboarding

import android.content.Context
import com.torchons.app.R
import com.torchons.app.data.api.ApiHttpException
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.ContextCreateRequest
import com.torchons.app.data.model.FoyerCreateRequest
import com.torchons.app.data.model.ImportApplyRequest
import com.torchons.app.data.model.ImportDecision
import com.torchons.app.data.model.ImportResolveRequest
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.HouseholdSwitcher
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import kotlinx.coroutines.CancellationException

/**
 * The "add a household" submission of the wizard (P4a.2): POST /foyers, switch, free contexts,
 * catalog import. One instance per wizard, because it REMEMBERS how far it got (D5, 2026-11-20
 * audit): a failure in the import used to leave the household created, and the retry created a
 * SECOND one (and the cache of the previous household stayed, the wipe being at the very end).
 *
 *  - create + switch + wipe + refresh run as ONE [HouseholdSwitcher] sequence, in the application
 *    scope, so the cache is the new household's as soon as the token is;
 *  - [createdFoyerId] survives a failed attempt: the retry skips what is done (the creation, the
 *    switch, the free contexts) and only redoes the import.
 * Failures are returned, never swallowed: the wizard shows them.
 */
internal class AddFoyerFlow(
    private val apiService: ApiService,
    private val authRepository: AuthRepository,
    private val switcher: HouseholdSwitcher,
    private val context: Context,
) {
    /** The household created by this wizard; null until the POST went through. */
    var createdFoyerId: String? = null
        private set
    private var switched = false
    private var contextsCreated = false

    /** Null on success, the error to show otherwise. */
    suspend fun run(s: WizardState): UserError? {
        if (!switched) {
            val result = switcher.switchWith { createAndSwitch(s) }
            if (result.isFailure) return errorOf(result.exceptionOrNull())
        }
        createFreeContexts(s)
        importCatalog(s)?.let { return it }
        // The cache was wiped at the switch; the import added tasks and contexts since.
        switcher.refreshAll()
        return null
    }

    private suspend fun createAndSwitch(s: WizardState): Result<Unit> {
        val foyerId = createdFoyerId ?: createFoyer(s).getOrElse { return Result.failure(it) }
        createdFoyerId = foyerId
        return authRepository.switchFoyer(foyerId).onSuccess { switched = true }
    }

    private suspend fun createFoyer(s: WizardState): Result<String> = try {
        val name = s.foyerName.ifBlank { context.getString(R.string.settings_foyer_default_name) }
        val resp = apiService.createFoyer(FoyerCreateRequest(name))
        val created = resp.body()
        when {
            !resp.isSuccessful -> Result.failure(ApiHttpException.from(resp))
            created == null -> Result.failure(IllegalStateException(context.getString(R.string.common_empty_response)))
            else -> Result.success(created.id)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    // Free contexts typed at step 2 -- best effort, the household exists anyway and a missing
    // context is added afterwards. Done once: a retry must not duplicate them.
    private suspend fun createFreeContexts(s: WizardState) {
        if (contextsCreated || s.customRooms.isEmpty()) return
        runCatching { s.customRooms.forEach { apiService.createContext(ContextCreateRequest(name = it)) } }
        contextsCreated = true
    }

    /** Catalog import into the NEW household (token already switched); the user sees why it failed. */
    private suspend fun importCatalog(s: WizardState): UserError? {
        if (s.selectedContextKeys.isEmpty() && s.selectedTaskKeys.isEmpty()) return null
        return try {
            val resolveResp = apiService.resolveCatalogImport(
                ImportResolveRequest(
                    taskKeys = s.selectedTaskKeys.toList(),
                    contextKeys = s.selectedContextKeys.toList(),
                ),
            )
            val resolved = resolveResp.body()
            if (!resolveResp.isSuccessful || resolved == null) {
                return if (resolveResp.isSuccessful) emptyAnswer() else UserErrors.fromResponse(context, resolveResp)
            }
            val applyResp = apiService.applyCatalogImport(
                ImportApplyRequest(
                    taskKeys = s.selectedTaskKeys.toList(),
                    contextKeys = s.selectedContextKeys.toList(),
                    contextDecisions = resolved.contexts.map { ImportDecision(it.templateKey, "create", null) },
                ),
            )
            if (applyResp.isSuccessful) null else UserErrors.fromResponse(context, applyResp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UserErrors.from(context, e)
        }
    }

    private fun emptyAnswer() = UserError(context.getString(R.string.common_empty_response))

    private fun errorOf(t: Throwable?) =
        UserErrors.from(context, t ?: IllegalStateException("switch failed"))
}
