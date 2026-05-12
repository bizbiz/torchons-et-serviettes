# Notes de version

## v0.31.3 — 2026-05-12

### Gamification — lisibilite des points
- Affichage des points prevus sur chaque carte de la page d'accueil ("💰 N pts"), avec mention du bonus retard quand applicable ("🔥 +M pts bonus retard")
- Icone (i) cliquable a cote des taches solo (0 point) qui ouvre une pop-up explicative : pourquoi un seul participant = pas de points, et pourquoi valider la tache reste utile
- Meme pop-up disponible dans le dialog "Fait !" quand la tache ne rapportera pas de points

## v0.31.2 — 2026-05-12

### Absences (gel a la maison)
- Le dialog "Jours a confirmer" pre-coche desormais tous les jours en
  absent par defaut. Plus besoin de tout toggler apres une longue absence
  — il suffit de marquer les quelques jours ou tu etais reellement la.
- Les taches "a la maison" ne s'affichent plus comme en retard pendant
  les jours d'absence confirmes. Le retard est gele et reprend a ton
  retour, sans cumul de "dette" injuste.
- Les stats (hebdo, mensuel, total, leaderboard, long-terme, totaux du
  couple, resume hebdo) ignorent maintenant les completions tombant sur
  un jour absent — coherent avec le gel applique aux taches.
- Apres avoir resolu un prompt d'absence, les taches sont automatiquement
  recalculees : l'affichage est toujours bon, plus d'incoherence visuelle.

## v0.31.1 — 2026-05-01

### Sous le capot
- Mise en place d'un export CSV de l'historique cote serveur
  (completions + taches). Accessible uniquement via curl avec le
  JWT pour l'instant ; un ecran "Mes donnees" dans les Reglages
  suivra dans une iteration ulterieure.
- Cette release embarque aussi v0.31.0 (stats hebdo + globales
  couple + long-terme + objectifs + add-device) qui n'avait jamais
  ete installee sur le device.

## v0.31.0 — 2026-04-30

### Statistiques hebdomadaires
- Une nouvelle card "Cette semaine" en haut de l'onglet Stats resume ta
  semaine du foyer : nombre de taches, points cumules, classement par
  membre avec emoji 🏆 sur le top, et top 3 des taches les plus faites.
  Fleches gauche/droite pour naviguer dans les semaines passees.

### Statistiques globales du couple
- Le modal de profil utilisateur (cliquer sur un nom dans Stats) affiche
  desormais une card "Total du foyer" avec 3 colonnes (cette semaine /
  ce mois / au total). Si le foyer enchaine plusieurs jours d'affilee
  avec >=1 tache faite par n'importe qui, un compteur "🔥 N jours
  d'affilee" apparait. La section "Repartition" (barres Quentin/***REMOVED***)
  est conservee en complement.

### Stats long-terme par pieces
- Nouvelle card "Repartition par pieces" sur Stats : barres horizontales
  pour visualiser quelle piece concentre le plus de taches sur la
  periode (Mois / Trimestre / Annee). Affiche aussi le temps cumule
  estime (deduit du consensus effort de chaque tache, 1pt = 5min).

### Objectifs personnels et partages
- Nouvelle card "Mes objectifs" sur Stats. Tape sur + pour creer un
  objectif (ex: 3 taches par semaine), avec choix de periode (par jour/
  semaine/mois) et de la cible (1 a 30). La progression est calculee
  automatiquement et affichee avec une barre de progression par
  objectif. Long-press pour supprimer.

### Ajouter un de mes appareils
- Une nouvelle card "Ajouter un de mes appareils" dans les Reglages
  permet de generer un QR pour connecter une tablette ou un 2eme
  telephone a ton compte sans creer de nouveau membre dans le foyer.

## v0.30.1 — 2026-04-30

### Securite du foyer (Reglages)
- Nouvelle section "Securite du foyer" dans les Reglages avec deux
  actions :
  - "Regenerer mon code de recuperation" : remplace ton ancien code
    par un nouveau, affiche une seule fois avec boutons Copier et
    Partager. Utile si tu n'avais pas encore de code, ou si tu
    soupconnes que l'ancien a fuite.
  - "Quitter le foyer" : tu sors du foyer en preservant ton historique
    cote serveur. Si tu es membre d'un autre foyer, tu y bascules
    automatiquement ; sinon tu retombes sur l'ecran d'accueil.

### Soutenir / Discuter avec le dev
- Nouvelle card en bas des Reglages avec un mot du dev (Quentin,
  solo). Deux boutons : "Payer un cafe" (ouvre la page Buy Me a
  Coffee dans le navigateur) et "M'ecrire" (ouvre l'app mail avec
  un brouillon vers le dev). Les retours, idees et bugs sont les
  bienvenus.

## v0.30.0 — 2026-04-29

### Inviter quelqu'un dans le foyer
- Une nouvelle card "Inviter quelqu'un" dans les Reglages permet
  de generer un QR code d'invitation valide 15 minutes. La personne
  invitee installe l'app, choisit "Rejoindre un foyer" et scanne le
  QR pour devenir membre du foyer.

### Onboarding sans compte
- A la 1re ouverture de l'app (ou apres une desinstallation), un
  ecran d'accueil propose trois choix : "Creer un foyer",
  "Rejoindre un foyer" (scan QR), ou "Retrouver mon foyer" (code de
  recuperation). Plus besoin de creer un compte avec username.
- Le parcours "Creer un foyer" guide en 5 etapes : ton prenom, le
  nom du foyer, les pieces de chez toi, les taches que tu veux
  suivre (tirees du catalogue), puis ton code de recuperation.
- Pour l'instant le reseau Tailscale maison continue de te connecter
  automatiquement comme avant — tu ne verras le nouvel accueil que
  si tu reinstalles l'app.

### Code de recuperation
- A la creation ou a la jonction d'un foyer, un code unique de 8
  caracteres (XXXX-XXXX) est affiche. Note-le : il te permet de
  retrouver ton foyer + tes droits d'admin si tu perds ton telephone.
  Affiche une seule fois, partageable par mail/messagerie via un
  bouton dedie.

## v0.29.0 — 2026-04-29

### Modal "+ d'info" sur l'effort
- Sur la fiche d'une tache, dans la section "Effort & Affinite", un
  nouveau lien "Effort & points · + d'info" ouvre un modal qui
  explique a quoi sert le vote effort : calcul des points gagnes
  quand la tache est validee, partage equitable (fair-share) entre
  membres, et retard global du foyer.
- Le modal liste les votes effort de chaque membre avec la date du
  vote. Boutons "Modifier mon vote" sur ta ligne, "Demander revote"
  sur celle des autres, et "Demander a voter" pour les membres
  assignes qui n'ont pas encore vote.
- Symetrique du modal "+ d'info" deja existant pour la frequence.

## v0.28.2 — 2026-04-29

### Icone de notification reconnaissable
- Les notifications affichent desormais la silhouette du chapeau de magicien dans la barre de statut, au lieu d'un carre blanc generique. La pastille de fond reprend la couleur indigo de l'app sur Android 8+.

## v0.28.1 — 2026-04-28

### Notifications push enfin fiables
- Le token de notification est désormais re-synchronisé au démarrage de l'app, plus seulement après un login. Avant ce fix, un utilisateur connecté depuis longtemps via Tailscale ne renvoyait jamais son token au serveur — résultat : aucune notif arrivait. Ouvrir l'app suffit maintenant à corriger.

## v0.28.0 — 2026-04-28

### Bandeau "Tu n'as pas voté" sur le détail des tâches
- Sur le detail d'une tache, un bandeau bleu apparait quand tu n'as pas encore exprime ton avis (frequence, affinite, ou effort). Cliquer dessus ouvre le formulaire de vote, pre-rempli avec les valeurs deja saisies. C'est purement une invitation : libre a toi de l'ignorer.
- Le sous-texte sous la frequence change selon l'etat des votes : "Moyenne des votes de toi & Quentin", ou "Vote de Quentin · tu n'as pas vote", ou "Pas encore vote".

### Modaux "+ d'info" sur la fréquence et l'affinité
- Un lien "+ d'info" sous la frequence ouvre un modal qui explique le mecanisme de vote, montre la valeur de chacun avec sa date, et permet de modifier ton vote ou de demander a un autre membre de revoter.
- Un modal equivalent sur l'affinite explique pourquoi c'est une preference perso (qui n'influence que tes propres suggestions), et te laisse modifier ton avis. Pas de bouton "demander a quelqu'un d'autre" pour l'affinite : c'est subjectif et personnel.

### Demander à quelqu'un de revoter
- Depuis le modal "+ d'info" frequence, un bouton "Demander revote" cote utilisateur envoie une demande dans sa file d'attente. Quand l'autre ouvre l'app, le formulaire de vote pop avec un message "Quentin t'a demande de revoter sur cette tache". Idem pour l'effort.
- Une demande deja envoyee est visible (bouton grise "Demande envoyee") pour eviter d'envoyer 10 fois la meme.

## v0.27.0 — 2026-04-28

### Frequence des taches : un vote, simple et clair
- A la creation d'une tache, on parle desormais explicitement de "vote" : ta saisie est ton vote sur la frequence, pas une regle imposee. Si tu es seul a voter, c'est ton vote qui devient la frequence affichee.
- Quatre choix de type de tache, en mots clairs : "Reguliere", "Selon le mois", "Irreguliere", "Une seule fois". Trois anciens types techniques (jours fixes de semaine, mensuel a jour fixe, toutes les N semaines) disparaissent — ils n'etaient pas utilises et compliquaient l'interface.
- Le mini-dialog "Modifier mon vote" propose deux modes : "Reguliere" (un slider unique) ou "Selon le mois" (12 sliders mensuels). Pratique pour les taches saisonnieres : tondre la pelouse tous les 7 jours en ete et tous les 30 en hiver, par exemple.
- Le slider de creation pose la bonne question : *"Selon toi, tous les combien faut-il faire cette tache ?"*

## v0.26.0 — 2026-04-27

### Filtres de la liste des taches en mots clairs
- Les six filtres en haut de la liste (a faire / aimees / long / recurrentes / maison / solo) ne sont plus des icones mais des chips texte. Plus besoin de deviner ce que veut dire la flamme ou le coeur.
- Trois etats au lieu de deux : un clic active le filtre positif (fond rouge, "A faire"), un deuxieme clic le bascule en filtre inverse (fond vert, "Pas a faire"), un troisieme clic l'enleve (juste le texte avec un contour).
- Les chips defilent horizontalement quand il y en a trop pour la largeur de l'ecran.

## v0.25.1 — 2026-04-27

### Ajustements de la mini-courbe Retard sur l'Accueil
- La carte du retard du foyer remonte maintenant la phase de creation initiale du backlog (memes premiers points filtres que sur l'ecran Stats), pour une lecture plus claire des 30 derniers jours utiles.
- La carte est repositionnee tout en bas de l'Accueil, sous les taches urgentes : c'est une lecture contextuelle, pas une action a entreprendre.

## v0.25.0 — 2026-04-27

### Retard du foyer sur l'Accueil
- L'Accueil affiche desormais une mini-courbe du retard du foyer sur les 30 derniers jours glissants, sous les cartes "Rythme" et "Retard". Permet de voir d'un coup d'oeil la tendance recente sans aller dans les Stats.
- Pas de triche sur la phase de creation pour cette vue : on montre les 30 derniers jours fideles a la realite, contrairement a la vue "Depuis le debut" des stats qui demarre au pic.

### Selecteur de periode sur le graphique Retard
- Dans Stats, le graphique "Retard" gagne deux chips en haut : "Depuis le debut" (defaut, trim de la phase de creation) et "30 derniers jours" (vue glissante recente).

## v0.24.0 — 2026-04-27

### Recap "Cette semaine" en tete des stats
- Une nouvelle carte en haut de l'onglet Resume montre tes points de la semaine, ton "+/- X% vs semaine precedente", le jour ou le foyer a fait le plus de taches, et la categorie la plus active.
- Carte automatiquement masquee si rien a afficher (foyer tout neuf ou debut de semaine).

### Stats du foyer dans le profil utilisateur
- Quand on clique sur le nom de quelqu'un (dans une carte d'historique, un classement, etc.), la modal de profil affiche desormais aussi la repartition Quentin / ***REMOVED*** sur la semaine et au total, avec une barre coloree.
- Le nom de l'utilisateur ouvert est en gras dans la legende pour le reperage immediat.

## v0.23.0 — 2026-04-27

### Statistiques enrichies
- Sur les courses de points 7 jours et 30 jours, un "+/- X% vs avant" en couleur (vert si en progres, rouge si en recul) compare a la periode precedente.
- Un nouveau graphique "Repartition par categorie" arrive en derniere position du swiper : top 8 des categories que tu fais le plus, avec un breakdown par utilisateur. Selecteur de periode integre (Semaine / Mois / 3 mois / Tout).
- Le graphique "Par jour de semaine" gagne le meme selecteur de periode pour zoomer sur l'evolution recente vs l'historique total.
- La course de points "Depuis toujours" passe automatiquement en buckets mensuels au-dela d'un an d'historique pour rester lisible.

### Tap sur un point de graphique
- Toucher un point sur les courses de points ouvre directement l'historique filtre sur la journee correspondante. Pratique pour enqueter sur un pic ou un creux.

## v0.22.0 — 2026-04-27

### Notifications affinees pour les completions
- Trois reglages independants remplacent l'ancien "Taches completees" : "Quand quelqu'un fait une tache seul", "Quand on fait une tache ensemble (avec moi)", et "Quand une tache est faite a plusieurs sans moi" (visible uniquement a 3+ membres dans le foyer).
- Les notifs collectives sont personnalisees : "Quentin a fait la vaisselle avec toi", "Quentin, ***REMOVED*** & toi avez fait …", ou "Quentin & ***REMOVED*** ont fait …" selon que tu participes ou non.

### Historique des taches repense pour les collectifs
- Un evenement "fait ensemble" apparait comme une seule carte avec tous les participants ("A, B & C") au lieu d'une ligne par personne.
- Icone groupe distincte des completions solo, et chaque nom est cliquable vers le profil du participant.
- Long-press sur un collectif supprime tout l'evenement d'un coup.

### Onglet Statistiques repense
- Streaks remontees en premier dans Resume.
- Nouvelle carte "Statistiques" avec cinq graphiques que l'on swipe : retard du foyer dans le temps, course de points cumules sur 7 jours, 30 jours, depuis toujours, et frequence des taches faites par jour de semaine.
- Le graphique "Retard" (en 1ere position) montre l'evolution du retard du foyer depuis le debut, sur les taches en commun uniquement (les taches assignees a une seule personne sont exclues). Toggle Minutes / Taches pour basculer l'unite. Le delta vs debut s'affiche en vert quand le retard a baisse.
- Classement (Semaine / Mois / Total) fusionne dans Resume — l'onglet "Classement" disparait.

## v0.21.0 — 2026-04-26

### Recurrence : choisir l'unite (jours, semaines, mois)
- Sur le formulaire d'edition d'une tache, l'unite de saisie de la frequence se bascule entre Jours, Semaines et Mois via trois petits boutons. Pratique pour creer une tache mensuelle sans avoir a calculer "30 jours".
- Le mini-dialog de vote sur la cadence et la popup de notation (post-completion) ont aussi ce switch d'unite : voter "tous les 3 mois" ne demande plus de calcul mental.
- Quand on change d'unite, la valeur s'adapte intelligemment (92 j devient "3 mois", soit 90 j) — la conversion est explicite et le breakdown est affiche en clair.

### Affichage humain des durees
- Les durees longues sont affichees dans la forme la plus lisible : "3 mois 2 j" plutot que "92 jours", "1 sem. 3 j" plutot que "10 jours".
- Visible partout ou la cadence apparait : ecran detail d'une tache, formulaire d'edition, popup de vote, popup de notation, votes des autres membres.

## v0.20.0 — 2026-04-25

### Auto-archivage des taches ponctuelles
- Les taches ponctuelles (sans recurrence) completees depuis plus de 7 jours sont automatiquement masquees de la liste principale.
- L'historique reste preserve : les completions restent visibles dans les stats, le leaderboard et le pace.
- Pas de bouton de restauration : si une tache archivee redevient pertinente, il suffit de la recreer (l'autocomplete de duplication la propose toujours).

### Dupliquer une tache existante
- Nouveau bouton "Dupliquer" en haut de l'ecran detail d'une tache : cree une nouvelle tache pre-remplie avec les memes parametres, qu'il suffit d'ajuster (par ex. changer la piece) avant de valider.
- Dans le formulaire de creation d'une nouvelle tache, des suggestions apparaissent des 2 caracteres tapes dans le titre : selectionner une suggestion remplit tout le formulaire d'un coup.
- Un chip "Duplique de [titre]" indique qu'on est en train de cloner ; tap dessus pour annuler la duplication et repartir d'un formulaire vierge.
- Le clone herite du consensus de cadence et de la repartition d'effort de la tache d'origine, mais demarre avec un historique vide (les votes individuels ne sont pas copies, chacun re-votera au prochain prompt).

## v0.19.0 — 2026-04-24

### Historique : filtres et recherche
- Une barre de recherche permet de filtrer l'historique par titre de tache. Tape "lavabo" et tu ne verras que ce qui touche a la tache "Nettoyer le lavabo".
- Un bouton "Filtres" ouvre un panneau pliable avec :
  - Type d'evenement (3 chips multi-select : Completions, Votes, Modifications) pour ne voir qu'une famille a la fois.
  - Acteur (Tous, Quentin, ***REMOVED***) pour filtrer par personne.
  - Periode (Cette semaine, Ce mois, 3 derniers mois, Tout) pour scoper la fenetre temporelle.
- Les filtres sont combinables et un compteur sur le bouton "Filtres" indique combien sont actifs. Un bouton "Effacer" remet tout a zero.

### Historique : meilleure lisibilite
- Les modifications de cadence affichent maintenant la transition complete : "modifie la recurrence de 'Aspirateur' (recurrence) : tous les 7j → tous les 10j", au lieu d'un simple "(recurrence)" sans contexte.
- Les taches definitivement supprimees gardent leur titre suivi de "(supprimee)" au lieu d'apparaitre comme '?' dans l'historique.
- Les votes de frequence n'apparaissent plus en double : avant, chaque vote etait visible a la fois comme "vote" et comme "modification de recurrence". Maintenant un seul evenement par vote.

## v0.18.1 — 2026-04-24

### Ajustement
- Le slider du mini-dialog "Modifier via le vote" propose maintenant une plage adaptee a la cadence de la tache (de 30% a 300% du consensus actuel) au lieu de toujours afficher 1 a 365 jours. Sur une tache a 5 jours, tu vois maintenant un slider de 2 a 15 jours, et ton vote actuel est visible au milieu de la barre au lieu d'etre colle a gauche. Si tu avais deja vote tres differemment du consensus actuel, la plage est etendue pour inclure ta valeur — pas de troncature silencieuse.

## v0.18.0 — 2026-04-24

### Cadence des taches : enfin pilotee 100% par les votes
- Tu ne peux plus ecraser la cadence d'une tache (champ "tous les X jours") en l'editant directement dans la fiche : le champ devient grise et affiche "Consensus : 7 j (2 votes)". Pour modifier la cadence, un bouton "Modifier via le vote" ouvre un mini-dialog dedie ou tu peux poser ton vote, qui rentre dans le calcul du consensus avec ceux des autres membres du foyer.
- Le mini-dialog affiche le consensus actuel et les votes des autres membres ("Quentin : 7 j") pour que tu voies pourquoi la cadence est ce qu'elle est avant de voter.
- A la creation d'une tache recurrente "tous les X jours", la valeur que tu saisis devient automatiquement ton vote initial — plus de cadence "fantome" qui ne correspond a aucun vote.
- Si tu changes le type de recurrence (ex : passer de "tous les 7 jours" a "variable par mois"), tes votes sont conserves en sommeil. Si tu reviens a "tous les X jours" plus tard, ils reprennent vie automatiquement et la cadence est recalculee.

## v0.17.0 — 2026-04-23

### Mode vacances
- Les streaks (jours d'affilee) ne sont plus casses par les jours d'absence : si vous etes en vacances et que vos jours sont marques absents (via le dialog de resolution de presence), votre serie continue comme si rien ne s'etait passe. Le compteur reste honnete : il affiche toujours le nombre reel de jours d'activite, mais l'absence ne casse plus la serie.
- Meme regle pour les badges "Regulier" / "Semaine parfaite" / "Deux semaines" / "Mois parfait" : ils restent debloquables apres une periode de vacances, sans devoir tout recommencer a zero.
- Seuls les jours **explicitement** marques absents gelent la serie. Les jours "a confirmer" (incertains) la cassent toujours — pensez a resoudre via le dialog au prochain lancement de l'app pour que vos vacances soient bien comptees.

## v0.16.2 — 2026-04-23

### Correction
- Quand on cree une nouvelle tache, on n'est plus prompted instantanement de la noter (affinite + effort). L'app attend maintenant que la tache ait ete faite au moins 2 fois et que la derniere completion remonte a au moins 1 jour avant de proposer la notation. Evite le double travail "je remplis le formulaire de creation puis je remplis le formulaire de notation" et laisse le temps d'eprouver la tache pour pouvoir la noter sincerement.
- Les taches ponctuelles (sans recurrence) ne sont plus jamais proposees a la notation : noter une tache one-shot n'a pas d'utilite long-terme.

## v0.16.1 — 2026-04-23

### Correction
- L'heure du resume quotidien choisie dans les Reglages est enfin respectee des qu'on bouge le slider : on deplace, on relache, la prochaine notif tombe a la nouvelle heure. Plus besoin de tuer et relancer l'app.

## v0.16.0 — 2026-04-22

### Nouveau
- Bouton pour ignorer une suggestion sur l'ecran d'accueil : chaque carte a maintenant une petite croix en haut a droite qui masque la tache et affiche la suivante du meme tier. Les dismiss sont purement locaux et se reinitialisent a chaque redemarrage de l'app.
- Quand toutes les suggestions d'un tier (urgentes / affinite / sans cadence) ont ete ignorees, un message apparait et les taches ignorees redeviennent visibles — rien n'est jamais perdu.

## v0.15.5 — 2026-04-20

### Corrections
- La notification quotidienne sortait a un horaire arbitraire au lieu de l'heure reglee dans les parametres. Cause : l'ancienne planification creee par une version anterieure persistait meme apres mise a jour. Corrige : chaque montee de version replanifie le rappel avec l'heure choisie dans Reglages.

## v0.15.4 — 2026-04-19

### Ajustements
- Le slider de vote de frequence dans la popup de notation ne depasse plus 365 jours, meme quand la tache avait une cadence tres longue. Evite de proposer des votes que le serveur rejetterait.

## v0.15.3 — 2026-04-19

### Corrections
- Le bandeau "Synchronisation en cours" qui restait affiche en permanence est corrige. Certains votes de frequence saisis via le slider (ex : passer a tous les 120 jours) etaient rejetes par le serveur et l'app les rejouait en boucle sans jamais les abandonner, ce qui laissait le bandeau affiche indefiniment.
- Le cap sur les votes de frequence passe de 60 a 365 jours. Tu peux maintenant voter pour des taches qui reviennent annuellement (controle technique voiture, decorations de Noel, etc.).

## v0.15.2 — 2026-04-19

### Notifications
- Le rappel quotidien des taches a faire fonctionne a nouveau : le worker qui envoie la notification n'etait pas authentifie et recevait systematiquement une erreur du serveur, donc aucune notification n'etait produite.
- Nouveau : l'heure de la notification quotidienne est maintenant respectee. Choisis l'heure dans Reglages -> Notifications (slider 6h-22h), la notif arrivera autour de cette heure le lendemain. Avant, WorkManager choisissait une fenetre arbitraire dans la journee.
- Le toggle "Notification quotidienne" dans Reglages desactive correctement le rappel (plus besoin de redemarrer l'app).

## v0.15.1 — 2026-04-19

### Corrections
- Correction d'un bug qui faisait apparaitre certaines taches recurrentes comme "en retard" dans la liste alors qu'elles avaient ete faites recemment (exemple : une tache tous les 45 jours faite il y a 10 jours pouvait s'afficher en retard de 11 jours). L'ecran de detail affichait la bonne info — c'est uniquement la liste qui etait trompee.

## v0.15.0 — 2026-04-19

### Liste des taches
- Nouveau filtre Solo / Groupe (icone 👤) pour separer les taches attribuees a moi seul des taches partagees.

### Edition d'une tache
- On peut maintenant changer le type de recurrence en editant une tache existante (ex : passer d'une recurrence reguliere a "Quand c'est necessaire", ou l'inverse).
- La tolerance au retard n'est plus affichee pour les taches "Quand c'est necessaire" — elle ne s'appliquait pas a ce type et pretait a confusion.

### Corrections
- Les notifications fonctionnent enfin sur Android 13+ : l'app demande la permission au lancement (elle etait declaree mais jamais demandee, Android les refusait silencieusement).

## v0.14.0 — 2026-04-19

### Recurrence "Sans cadence"
- Nouveau choix dans le picker de recurrence : "Quand c'est necessaire" — pour les taches qu'on fait irregulierement sans rythme fixe (ex : lessive, liste de courses).
- Les taches "sans cadence" apparaissent dans une 3e section dediee sur l'accueil, toujours verte, avec un libelle factuel ("Pas encore faite" ou "Fait il y a X j") au lieu d'un compte a rebours.
- L'app apprend automatiquement a quelle frequence tu fais cette tache et la classe en tete quand elle te l'a pas proposee depuis longtemps.
- Le gel des vacances continue de s'appliquer (les jours d'absence ne comptent pas).

### Detail de tache : respect des attributions
- Sur une tache attribuee a une seule personne, l'ecran detail affiche maintenant un bandeau "Pour Quentin uniquement" (ou ***REMOVED***) en haut.
- L'autre personne ne voit plus son propre encart "Effort & Affinite" pour cette tache (il etait trompeur : noter une tache qu'on ne fait pas n'a pas de sens).
- Le bouton "Modifier" de la note d'affinite/effort est masque pour qui n'est pas attribue.

## v0.13.1 — 2026-04-18

### Fix recherche
- Le curseur du champ de recherche revenait en arriere quand on tapait vite : corrige. L'affichage du texte saisi est maintenant synchrone, independant du temps que prend le filtrage en arriere-plan.

## v0.13.0 — 2026-04-18

### Recherche dans les taches
- Nouvelle loupe dans l'ecran Taches, en haut a droite de la rangee de filtres. Cliquer la loupe remplace les chips de filtres par un champ de recherche qui prend toute la largeur.
- Par defaut la recherche ignore tous les filtres actifs (y compris "A faire") et cherche dans toutes les taches.
- Un petit bouton en forme d'entonnoir a cote du champ permet de reappliquer les filtres courants si besoin.
- La recherche matche le titre, la description, le nom de la categorie et le nom de la piece. Insensible aux accents (taper "ecole" trouve "Ecole") et a la casse.
- Recherche multi-mots : "cuisine linge" trouve les taches qui contiennent les deux.

### Refonte de la detection de presence
- La detection automatique en arriere-plan (qui necessitait que l'app tourne en permanence) est supprimee : Android la brimait trop pour qu'elle soit fiable.
- Nouveau fonctionnement : l'app detecte ta presence au lancement quand elle voit le WiFi maison. Les jours ou personne n'a lance l'app sont marques "a confirmer".
- Nouveau dialog de resolution : quand des jours sont incertains, l'app propose au lancement une grille-calendrier ou chaque jour peut etre marque present (vert) ou absent (gris). Pour les longues absences (>30 jours), un mode simplifie "j'etais absent depuis X" apparait a la place.
- Les permissions de localisation en arriere-plan ne sont donc plus necessaires — tu peux les retirer dans les reglages Android si tu veux.

## v0.12.0 — 2026-04-17

### Bonus et malus de points
- Les taches en retard rapportent maintenant un bonus de points (+50% par defaut) quand elles depassent un seuil configurable. Une pastille "🔥 +50%" apparait sur les suggestions concernees.
- A l'inverse, completer une tache tres en avance applique un malus progressif (jusqu'a -50% des points). Plus tu la fais tot dans le cycle, plus le malus est fort.
- La premiere completion d'une tache donne toujours les points pleins (pas de bonus ni de malus).
- Le dialog "Deja fait" / "Fait ensemble" affiche maintenant une ligne de preview des points avant validation : tu vois combien tu vas gagner et pourquoi (bonus retard, malus avance, premiere fois).
- La celebration apres validation affiche les points gagnes (+X pts) avec la raison du bonus ou malus.
- La fiche detail d'une tache affiche le bonus ou malus actuel a cote de l'etat d'urgence.

### Detection des taches penibles
- Quand une tache est systematiquement en retard (plus de 70% des completions en zone bonus sur une fenetre glissante), l'app propose automatiquement a chaque membre du foyer de bumper la penibilite de +1.
- Le modal de bump affiche la liste des dernieres completions avec leur statut, et propose soit un bump rapide, soit d'aller modifier les notes (affinite/effort).
- Un seul prompt s'affiche a l'ouverture de l'app (pas de spam). Apres un prompt, 2h de silence minimum.

### Reglages avances
- Nouveau panneau "Bonus / Malus de points" dans les Options avancees des Reglages, avec tous les seuils configurables par foyer (14 curseurs avec explications).

## v0.11.1 — 2026-04-15

### Fin du "flash" apres une completion sur l'accueil
- Avant : apres avoir valide une tache sur l'accueil, l'animation de celebration se jouait, puis la meme tache reapparaissait brievement pendant 1-2 secondes avant d'etre remplacee par une autre. C'etait lie au fait que le serveur n'avait pas encore vu la validation (synchro en cours) et renvoyait donc la meme suggestion.
- Maintenant : l'accueil charge en arriere-plan un tampon de plusieurs suggestions d'avance (5 urgentes + 5 affinites). Quand tu valides une tache, la suivante apparait instantanement apres l'animation, sans attendre le serveur. La tache que tu viens de valider est automatiquement masquee cote client jusqu'a ce que la synchro rattrape.
- En cas de burst de completions (5 d'affilee sans que la synchro suive), une petite carte "Prochaine suggestion en route..." s'affiche le temps que le backend rattrape, plutot que de te montrer du vide ou une fausse tache.

## v0.11.0 — 2026-04-15

### "Fait ensemble" — partager les taches et les points
- Nouveau bouton **Fait ensemble** (icone groupe) sur chaque tache, present sur l'accueil, la liste des taches et la vue detail. Il permet de dire "on ne l'a pas fait tout seul" : les points sont partages equitablement entre les participants et chacun progresse dans son compteur.
- Dans un foyer a 2 personnes : cliquer ouvre directement un selecteur de date, les deux membres sont automatiquement inclus (pas besoin de cocher).
- Dans un foyer a 3 personnes ou plus : une popup apparait avec les membres assignes pre-coches, un bouton "Ajouter un membre" pour inclure quelqu'un d'autre du foyer, et un selecteur de date. Minimum 1 participant coche pour valider.
- Si quelqu'un a deja valide la tache dans la fenetre anti-doublon, la validation collective est refusee en bloc (aucune ligne creee) et un message l'explique.

### "Deja fait" sur la vue detail d'une tache
- Le bouton **Deja fait** (icone calendrier) est maintenant aussi present sur l'ecran detail d'une tache, en plus de l'accueil et de la liste. Jusqu'ici il manquait a cet endroit.
- Dans la popup "Deja fait", un nouveau bouton "A plusieurs..." permet de basculer vers le flow "Fait ensemble" sans repartir de zero.

### Popup de completion unifiee
- Les deux anciennes popups separees (choisir qui puis choisir quand) sont remplacees par une seule popup claire avec la date et la liste des participants au meme endroit. Plus rapide a valider.

### Nettoyage de l'accueil
- La section "En cours" de l'accueil (qui affichait les taches demarrees mais pas encore validees) est supprimee. Elle etait quasi jamais utilisee et parasitait l'ecran.

## v0.10.2 — 2026-04-15

### Fin des crashs apres mise a jour
- L'app purge maintenant automatiquement son cache local d'ecran d'accueil a chaque nouvelle version installee. Ca evite les crashs comme celui de la v0.10.0 : quand le format des donnees change d'une version a l'autre, l'ancien contenu en cache pouvait faire planter l'app au premier lancement. Maintenant, la premiere ouverture apres une mise a jour repart d'un cache vide et se re-remplit au premier refresh reseau.

## v0.10.1 — 2026-04-15

### Fix crash au demarrage
- Corrige un crash de l'app au demarrage apres la mise a jour v0.10.0 (ecran qui se ferme immediatement quand on ouvre l'app). Cause : les anciennes suggestions encore en cache local n'avaient pas le nouveau champ d'etat ; l'app tentait de les afficher avant le refresh et plantait. Maintenant le rendu est tolerant a ce cas et affiche neutre le temps que le refresh arrive.

## v0.10.0 — 2026-04-15

### Refonte du modele de fraicheur — fini la demi-vie, place a l'echeance
- Avant : chaque tache avait un score de "fraicheur" qui decroissait de facon exponentielle (demi-vie). Probleme : pas intuitif. Une tache "litiere tous les 2 jours" faite il y a 4 jours n'etait toujours qu'en orange, alors que mentalement elle est "en retard de 2 jours".
- Maintenant : chaque tache a une **echeance** (`due_date = derniere_completion + intervalle`) et l'etat est calcule en jours :
  - **Vert** : pas encore due (loin de l'echeance)
  - **Orange** : du aujourd'hui ou bientot (fenetre de `floor(intervalle * 0.10)` jours avant l'echeance — pour 7j elle est de 0 jour, pour 30j de 3 jours, pour 60j de 6 jours)
  - **Rouge** : en retard (apres l'echeance)
- Sur les cartes : couleur + texte countdown ("dans 3j", "a faire aujourd'hui", "en retard de 2 j") affiches systematiquement.
- Au passage, le scenario d'origine est corrige : litiere 2j faite le 11/04 -> rouge "en retard de 2 j" le 15/04 (avant : encore orange, ce qui n'avait aucun sens).

### Tolerance repurposee — uniquement pour le selecteur "la plus urgente"
- La tolerance (STRICT / NORMAL / SOUPLE) ne change plus rien a l'etat affiche d'une tache ni au moment ou elle passe en retard.
- Son seul role : prioriser le selecteur de la "tache la plus urgente" sur la home page :
  - STRICT en retard passe avant NORMAL en retard, qui passe avant SOUPLE en retard. Au sein d'un tier, on prend celle avec le plus gros ratio (jours de retard / intervalle).
  - Si aucune tache n'est en retard, fallback sur les Oranges (toutes tolerances confondues, plus proche de l'echeance d'abord).
- Une bulle ℹ a cote du selecteur de tolerance (dans le detail/edition/creation d'une tache) explique tout ca.

### Nettoyage
- Suppression du knob "Seuil de suggestion" dans les reglages avances (le modele est maintenant deterministe, plus besoin de le configurer).

## v0.9.0 — 2026-04-14

### Catalogue de templates
- Nouvel ecran "Catalogue" accessible via un petit bouton magique a cote du "+" dans l'ecran des taches. Il propose une bibliotheque de taches, de pieces et de categories pretes a l'emploi (vaisselle, aspirateur, nettoyer la sdb, cuisine, salon, menage, linge, ...) en trois onglets.
- Selection multiple (tu coches ce que tu veux) et bouton "Importer N" en bas. Quand les taches que tu importes referencent des pieces ou des categories, une popup de rattachement propose soit de les creer, soit de les mapper sur des elements existants de ton foyer (avec detection automatique des noms similaires — "Cuisine" vs "cuisine" vs "La cuisine" sont detectes comme la meme chose).
- Pratique pour peupler un foyer vide en quelques clics, ou pour t'inspirer et ajouter des taches auxquelles tu n'aurais pas pense.

## v0.8.1 — 2026-04-14

### Proposition d'objectifs en mode foyer
- Le popup de simulation (tap sur les cartes "Moi" ou "Foyer") contient maintenant un slider par membre du foyer : tu peux simuler un plan complet en ajustant les rythmes de chacun et voir en direct en combien de jours le retard serait rattrape. Le bouton "Juste pour moi" reste dispo pour sauver uniquement ton objectif.
- Nouveau bouton "Proposer au foyer" (actif des que tu bouges au moins un slider des autres membres) : il envoie ta proposition en live au reste du foyer. Les autres recoivent un modal bloquant avec ta proposition, ils ont 3 minutes pour accepter ou refuser. Pendant ce temps, tu vois un ecran d'attente qui liste en direct qui a valide, avec un bouton "Annuler la proposition" si tu changes d'avis.
- Quand tout le monde accepte, les objectifs sont sauves pour chacun et une popup de celebration apparait sur les apps de tous les membres ("Vous etes sur la bonne voie vers un foyer propre !") avec un easter egg a decouvrir en cliquant sur le lien en bas.
- Si quelqu'un refuse ou si le delai expire, la proposition est annulee, aucun objectif n'est sauve, et tout le monde est notifie.

## v0.8.0 — 2026-04-14

### Prevision de rattrapage du retard
- Deux nouvelles cartes en haut de l'accueil : "Moi" (minutes faites aujourd'hui, barre de progression vers l'objectif du jour, nombre de jours de rattrapage estime) et "Foyer" (retard courant du foyer, nombre de jours pour etre a zero, rythme de maintien une fois resorbe).
- Popup de simulation : tap sur une carte ouvre un slider pour fixer ton rythme cible en minutes ou en taches par jour, avec feedback live sur le nombre de jours de rattrapage. Le bouton "Definir comme objectif" sauve ton engagement personnel.
- L'unite d'affichage (minutes ou taches) est choisissable dans le popup et sauvee par foyer.

### Accueil plus compact
- Suppression du liseret rouge et de la barre de progression sur les suggestions de taches : la pastille rouge suffisait deja a signaler l'urgence.
- Suppression du titre "Suggestions" (evident vu le contexte).
- Deplacement du message "Tu as fait plus que ta part..." depuis l'accueil vers l'onglet "Resume" des Stats — pour que les cartes de taches et les cartes de prevision tiennent en un seul ecran.

## v0.7.4 — 2026-04-14

### Mise en pause des taches "a la maison" en cas d'absence
- Quand personne n'est detecte au WiFi maison pendant plus de 24h, les taches des categories marquees "a la maison" arretent de vieillir jusqu'au retour. Au-dela des 24h de tolerance, le compteur est fige — plus de retard accumule pendant les vacances.
- Popup "Bon retour a la maison !" au retour, avec le nombre de jours d'absence et un rappel que les taches concernees ont ete mises en pause.
- Detection automatique de la presence WiFi en arriere-plan toutes les 6h et au lancement de l'app.

### Reglage du foyer
- Bouton "modifier" sur chaque foyer (admins uniquement) dans Reglages > Mes foyers : permet de renommer le foyer et d'activer/desactiver la mise en pause des taches "a la maison" en cas d'absence.

### Tooltip adaptatif "a la maison"
- Le tooltip d'info a cote de "A la maison uniquement" (edition de categorie) explique maintenant l'etat reel du freeze : si la mise en pause est desactivee, si tu n'as pas configure ton WiFi maison, si on attend ta premiere connexion, ou si d'autres membres du foyer bloquent encore la detection.

## v0.7.3 — 2026-04-14

### Multi-foyer : isolation users + wifi maison
- Les listes d'utilisateurs dans les pickers (assignation de tache, "qui a fait ?", fair-share, stats, leaderboard) n'affichent plus que les membres du foyer actif. Avant, tous les users de la base apparaissaient meme dans un foyer ou ils n'etaient pas.
- Les SSIDs WiFi maison sont maintenant stockes par foyer : dans ta maison tu configures tes SSIDs, dans la colloc d'un ami tu en configures d'autres, sans melanger. Quand tu basculais de foyer, les SSIDs de l'autre foyer apparaissaient — corrige.

## v0.7.2 — 2026-04-14

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
