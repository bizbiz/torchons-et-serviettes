package com.torchons.app.ui.common

/**
 * Whether a screen may say "Aucun ..." (nothing here). Only when the load really ANSWERED with
 * nothing: while it runs, or after it failed, the list is empty for another reason and the text
 * would be a lie (emulator re-test of 2026-11-20: "Impossible de joindre le serveur" next to
 * "Aucun objectif", and "Aucun historique" after a cold start offline).
 */
internal fun shouldShowEmptyState(isLoading: Boolean, loadFailed: Boolean, isEmpty: Boolean): Boolean =
    isEmpty && !isLoading && !loadFailed
