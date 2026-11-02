package com.torchons.app.ui.taskdetail

import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.FormerMember
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.data.model.User

/** Conteneur interne pour le combine des flows. */
internal data class CachedDetail(
    val task: Task?,
    val completions: List<Completion>,
    val users: List<User>,
    val history: List<TaskHistoryEvent>,
    val former: List<FormerMember>,
)
