package com.torchons.app.data.repository

import android.content.Context
import com.torchons.app.R
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.model.CompletionPreview
import com.torchons.app.data.model.RecurrenceRule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the app checks before a completion: its points preview, and whether the server would
 * drop it as a duplicate.
 *
 * The duplicate rule is the twin of the server's (`services/completion_guard.py`, and the 409
 * COMPLETION_DUPLICATE of the REST route): same task, same member, inside
 * [minCompletionGapSeconds] around the new completion's own date. /sync acknowledges such a
 * completion without writing it, but the screens play their celebration BEFORE writing: on
 * 2026-11-11 a discarded duplicate still showed « Bien joué ! ». Decided with Quentin the same
 * day: the app refuses it before the celebration, with a message, from its cache, so that it
 * also holds offline.
 */
@Singleton
class CompletionChecks @Inject constructor(
    private val apiService: ApiService,
    private val taskDao: TaskDao,
    private val completionDao: CompletionDao,
    private val tokenManager: TokenManager,
    @ApplicationContext private val context: Context,
) {

    /** Server-side points preview (bonus/malus by date and participant count). Null offline or on error. */
    suspend fun preview(taskId: String, isoDate: String, numParticipants: Int): CompletionPreview? =
        try {
            val resp = apiService.getCompletionPreview(taskId, isoDate, numParticipants)
            if (resp.isSuccessful) resp.body() else null
        } catch (_: Exception) {
            null
        }

    /**
     * The message refusing a completion of [taskId] dated [completedAt] (null = now) by [by]
     * (null = the current user; several for « Fait ensemble »), or null when it is not a duplicate.
     */
    suspend fun refusal(taskId: String, completedAt: String? = null, by: List<String>? = null): String? {
        val task = taskDao.getById(taskId) ?: return null
        val members = by ?: listOfNotNull(tokenManager.userIdFlow.first())
        val at = completedAt?.let(::parseInstant) ?: Instant.now()
        val gap = minCompletionGapSeconds(task.recurrenceRule, task.inferredIntervalDays)
        val twin = findDuplicate(completionDao.observeByTask(taskId).first(), members.toSet(), at, gap)
            ?: return null
        val existing = parseInstant(twin.completedAt)?.atZone(ZoneId.systemDefault()) ?: return null
        val time = existing.format(DateTimeFormatter.ofPattern("HH:mm"))
        return if (existing.toLocalDate() == LocalDate.now()) {
            context.getString(R.string.completion_duplicate_today, time)
        } else {
            val day = existing.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            context.getString(R.string.completion_duplicate_day, day, time)
        }
    }
}

/** Twin of `min_completion_gap_seconds` (backend `services/recurrence.py`): 1 h, or 24 h for a weekly-or-rarer task. */
internal fun minCompletionGapSeconds(rule: RecurrenceRule?, inferredIntervalDays: Double?): Long {
    val oneHour = 3600L
    val oneDay = 86400L
    return when (rule?.type) {
        null -> oneHour
        "interval" -> if ((rule.everyNDays ?: 1) >= 7) oneDay else oneHour
        "monthly_variable", "anniversary" -> oneDay
        "uncertain" -> if (inferredIntervalDays != null && inferredIntervalDays >= 7) oneDay else oneHour
        else -> oneHour
    }
}

/** The cached completion by one of [members] strictly within [gapSeconds] of [at], if any. */
internal fun findDuplicate(
    completions: List<CompletionEntity>,
    members: Set<String>,
    at: Instant,
    gapSeconds: Long,
): CompletionEntity? = completions.firstOrNull { c ->
    val done = parseInstant(c.completedAt) ?: return@firstOrNull false
    c.completedBy in members &&
        done.isAfter(at.minusSeconds(gapSeconds)) && done.isBefore(at.plusSeconds(gapSeconds))
}

private fun parseInstant(iso: String): Instant? =
    runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .recoverCatching { Instant.parse("${iso}Z") }
        .getOrNull()
