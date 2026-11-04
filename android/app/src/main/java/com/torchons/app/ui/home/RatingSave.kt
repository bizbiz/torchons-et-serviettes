package com.torchons.app.ui.home

import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.AffinityRequest
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.ui.common.RatingRecurrence

/** Writes one rating (affinity, effort, optional frequency vote). Moved out of HomeViewModel. */
suspend fun saveRatingData(
    apiService: ApiService,
    taskRepository: TaskRepository,
    taskId: String,
    affinity: Int,
    effortPoints: Int,
    recurrence: RatingRecurrence?,
) {
    apiService.setAffinity(taskId, AffinityRequest(affinity))
    apiService.setEffort(taskId, mapOf("points" to effortPoints))
    if (recurrence != null) {
        // Vote de frequence individuel — le serveur recalcule le consensus
        // (moyenne arrondie des votes) et met a jour tasks.recurrence_rule.
        taskRepository.voteFrequency(taskId, recurrence.everyNDays)
    }
}
