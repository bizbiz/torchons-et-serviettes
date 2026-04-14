# Notes de version

## v0.7.1 — 2026-04-14

### Multi-foyer : stats isolees par foyer
- Les statistiques (points, taches completees, leaderboard, streaks, badges, historique) ne melangent plus les donnees de tous tes foyers : chaque ecran stats affiche uniquement les chiffres du foyer actif.
- Les badges gagnes sont specifiques a chaque foyer (un meme badge peut etre gagne dans plusieurs foyers separement).

## v0.7.0 — 2026-04-14

### Multi-foyer (refonte invisible mais fondamentale)
- L'app est maintenant prete a gerer plusieurs foyers : tu peux creer un nouveau foyer (par exemple pour une colloc d'amis) et basculer entre tes foyers sans perdre les donnees de l'autre.
- Nouvel ecran **Reglages → Mes foyers** : tu vois la liste des foyers auxquels tu appartiens, tu peux en creer un nouveau via le bouton + et basculer en cliquant dessus. Au basculement, l'app vide son cache local et recharge les donnees du nouveau foyer (quelques secondes).
- Pour l'instant Quentin et ***REMOVED*** partagent un seul foyer "Maison" — toutes les taches, categories et pieces existantes y ont ete migrees automatiquement.
- L'invitation d'amis par QR code pour creer un foyer commun sera ajoutee dans une prochaine version (ca necessite une infra de deploiement publique).

### Taches ponctuelles visibles
- Une tache creee sans recurrence (ponctuelle) apparait maintenant dans la liste "a faire" et dans les suggestions de l'accueil tant qu'elle n'a pas ete faite. Avant, elle etait invisible tant qu'on n'avait pas desactive le filtre "a faire".

### Piece correctement sauvegardee
- Quand on cree ou modifie une tache et qu'on lui assigne une piece, la piece est maintenant bien enregistree. Avant, la piece choisie pouvait etre perdue apres sauvegarde.

### Popup de notation moins intrusive
- La popup qui demande de noter l'affinite/effort d'une tache ne reapparait plus a chaque fois qu'on revient sur l'accueil. Elle s'affiche a l'ouverture de l'app et au retour depuis le multitache, puis plus jusqu'a la prochaine session.

## v0.6.2 — 2026-04-13

### Felicitations a chaque tache validee
- Quand tu valides une tache (peu importe ou : accueil, liste ou fiche detail, et que ce soit "fait" ou "deja fait"), la carte affiche maintenant un emoji de felicitations ("Bravo !", "Bien joue !", etc.) et des confetti tombent sur l'ecran pendant 2 secondes avant que la tache disparaisse / la suivante apparaisse. Plus de validation invisible.

## v0.6.1 — 2026-04-13

### Pieces visibles partout
- Sur la page d'accueil, les cartes "Suggestions" et "En cours" affichent maintenant la piece (avec son emoji si tu en as defini un) et la categorie en pastille colore sous le titre.
- Les cartes de suggestions tres en retard ont un liseret rouge autour pour attirer l'oeil.
- La fiche detail d'une tache affiche aussi la piece (read-only — pour la modifier passe par le crayon en haut a droite).
- La popup de notation affiche la piece sous le titre de la tache pour rappel visuel.

## v0.6.0 — 2026-04-13

### Pieces : nouvelle gestion
- Les pieces de la maison sont maintenant des entites de plein droit (Cuisine, SDB, Chambre, Salon, etc.) au lieu d'etre cachees dans les titres des taches.
- Nouvel ecran "Gerer les pieces" dans Reglages : ajoute, modifie, supprime tes pieces. Chacune a un nom, une couleur et un emoji optionnel.
- Quand tu cree ou modifie une tache, un selecteur "Piece" apparait a cote de "Categorie".
- Les filtres par piece dans la liste affichent maintenant l'emoji a cote du nom.
- Dans la liste des taches, le nom de la piece (avec son emoji si defini) apparait sous le titre.
- Migration automatique : tes 29 taches existantes ont ete liees aux pieces extraites de leurs titres. Les titres ont ete nettoyes du suffixe " — Piece". Les 13 taches sans suffixe restent sans piece, tu peux les editer pour en attribuer une.

## v0.5.8 — 2026-04-13

### Liste des taches
- Plus d'air superflu en haut de l'ecran : la 1ere rangee de filtres colle vraiment au status bar Android (correction du double padding).

## v0.5.7 — 2026-04-13

### Liste des taches
- Encore plus de fluidite au scroll (optimisations internes : Compose peut maintenant detecter les cartes inchangees et ne pas les redessiner inutilement quand la liste se met a jour).

## v0.5.6 — 2026-04-13

### Liste des taches
- Marge en haut de la liste reduite : la 1ere rangee de filtres colle directement sous la barre d'etat Android (avant : trop d'air en haut).
- Defilement nettement plus fluide grace a plusieurs optimisations internes (pipeline de filtrage deporte hors du thread principal, callbacks stabilises pour eviter les recompositions inutiles).

## v0.5.5 — 2026-04-13

### Liste des taches : refonte visuelle
- Le bandeau violet "Torchons & Serviettes" en haut a ete retire — il prenait de la place sans rien proposer de cliquable.
- Nouvelle barre de synchronisation fine et discrete tout en haut, qui apparait pendant les chargements (au lieu d'un gros spinner plein ecran qui masquait les taches).
- Espacements entre les rangees de filtres revus : plus d'air en haut, espacement uniforme entre les rangees.
- Cartes de tache simplifiees : on ne garde plus que le nom, la piece et un indicateur de retard sous forme de bordure colore a gauche (vert/jaune/orange/rouge). Plus de badge categorie, d'etoiles, de barre de progression ni de texte "Rouge X%". Les cartes sont plus compactes, on en voit plus a l'ecran et le scroll est plus fluide.

## v0.5.4 — 2026-04-13

### Liste des taches
- Au demarrage de l'app, le filtre "🔥 A faire" est maintenant active par defaut : tu vois directement les taches urgentes, sans scroller dans toute la liste. Tu peux desactiver le filtre en tapant sur la chip rouge si tu veux voir toutes les taches.

## v0.5.3 — 2026-04-13

### Liste des taches
- Le swipe pour valider une tache est supprime — il declenchait parfois des validations accidentelles.
- Les deux boutons d'action sur chaque carte (calendrier "deja fait" et check "valider") sont plus gros, avec un fond colore (vert pour valider, gris pour le calendrier) et clairement identifiables comme boutons cliquables.
- Le defilement de la liste est nettement plus fluide, surtout quand il y a beaucoup de taches.

## v0.5.2 — 2026-04-13

### Notation unifiee
- Le bouton "Modifier" sur la fiche d'une tache ouvre maintenant la meme popup de notation que celle de l'accueil — avec affinite, effort ET frequence dans un seul ecran.
- Les valeurs precedentes sont prechargees correctement (avant, le bouton "Modifier" affichait toujours 5/10 et 15min par defaut).
- Affichage de la date de derniere notation au-dessus des reglages : "Derniere notation il y a 3 jours" / "Jamais note".

### Frequence
- La frequence d'une tache existante ne se modifie plus depuis l'ecran d'edition de la tache : il faut passer par la popup de notation (chacun vote sa frequence et la moyenne est utilisee).
- Lors de la creation d'une tache, le choix de la frequence reste disponible.
- Le slider d'effort est cap a 1h max (avant : 1h30 sur la fiche detail, 1h sur l'accueil — incoherent).

## v0.5.1 — 2026-04-13

### Corrections
- L'app ne crashe plus au demarrage apres mise a jour vers v0.5.0 (incompatibilite de schema de base locale sur la table d'historique).

## v0.5.0 — 2026-04-13

### Historique des modifications
- Nouvelle section « Historique des modifications » dans la fiche detail d'une tache, sous l'historique des completions : affiche qui a vote quoi et comment le consensus a evolue (affinite, effort, frequence).
- Chaque vote s'affiche avec son impact sur la moyenne : par exemple « ***REMOVED*** a vote 7j frequence → consensus 6j » ou « Quentin a vote 3/10 affinite → consensus 4/10 ».
- Les modifications directes de la tache (changement de titre, recurrence, categorie, tolerance, affectations) apparaissent aussi dans cette timeline.

### Frequence votable
- Quand la popup de notation propose d'ajuster la frequence d'une tache, c'est maintenant un vote individuel : chaque participant a sa propre valeur et le consensus affiche est la moyenne des votes.
- Avant, le dernier qui ajustait la frequence ecrasait la valeur pour tout le monde — ce n'est plus le cas.

## v0.4.1 — 2026-04-12

### Corrections
- Les reglages (WiFi maison, notifications, seuil de suggestions, etc.) se sauvegardent maintenant correctement — avant, les modifications pouvaient etre perdues a la reouverture de l'app.
- La popup de notation d'affinite n'apparait plus en double a l'ouverture de l'app.
- Le bouton "Une autre" dans la popup de notation fonctionne : il passe bien a la tache suivante au lieu de simplement fermer la popup.
- Le bouton "?" affiche maintenant une tache differente a chaque utilisation (avant, il montrait toujours la meme).
- La popup de notation affiche le nombre de taches restantes a noter.
- Quand toutes les taches sont deja notees, un message le confirme au lieu de ne rien faire.

## v0.4.0 — 2026-04-12

### Toute l'app fonctionne hors ligne
- Les ecrans Taches et Detail d'une tache affichent maintenant les dernieres donnees connues meme quand le backend ne repond pas (avant, ils restaient vides).
- Le bandeau "Hors ligne" affiche la date et l'heure des dernieres donnees ("donnees du 12/04 14:32").
- Quand la connexion revient, les donnees se rafraichissent automatiquement sans avoir a fermer et rouvrir l'ecran.

### Creation et validation de taches sans reseau
- On peut maintenant creer, modifier, supprimer et valider des taches meme sans connexion au backend.
- Les operations s'appliquent instantanement dans l'app et se synchronisent automatiquement quand la connexion revient.
- Un badge "X operation(s) en attente" s'affiche dans le bandeau quand des operations n'ont pas encore ete envoyees au serveur.
- Quand la synchronisation se fait, un indicateur "Synchronisation en cours..." s'affiche brievement.

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
