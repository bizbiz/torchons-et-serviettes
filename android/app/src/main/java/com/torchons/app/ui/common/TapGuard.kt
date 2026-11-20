package com.torchons.app.ui.common

import java.util.concurrent.ConcurrentHashMap

/**
 * Lets one completion per task through at a time, from the tap to the end of its celebration.
 *
 * A second tap on the same task while the first one is still being handled is ignored silently:
 * the user is impatient, not asking for a second completion. Taken synchronously in the tap
 * handler, before any coroutine starts, so two taps in the same frame cannot both pass.
 * (CompletionLedger refuses a duplicate anyway; this keeps it from showing as an error.)
 */
class TapGuard {
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True when [taskId] was free and is now taken; false when a completion of it is running. */
    fun tryAcquire(taskId: String): Boolean = busy.add(taskId)

    fun release(taskId: String) {
        busy.remove(taskId)
    }

    fun isBusy(taskId: String): Boolean = taskId in busy
}
