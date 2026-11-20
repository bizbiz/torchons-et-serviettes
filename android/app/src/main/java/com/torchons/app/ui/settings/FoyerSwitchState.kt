package com.torchons.app.ui.settings

import com.torchons.app.ui.common.UserError

/**
 * Where the "Change household" sheet stands. The sheet stays open on [Switching] (a progress
 * indicator: the wipe and the refresh take a few seconds), closes on [Done], and shows [Error]
 * (D5, 2026-11-20 audit: it used to close at once and a failure was swallowed).
 */
sealed class FoyerSwitchState {
    data object Idle : FoyerSwitchState()
    data object Switching : FoyerSwitchState()
    data object Done : FoyerSwitchState()
    data class Error(val error: UserError) : FoyerSwitchState()
}
