# Notes de version

## v0.3.0 — 2026-04-11

### Mode hors ligne sur l'accueil
- L'accueil affiche maintenant les dernieres donnees connues meme quand le backend ne repond pas, au lieu de rester bloque ou de mentir avec "Tout est frais !" alors que rien n'a pu charger.
- Quand le backend est injoignable, un bandeau orange "Hors ligne" apparait en haut de l'app avec un bouton "Reessayer".
- Quand le backend revient (par exemple apres redemarrage du serveur), l'app le detecte automatiquement et le bandeau disparait tout seul.
- Si l'accueil n'a vraiment aucune donnee a afficher faute de backend, il affiche un message clair "Donnees indisponibles" au lieu du "Tout est frais !" trompeur.

### Demarrage plus rapide
- L'app n'attend plus 30 secondes en blanc au demarrage si le backend rame ou est injoignable. Au-dela de quelques secondes elle passe directement en mode hors ligne.

## v0.2.2 — 2026-04-11

### Tolerance au retard sur les taches recurrentes
- Tu peux maintenant choisir, pour chaque tache recurrente, a quel point l'app est severe sur le retard : Strict, Normal ou Souple. Le choix est dans le formulaire d'edition de la tache, juste apres la recurrence.
- Strict : la tache devient orange/rouge avant l'echeance.
- Normal (defaut) : la tache est jaune a l'echeance — comportement actuel.
- Souple : la tache reste verte meme un peu apres l'echeance.

### Felicitations fair-share plus intelligentes
- Le message "Tu as fait plus que ta part" sur l'accueil arrive maintenant uniquement quand tu as fait *vraiment* plus que ta part, et non au moindre ecart. Par defaut il faut avoir fait 30% de plus que ton ratio cible.
- Ce seuil de tolerance est reglable dans Reglages avances ("Tolerance fair-share", de 0% a 100%).
- Le message ne s'affiche plus quand l'app n'a pas assez de donnees (moins de 10 taches completees au total sur les 30 derniers jours).
- Le texte du message dit maintenant "ces 30 derniers jours" au lieu de "ce mois-ci", plus precis par rapport au calcul reel.

## v0.2.1 — 2026-04-11

### Maintenance
- Ameliorations internes du stockage des configurations sensibles. Transparent pour l'utilisateur.

## v0.2.0 — 2026-04-11

### Filtres puissants sur la liste des taches
- Cinq filtres rapides en haut de la liste : "A faire" (rouge), Affinite (aimees / detestees), Effort (long / court), Recurrence (recurrentes / ponctuelles), Maison (a la maison / hors maison).
- Chaque filtre est tri-state : un clic active "haut", deux clics active "bas", trois clics desactive.
- Filtre par piece, extrait automatiquement du titre des taches (ex : "— Cuisine", "— Salon").
- Possibilite de selectionner plusieurs categories en meme temps au lieu d'une seule.
- Les categories vides ne sont plus affichees dans la barre de filtres.
- Un petit recapitulatif des filtres actifs s'affiche au-dessus de la liste.

### Popup de notation enrichie
- La popup d'affinite et d'effort se pre-remplit maintenant avec ta derniere note (ou la moyenne du foyer si tu n'as jamais note).
- Pour une tache ponctuelle, tu peux la transformer en tache recurrente directement depuis la popup, et choisir la frequence.
- Pour une tache deja recurrente, tu peux ajuster sa frequence (de 50 % a 200 % de la valeur actuelle).

### "Qui a fait cette tache ?"
- Quand tu valides une tache en mode "deja fait" (date passee), un nouveau selecteur te demande qui (Quentin ou ***REMOVED***) l'a faite, en plus de la date.
- Le selecteur est skippe automatiquement si une seule personne est assignee a la tache.

### Profil utilisateur cliquable partout
- Tu peux maintenant cliquer sur le nom de Quentin ou ***REMOVED*** n'importe ou dans l'app : feed historique, classement, resume stats, streaks, completions de taches.
- Un modal s'ouvre avec son historique personnel, son nombre de completions et son total de points.
- Les taches dans cet historique sont elles-memes cliquables pour aller a leur detail.

### Historique nettoye
- Plus de doublons sur les affinites et efforts : les saves sans changement reel ne polluent plus le flux.
- Quand une tache est modifiee, le feed indique maintenant precisement quels champs ont change (titre, categorie, recurrence, etc.) au lieu d'un "modifie" opaque.
- Cliquer sur une carte du feed ouvre directement la tache correspondante.

### Anti-double-validation
- Impossible de valider la meme tache deux fois dans la meme heure.
- Pour les taches hebdomadaires ou plus longues, blocage sur la journee entiere.
- Evite les doubles taps accidentels sur le bouton "Faite".

### Nouveautes & Roadmap dans l'app
- Nouvelle entree "Nouveautes" dans les Reglages.
- Affiche la version actuelle, les notes de version et la roadmap des features prevues.

### Cosmetique
- Nouvelle icone d'app : un chapeau de magicien pose sur une serpillere, sur fond bleu nuit etoile.
- La version de l'app affichee en bas de l'accueil et dans les Reglages est maintenant lue dynamiquement (fini le "v1.0.0" en dur).

## v0.1.0 — 2026-04-08

### Premiere version
- CRUD taches avec recurrence (intervalle, jours de semaine, mensuel, custom).
- Categories, attribution, completions avec points.
- Sync offline bidirectionnelle.
- Gamification : freshness score, streaks, leaderboard, badges.
- Fair-share : ratio configurable par couple.
- Notifications push Firebase + rappels locaux.
- Import initial des 39 taches depuis la config Home Assistant.
