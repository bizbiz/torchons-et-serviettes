package com.torchons.app.ui.home

import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.data.repository.VoteRepository
import com.torchons.app.ui.common.RatingRecurrence

/**
 * Writes one rating (affinity, effort, optional frequency vote). Moved out of HomeViewModel.
 *
 * Each vote goes online-first and is queued when the network or the server fails (D3, 2026-11-20
 * audit: the responses were never read, so "Notes enregistrées" showed on a 4xx and an offline
 * vote vanished). Throws the first permanent refusal: both callers show it and keep the dialog
 * open. Resending the whole rating after one refused step is harmless, each step sets "my value".
 */
suspend fun saveRatingData(
    votes: VoteRepository,
    taskRepository: TaskRepository,
    taskId: String,
    affinity: Int,
    effortPoints: Int,
    recurrence: RatingRecurrence?,
) {
    runRatingSteps(
        buildList {
            add { votes.voteAffinity(taskId, affinity) }
            add { votes.voteEffort(taskId, effortPoints) }
            // Individual frequency vote: the server recomputes the consensus (rounded mean).
            recurrence?.let { add { taskRepository.voteFrequency(taskId, it.everyNDays) } }
        },
    ).getOrThrow()
}

/** Runs [steps] in order and stops at the first failure, which is the result. */
internal suspend fun runRatingSteps(steps: List<suspend () -> Result<Unit>>): Result<Unit> {
    for (step in steps) {
        val result = step()
        if (result.isFailure) return result
    }
    return Result.success(Unit)
}
