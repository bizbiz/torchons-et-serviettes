# Dogfood — ce qui reste à voir de ses yeux

Registre du skill `dogfood` (`~/.claude/skills/dogfood/SKILL.md`). Une entrée par chose à voir ;
cibles et lecture des versions dans `.claude/dogfood.conf`.

## Un ✓ fait hors ligne gardant l'heure du toucher n'a pas été vu sur le téléphone

**Noté le** 2026-11-11 (commit ebf77bf, repris du todo le 2026-11-16)
**Cible** : telephone
**Attend** : v0.44.3 sur telephone

Trouvé par le test lourd du refus des doublons (ts_test, beta) : TSdupX touché hors ligne à
19:31, enregistré à 19:32:55 au retour du réseau. `TaskRepository.completeTask` envoyait
`completed_at` vide pour un ✓, et `backend/app/api/sync.py` (`_insert_completion`) prenait alors
l'heure du serveur. Le payload porte depuis l'heure du toucher. Décidé par Quentin le
2026-11-14 : se teste sur le Pixel, pas sur l'émulateur. Jamais vus non plus : le message en
anglais, la fenêtre d'1 h sur un ✓ « maintenant », les règles mensuelle, anniversaire et
incertaine.

**Geste** : dans l'app prod, passer en mode avion, cocher ✓ une tâche du jour en notant l'heure
exacte du toucher, attendre deux minutes, rétablir le réseau et laisser la synchro passer.
Donner à la session le nom de la tâche et l'heure du toucher ; elle lit `completed_at` de la
ligne en prod (lecture seule, `local-ops/prod-queries/`).

**Attendu** :
- `completed_at` en prod porte l'heure du toucher, pas celle du retour du réseau
- l'historique de la tâche dans l'app montre la même heure
