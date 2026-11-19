package com.torchons.app.util

import com.torchons.app.data.model.TaskContextRef

/**
 * Which tasks pause during an absence, and what to change to move one to the other side.
 *
 * Mirror of the server rule (`backend/app/services/task_flags.py`):
 * `freezes = OR(requiresPresence) AND NOT OR(accruesWhenAway)` over the task's contexts,
 * and only when the household has the freeze switched on. Pure and local, so it works
 * offline and runs in a plain JUnit test.
 */
object AbsenceFreeze {

    /** What to tell the user about one task. Names are the contexts to act on. */
    sealed interface Advice {
        /** Paused task: tick "keeps going" on these presence contexts, or add one that has it. */
        data class KeepGoing(val presenceContexts: List<String>) : Advice

        /**
         * Running task that has "keeps going" contexts: untick it on them. When
         * [alsoNeedsPresence], no context requires presence yet, so one must be added too.
         */
        data class Pause(val accrueContexts: List<String>, val alsoNeedsPresence: Boolean) : Advice

        /** Running task with neither flag: add a "must be there" context. */
        data object AddPresence : Advice
    }

    /** One task as the sheet shows it. [advice] is null when the freeze is off (nothing to change). */
    data class Entry<T>(
        val item: T,
        val contexts: List<TaskContextRef>,
        val frozen: Boolean,
        val advice: Advice?,
    )

    /** A task with no context never freezes (same neutral default as the server). */
    fun freezes(contexts: List<TaskContextRef>): Boolean =
        contexts.any { it.requiresPresence } && contexts.none { it.accruesWhenAway }

    fun adviceFor(contexts: List<TaskContextRef>, freezeEnabled: Boolean): Advice? {
        if (!freezeEnabled) return null
        val presence = contexts.filter { it.requiresPresence }
        val accrue = contexts.filter { it.accruesWhenAway }
        return when {
            freezes(contexts) -> Advice.KeepGoing(presence.map { it.label })
            accrue.isNotEmpty() -> Advice.Pause(accrue.map { it.label }, presence.isEmpty())
            else -> Advice.AddPresence
        }
    }

    fun <T> classify(
        items: List<T>,
        contextsOf: (T) -> List<TaskContextRef>,
        freezeEnabled: Boolean,
    ): List<Entry<T>> = items.map { item ->
        val contexts = contextsOf(item)
        Entry(
            item = item,
            contexts = contexts,
            frozen = freezeEnabled && freezes(contexts),
            advice = adviceFor(contexts, freezeEnabled),
        )
    }
}
