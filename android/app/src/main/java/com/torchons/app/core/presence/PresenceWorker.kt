package com.torchons.app.core.presence

/**
 * SUPPRIME — PresenceWorker ne fonctionne pas de maniere fiable.
 *
 * Ce fichier contenait un Worker WorkManager qui checkait le WiFi toutes les
 * 6h pour reporter la presence au backend. Sur Android 12+ (Doze mode,
 * restrictions background, App Standby Buckets), le worker s'executait
 * rarement voire jamais, ce qui rendait la detection de presence non fiable
 * et declenchait des faux "Bon retour a la maison".
 *
 * Remplace par : checkin WiFi au lancement de l'app uniquement (signal
 * positif) + resolution manuelle des jours incertains via le prompt
 * RESOLVE_PRESENCE dans la queue unifiee.
 *
 * Voir : HomeViewModel.reportPresenceNow() pour le checkin au lancement.
 */
