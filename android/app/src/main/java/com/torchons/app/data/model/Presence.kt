package com.torchons.app.data.model

/**
 * Modeles de presence supprimes dans la refonte v2 (home freeze).
 *
 * L'ancien systeme (PresenceWorker background + POST /presence avec
 * is_at_home booleen) a ete remplace par un checkin WiFi positif uniquement
 * (POST /presence/checkin sans payload) + resolution manuelle des jours
 * incertains via le prompt RESOLVE_PRESENCE.
 *
 * Note : le PresenceWorker en arriere-plan ne fonctionnait pas de maniere
 * fiable sur Android 12+ (Doze mode, restrictions background). Le signal
 * WiFi n'est desormais lu qu'a l'ouverture de l'app.
 */
