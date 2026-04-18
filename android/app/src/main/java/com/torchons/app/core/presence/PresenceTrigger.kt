package com.torchons.app.core.presence

/**
 * SUPPRIME — PresenceTrigger et le worker periodique ne fonctionnaient pas.
 *
 * Voir PresenceWorker.kt pour les details. La detection de presence passe
 * desormais par un simple checkin WiFi a l'ouverture de l'app
 * (HomeViewModel.reportPresenceNow).
 */
