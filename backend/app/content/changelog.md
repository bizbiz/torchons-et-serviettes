# Notes de version

## v0.45.0 — 2026-11-16

### Réglages : le foyer d'un côté, toi de l'autre
- L'écran Foyer réunit les fonctionnalités du foyer (Répartition, Motivation, Objectifs…) :
  un changement vaut pour tous les membres.
- Les Réglages ne gardent que ce qui est à toi. La gamification y devient « Masquer points et
  séries pour moi », sans effet pour les autres.
- Une option à toi dont le foyer n'utilise pas la fonctionnalité reste visible, grisée, avec
  un lien vers les réglages du foyer.
- Quand un autre membre coupe une fonctionnalité du foyer, une carte te le dit à l'ouverture.

### Corrigé
- Couper la gamification depuis l'écran Foyer n'avait plus d'effet : les points et les séries
  restaient visibles.

## v0.44.6 — 2026-11-16

### Maintenance
- Nettoyage interne du serveur : les anciennes versions de l'app, déjà refusées, ne sont plus
  prises en charge. Rien ne change dans l'usage.

## v0.44.5 — 2026-11-16

### Création de tâche
- Les contextes viennent en premier et filtrent les propositions.
- Un foyer sans pièce voit tout le catalogue, filtré par le titre.

### Création d'un foyer
- Sauter une étape n'ajoute plus rien : ni pièce, ni tâche.
- Le nom du foyer est déjà rempli et ne se saute plus.
- « Ajouter des tâches » ouvre directement la création d'une tâche.

## v0.44.4 — 2026-11-16

### Confidentialité
- Un lien vers la politique de confidentialité, à la création
  du foyer, en le rejoignant, et dans Réglages.
- Quand un membre quitte le foyer, ses données personnelles
  sont effacées plus complètement au bout de 30 jours.
- Le nom du Wi-Fi de la maison est effacé dès qu'on quitte
  le foyer.

## v0.44.3 — 2026-11-15

### Validations
- Une tâche déjà validée est refusée avant les confettis, avec
  l'heure de la première validation, même hors ligne.
- Un ✓ fait hors ligne garde l'heure où tu l'as touché,
  et non celle de la synchronisation.
- Une tâche solo n'affiche plus « à plusieurs ».
- Un refus « hors du foyer » nomme le membre par son prénom.

### Revote
- Celui qui demande un revote voit que la notification est
  partie, et il est prévenu quand l'autre répond, avec
  l'ancien et le nouveau vote.

### Gamification coupée
- Plus aucun point, série ni classement affiché : agenda,
  profil, stats, historique et fiche de tâche.

### Écrans
- Un foyer sans tâche est invité à en ajouter, et le widget
  dit « Aucune tâche pour l'instant ».
- L'historique d'une tâche montre sa création.
- Les durées longues s'arrondissent au mois (« 2 mois »).
- Stats : « Répartition par contexte ».
- Libellés accentués partout, et traduits en anglais.

### Compte
- Réglages › Supprimer mon compte.
- Le code de récupération copié est masqué dans l'aperçu
  du presse-papiers.

## v0.44.2 — 2026-11-11

### Revote
- La demande de revote arrive tout de suite en notification, une par demande.
- La fenêtre de vote et la notification donnent les contextes de la tâche
  (« Salon · Ménage ») : on sait de quel aspirateur on parle.

### Widget
- Le widget ne montre plus que les tâches à faire. Une tâche validée en sort
  aussitôt, ce qui évite de la valider deux fois.
- « Déjà fait » depuis le widget affiche toujours le nom de la tâche.

### Validations
- Une même tâche validée deux fois de suite par la même personne ne compte plus
  qu'une fois (dans l'heure, ou dans la journée pour une tâche hebdomadaire).

## v0.44.1 — 2026-11-11

### Revote
- Quand un membre redemande l'avis du foyer sur une tâche, tu reçois une notification,
  même app fermée.
- Les demandes de revote ne sont plus retenues deux heures : elles s'enchaînent dès
  que tu réponds à la précédente, ou que tu la fermes.

### Widget
- Toucher le widget ne lance plus l'appli une seconde fois quand elle est déjà ouverte.
- La fenêtre « Déjà fait » ouverte depuis le widget affiche toujours le nom de la tâche.

## v0.44.0 — 2026-11-11

### Notifications
- Une notification le matin liste les tâches passées en retard depuis la veille.
  Elle a son interrupteur et son heure dans Réglages (8 h par défaut).
- Le rappel quotidien donne ta part des tâches en retard : leur nombre multiplié
  par ta part dans la répartition. Il n'est plus bloqué à 5, se tait quand il n'y
  a rien, et marche sans réseau.
- Quand un membre coche une tâche à ton nom, tu es prévenu. Le réglage « Fait
  ensemble avec moi » devient « Quand un membre m'inclut dans une tâche ».
- Une tâche cochée plusieurs jours après coup prévient quand même les autres
  membres, avec sa date.
- Plusieurs tâches cochées à la suite donnent une seule notification, envoyée
  5 minutes après la dernière.

## v0.43.1 — 2026-11-04

### Répartition
- Dans Réglages, on peut fixer la part de chaque membre du foyer (parts égales par
  défaut). Un ⓘ explique le calcul, et chaque changement reste dans l'historique.
- La demande de notation d'une tâche s'espace quand on la ferme sans noter, et revient
  plus souvent quand on note (entre 1 et 10 jours).

### Propositions au foyer
- Une proposition faite au foyer s'affiche chez les autres membres en quelques
  secondes, et la fenêtre d'attente se ferme dès qu'ils répondent, même sans
  notification.

### Modules
- À la connexion, l'app charge tout de suite les modules du foyer : un module éteint
  n'apparaît plus le temps d'un passage dans Réglages.
- Dans un nouveau foyer, activer un module pour tout le foyer n'allume plus les
  modules personnels de chacun, comme Statistiques.
- « Demander à voter » n'apparaît que si l'Ajustement automatique est activé, et plus
  sur sa propre ligne.
- Les guides des modules sont relus et ont chacun un exemple. « Contextes » y remplace
  « pièces » et « catégories ».
- Un ⓘ explique les contextes, sur leur écran et sur le champ Contextes d'une tâche.

### Corrections
- « Fonctionnalités » ne charge plus sans fin quand Réglages a été ouvert hors
  connexion.

## v0.43.0 — 2026-11-03

### Modules
- Chaque module a sa page d'explication : à quoi il sert, pourquoi, et comment bien
  l'utiliser. Dans Réglages, un ⓘ à côté de son interrupteur la rouvre à tout moment.
- La page s'affiche une fois, quand l'app propose le module ou quand on l'active
  soi-même. Quand quelqu'un active un module pour tout le foyer, les autres membres
  la voient à leur prochaine ouverture de l'app.
- Motivation, Bonus et malus, Objectifs et Statistiques sont désormais proposés quand
  le foyer s'y prête, au lieu de rester cachés dans Réglages.
- Deux propositions sont espacées d'au moins 5 jours.

### Corrections
- Hors connexion, l'écran Contextes affiche un message au lieu de fermer l'app.

## v0.42.1 — 2026-11-03

### Quitter le foyer
- Un membre qui revient dans le foyer retrouve aussi les tâches qu'il partageait au moment
  de son départ, sauf celles supprimées entre-temps.

### Widget
- Le widget n'efface plus les tâches à faire sur place quand on est à la maison.

## v0.42.0 — 2026-11-02

### Quitter le foyer
- Un membre qui quitte le foyer est retiré des tâches qu'il partageait ; les tâches
  qui n'étaient qu'à lui sont mises en pause et reviennent s'il revient.
- Pendant 30 jours, un membre parti peut retrouver son foyer avec son code de
  récupération ; passé ce délai, son compte est anonymisé.
- Ses validations restent : son nom y apparaît barré, puis « Ancien membre ».
- L'historique d'une tâche indique « X a quitté le foyer ».

### Corrections
- Hors ligne, « Modifier » ouvre bien la tâche au lieu d'un formulaire vide.
- La ligne des votes de fréquence affiche « tu n'as pas voté » au lieu de
  « toi n'ont pas voté », avec l'espace qui manquait.

## v0.41.5 — 2026-10-18

### Notifications
- Valider une tâche prévient de nouveau les autres membres du foyer qui ont activé la
  notification : jusqu'ici, seul « fait ensemble » les prévenait.
- Plusieurs tâches validées hors ligne arrivent en une seule notification à la reconnexion.
- Une validation enregistrée avec une date de plus d'un jour ne prévient personne.

### Confidentialité
- Les tâches attribuées à une seule personne n'apparaissent plus non plus dans le calendrier
  des autres membres, et l'historique ne s'arrête plus trop tôt quand il en contenait.

## v0.41.4 — 2026-10-18

### Confidentialité
- Une tâche attribuée à une seule personne n'est visible que par elle, peu importe qui l'a créée :
  elle n'apparaît plus chez les autres membres du foyer, ni dans l'historique, ni dans le résumé
  de la semaine, ni dans les exports.
- Sa validation n'envoie plus de notification aux autres membres.

## v0.41.3 — 2026-10-18

### Widget
- Une tâche validée depuis le widget passe au vert tout de suite, sans attendre.
- Le widget ne montre que vos tâches : celles qui vous sont attribuées et celles du foyer.
- Hors du WiFi de la maison, il masque les tâches à faire sur place, comme l'onglet Tâches.

## v0.41.2 — 2026-10-18

### Widget
- Le widget affiche toutes les tâches, dans le même ordre que l'onglet Tâches, et défile quand
  elles ne tiennent pas toutes.
- Chaque tâche porte les trois boutons de la liste : « Fait », « Déjà fait » et « Fait ensemble ».
  Les deux derniers ouvrent l'app sur la fenêtre correspondante.
- Le widget fonctionne hors ligne, avec les dernières données du téléphone, et une tâche marquée
  « Fait » part dès que le réseau revient.

## v0.41.1 — 2026-10-17

### Widget
- Le widget ne dit plus « Tout est frais » quand il n'a pas pu charger les tâches : il affiche
  « Impossible de charger les tâches », et le toucher ouvre l'app.
- Le widget se met à jour dès qu'on quitte l'app.

### Synchronisation
- Une modification que le serveur refuse pour de bon, par exemple une tâche attribuée à quelqu'un
  qui a quitté le foyer, sort seule de la file d'attente. La fenêtre de synchronisation l'indique
  sous « Refusé par le serveur ».

### Corrections
- Le rythme d'une tâche « incertain » compte une tâche faite ensemble une seule fois, et non une
  fois par participant.

## v0.41.0 — 2026-10-16

### Inviter à distance
- L'écran « Inviter quelqu'un » propose « Partager » et « Copier le code » :
  plus besoin d'être côte à côte pour scanner le QR.
- « Rejoindre un foyer » accepte un code collé, seul ou avec le message
  entier.

### Synchronisation
- Une tâche marquée faite hors connexion, par exemple pour l'autre membre,
  ne disparaît plus de l'historique en attendant d'être envoyée.
- Après une coupure, les changements repartent dès la modification
  suivante, au lieu d'attendre parfois plusieurs heures.
- Quand l'envoi échoue, l'icône de synchronisation le signale au lieu de
  tourner comme si de rien n'était.
- La fenêtre de synchronisation liste ce qui attend d'être envoyé, depuis
  quand et pourquoi, et permet d'abandonner une modification que le serveur
  refuse.
- Quand tout va bien, elle affiche le dernier échange avec le serveur, le
  dernier envoi, et les versions de l'application et du serveur.

## v0.40.0 — 2026-10-01

### Un an après la première fois
- Nouvelle récurrence « anniversaire », pour les vaccins, la révision ou le
  contrôle technique : l'échéance part de la première fois que la tâche a été
  faite, et revient tous les ans (ou tous les N mois) à la même date, même si
  on la fait en retard.
- Pour une tâche déjà faite avant d'être créée dans l'app, il suffit
  d'enregistrer la première fois à sa vraie date.

### Corrections
- Quand l'autre membre du foyer modifiait une tâche, elle pouvait apparaître
  vide sur ton téléphone (sans titre, sans personne assignée) jusqu'au
  prochain rafraîchissement.
- Une tâche à date fixe faite en avance, dans la semaine qui précède son
  échéance, ne passe plus en rouge le lendemain : elle compte pour cette
  échéance, et la suivante est visée.
- Le catalogue de tâches proposé à la création d'un foyer est traduit en
  anglais pour les téléphones réglés en anglais.

## v0.39.0 — 2026-09-30

### Les dates fixes tiennent parole
- Une tâche à date fixe (impôts, chaudière, vaccin) affiche enfin son échéance
  et sa couleur dans la liste et dans sa fiche : elle y restait verte quoi qu'il
  arrive.
- Elle prévient une semaine avant l'échéance, et non plus un mois : un vaccin
  annuel passait orange 36 jours à l'avance.
- La fréquence d'une date fixe ne se vote plus : le vote la remplaçait par un
  intervalle, et elle recommençait à glisser.
- Une tâche mensuelle créée avec un ancien format ne ressortait plus depuis
  juillet : elle revient chaque mois, à sa date.

### Mes appareils
- Réglages → Mes appareils : voir les téléphones connectés à ton compte, et
  couper l'accès de celui que tu n'utilises plus. Il ne reçoit plus non plus
  les notifications.

### Corrections
- La synchronisation hors ligne pouvait rester bloquée, voire perdre une
  modification, quand le serveur refusait une seule opération d'un lot.
- Les questions devenues sans objet (tâche supprimée, jours déjà confirmés) se
  referment d'elles-mêmes.
- La courbe de retard des statistiques ne se chargeait plus quand le gel des
  absences était actif.

## v0.38.0 — 2026-08-20

### Les contextes partout
- Pièces et catégories ont disparu : il n'y a plus qu'une liste, les
  contextes, et une tâche peut en porter plusieurs. L'écran de réglages, les
  filtres de l'onglet Tâches, l'édition et l'accueil parlent tous le même
  langage.
- Le gel pendant les absences distingue enfin deux choses : *faut-il être sur
  place* et *est-ce que ça continue sans nous*. Le jardin cesse d'être mis en
  pause pendant les vacances — l'herbe, elle, ne s'arrête pas.

### Ajouter une tâche, en trois secondes
- Le bouton **+** propose des tâches adaptées à ton foyer : la litière si tu
  as un chat, l'arrosage si tu as un potager. Ce que tu as déjà n'est plus
  proposé.
- Tape le début d'un nom et la liste se réduit. En choisir une remplit le
  titre, les contextes, la fréquence et l'effort d'un coup — tout reste
  modifiable ensuite.
- Le bouton « Inspirations » disparaît : il ouvrait un écran auquel plus rien
  ne menait.

### Corrections
- L'accueil affichait « Données indisponibles » dans certaines configurations.
- Les statistiques sur trois mois pouvaient ne pas se charger.

## v0.37.0 — 2026-08-19

### Les contextes remplacent les pièces et les catégories
- Une seule liste au lieu de deux : un contexte, c'est ce qui explique qu'une
  tâche existe chez toi — une pièce, un animal, un extérieur, une situation.
- **Une tâche peut en avoir plusieurs.** « Nettoyer la litière » relève du
  Salon, du Chat et du Ménage à la fois : plus besoin de choisir.
- Deux réglages distincts par contexte, là où il n'y en avait qu'un :
  *il faut être sur place* (masquage hors WiFi maison) et *ça continue sans
  nous* (pas de pause pendant les absences). Le jardin a besoin du premier
  sans le second — l'herbe pousse même en vacances.
- Le filtre de l'onglet Tâches devient unique et accepte plusieurs contextes
  à la fois.

### Création de foyer repensée
- Quatre étapes au lieu de cinq : prénom et nom du foyer sur le même écran, le
  nom étant pré-rempli (« Chez Alice ») et modifiable.
- Le choix se fait sur 34 contextes — animaux, jardin, potager, administratif,
  colocation… — et non plus sur les seules pièces.
- Les tâches proposées sont regroupées par contexte, avec quelques
  pré-cochages utiles, au lieu d'une longue liste à plat.

### Tâches à date fixe
- Nouveau rythme *calendaire* : « en mai », « le 15 mai ». L'échéance est
  ancrée sur le calendrier et ne dérive plus — faire ses impôts en retard une
  année ne décale pas l'année suivante.
- Chaque fréquence est traduite en une phrase claire au moment où tu la
  règles, en français comme en anglais.

### L'app grandit avec toi
- Les fonctionnalités s'activent depuis les Réglages : répartition,
  motivation, bonus et malus, objectifs, absences… Rien n'est imposé au
  premier lancement, et l'app propose le reste au fil de l'usage.

### Corrections
- Les accents manquants de dix-sept tâches du catalogue sont rétablis
  (« Aérer la maison », « Nettoyer la litière », « Vérifier les factures »…).
- Supprimer une pièce puis en recréer une du même nom échouait sans message.

## v0.36.1 — 2026-07-05

### Retard — cohérence des compteurs
- Le nombre de tâches « en retard » de l'Accueil correspond désormais à ce
  que tu vois dans l'onglet Tâches : même définition (jour entier, gel des
  jours d'absence), et une tâche jamais faite n'est plus comptée en retard
  dès sa création.
- La courbe de retard sous ce nombre suit exactement le même calcul (toutes
  les tâches, y compris perso) et son dernier point colle au compteur, même
  en cours de journée.

## v0.36.0 — 2026-07-05

### Accueil
- Les boutons de validation en bas des tâches sont remplacés par une
  navigation par jour : tu choisis le jour concerné avant de valider.
- Un indicateur d'état de synchronisation apparaît, et les points
  affichés s'adaptent au jour sélectionné.

### Célébration après validation
- La célébration qui suit une validation affiche désormais les points
  gagnés et à qui ils vont — avec la précision quand ce n'est pas pour
  toi. Avant, ce détail n'apparaissait que depuis l'Accueil ; il est
  maintenant aussi présent quand tu valides depuis la liste des tâches
  ou depuis le détail d'une tâche.
- Sa durée a été ajustée pour rester visible sans traîner.

## v0.35.18 — 2026-05-22

### i18n — Stats, historique, badges
- Carte **« Cette semaine »** (haut de Stats) : titre, plage de
  dates « du X au Y », statistiques par membre, Top tâches, message
  « Pas encore de tâche cette semaine » — désormais bilingues et
  avec les accents propres en français.
- Carte **« Répartition par pièces »** (Stats long-terme) : titre,
  chips Mois / Trimestre / Année, libellés des barres, message vide
  — bilingues.
- **Historique des activités** (modal profil + onglet Historique
  des Stats) : les descriptions générées côté serveur
  (« X a complété : Y (+Z pts) », « X a noté l'affinité de Y à
  S/10 », « X a modifié la tâche Y (récurrence) », etc.) sont
  désormais traduites selon `Accept-Language`. Plus de FR forcé.
- **Badges** : les 12 badges du catalogue (Première tâche, Régulier,
  Roi de la pénibilité, etc.) sont désormais traduits côté serveur
  selon la langue du téléphone.

## v0.35.17 — 2026-05-22

### i18n — correctif countdown + nom d'app
- Le countdown des cartes sur l'Accueil («&#160;en retard de 3 j&#160;»,
  «&#160;dans 5 j&#160;», «&#160;Fait il y a 2 j&#160;»…) suit
  désormais la langue du téléphone — il était resté FR-only par
  oubli dans le Lot 1.
- Le **nom d'app** affiché sur le launcher Android (« Torchons &
  Serviettes » prod, « T&S Beta » beta) ne contient plus l'artefact
  « `&` » visible depuis le ship 0.35.16 (double-
  escape de l'esperluette dans `build.gradle.kts`).

## v0.35.16 — 2026-05-21

### Popup d\'absence unifiée avec le modal Présence & séries
- La popup qui apparaît automatiquement à l\'ouverture quand l\'app
  détecte des **jours d\'absence à confirmer** ouvre désormais le
  **même modal éditable** que celui qu\'on a partout ailleurs
  (écran Foyer, Stats, profil) — fini les 2 surfaces différentes
  qui faisaient la même chose. Tu peux maintenant éditer
  n\'importe quel jour (pas juste les jours détectés douteux),
  voir tes séries / records, switcher entre membres du foyer,
  changer de mois — tout en un seul endroit.
- Les modifications sont enregistrées immédiatement à chaque tap.
  À la fermeture du modal, le rappel se marque comme résolu et le
  bandeau «&#160;jours non confirmés&#160;» disparaît de l\'accueil.

## v0.35.15 — 2026-05-21

### Traduction EN — Messages d\'erreur backend
- Les messages d\'erreur les plus visibles (33 cas) sont désormais
  bilingues, traduits côté serveur en fonction de la langue du
  téléphone. Concerne tous les flows critiques&#160;: **rejoindre un
  foyer** (QR code expiré, déjà utilisé, etc.), **retrouver mon foyer**
  (code invalide, etc.), **quitter / changer de foyer**, **valider une
  tâche** (anti-doublon, participants hors foyer), **complétion d\'une
  tâche** introuvable, etc.
- Si ton téléphone est en anglais&#160;: «&#160;Invalid invitation
  code&#160;» au lieu de «&#160;Code d\'invitation invalide&#160;»,
  «&#160;Task already completed 5 min ago. Next slot at…&#160;» au lieu
  de la version FR, etc.
- Les ~60 autres messages backend restent en français pour l\'instant
  (migration progressive — pas de régression&#160;: les anciens clients
  voient toujours du FR).

## Site web v0.35.14b — 2026-05-21

### Site bilingue (partiel)
- Le site web torchonsetserviettes.app est maintenant accessible en
  anglais sur les pages principales&#160;: **accueil**, **À propos**,
  **Contact** et **page 404**. Sélecteur de langue dans le menu.
- Pages **FAQ**, **Politique de confidentialité**, **Nouveautés** et
  **Feuille de route** restent en français pour le moment (le
  sélecteur les marque «&#160;FR&#160;» avec un petit «&#160;EN
  bientôt&#160;»). Traduction prévue dans un prochain ship dédié —
  contenu juridique sensible qui mérite une relecture soignée.

## v0.35.14 — 2026-05-21

### Traduction EN — Catalogue de templates
- Les **noms des pièces** (Cuisine&#160;→&#160;Kitchen, Salle de bain
  &#160;→&#160;Bathroom, Buanderie&#160;→&#160;Laundry room, …), les
  **catégories** (Ménage&#160;→&#160;Cleaning, Linge&#160;→&#160;Laundry,
  Administratif&#160;→&#160;Admin, Soin personnel&#160;→&#160;Personal
  care, …) et les **41 tâches** du catalogue (Faire la
  vaisselle&#160;→&#160;Do the dishes, Passer l\'aspirateur&#160;→&#160;
  Vacuum, Détartrer la cafetière&#160;→&#160;Descale the coffee maker,
  …) sont désormais traduits en anglais.
- L\'app envoie automatiquement la langue de ton téléphone au serveur,
  donc importer le catalogue depuis l\'écran «&#160;Catalogue&#160;»
  créera des tâches en anglais si ton téléphone est en anglais, en
  français sinon.

## v0.35.13 — 2026-05-21

### Traduction EN — Onboarding complet
- Les écrans d\'onboarding sont désormais bilingues&#160;: **Welcome**
  (3 boutons Créer / Rejoindre / Retrouver), **Login** (Tailscale
  fallback, choix d\'utilisateur), **Wizard Créer un foyer** complet
  (5 étapes&#160;: prénom, nom du foyer, pièces, tâches, code de
  récupération), **Rejoindre un foyer** (scan QR + saisie prénom +
  ajout d\'appareil + écran code de récupération), **Retrouver mon
  foyer** (saisie code + confirmation d\'identité avec pluriels), et
  **Inviter quelqu\'un** (QR code + variants ajout appareil vs invité).
- Les rôles dans l\'écran restore («&#160;admin / ancien membre /
  membre&#160;») sont localisés.
- **L\'application Android est maintenant entièrement bilingue
  FR&#160;+&#160;EN.** Bascule la langue du téléphone et tu verras
  toute l\'app changer. Reste à traduire&#160;: messages d\'erreur
  backend, catalogue de templates de tâches, et site web.

## v0.35.12 — 2026-05-21

### Traduction EN — Détail & édition de tâche
- L'écran **Détail d'une tâche** est désormais bilingue : état d'urgence,
  bandeau «&#160;Pour…&#160;», bandeau «&#160;tu n'as pas voté&#160;»,
  card Effort & Affinité avec liens « + d'info », fréquence,
  tolérance, historique des complétions (cartes individuelles et
  collectives) et historique des modifications (votes + édits) avec
  les noms de champs traduits.
- L'écran **Édition / Création de tâche** est bilingue : titre, chip
  «&#160;Dupliqué de…&#160;», suggestions, dropdowns catégorie / pièce /
  fréquence, champ «&#160;tous les combien&#160;», breakdown smart,
  consensus avec pluriels, tableau 12 mois (noms de mois localisés),
  tolérance et effort.
- L'app est maintenant traduite à environ 95&#160;%. Reste à traduire :
  écrans d'onboarding (Welcome / Login / création de foyer / scan QR),
  catalogue de templates et site web.

## v0.35.11 — 2026-05-21

### Traduction EN — Accueil & Tâches
- Les onglets **Accueil** et **Tâches** sont désormais bilingues : titre,
  salutation, cards "Moi"/"Foyer", suggestions, boutons (Fait /
  Déjà fait / Ensemble), filtres tri-state, recherche, et tous les
  dialogs de proposition entre membres s'affichent en anglais si ton
  téléphone est en anglais. Les pluriels (1 jour vs N jours, 1 pt vs N
  pts) sont gérés correctement dans les deux langues.
- L'écran **Détail d'une tâche** et **Édition** restent en FR pour
  l'instant — leur traduction arrive dans la prochaine version.

## v0.35.10 — 2026-05-21

### Traduction EN — Catalogue
- L'écran **Catalogue** (import de templates pré-faits) est désormais
  bilingue : titres d'onglets, dialog de rattachement et résumé d'import
  s'affichent en anglais si ton téléphone est en anglais. Les pluriels
  («&#8239;1 tâche créée&#8239;» vs «&#8239;3 tâches créées&#8239;»)
  sont gérés correctement dans les deux langues.

## v0.35.9 — 2026-05-20

### Préparation app bilingue (FR + EN)
- Démarrage de la traduction de l'app en anglais : les écrans
  **Catégories** et **Pièces** (depuis l'écran Foyer) sont les premiers
  traduits. Mets ton téléphone en anglais : ces 2 écrans s'affichent
  en anglais. Les autres écrans restent en français le temps de la
  migration progressive.

## v0.35.8 — 2026-05-20

### Accueil & Tâches à jour automatiquement
- L'onglet **Accueil** se met à jour à chaque ouverture (projections,
  prompts, fair-share) — plus besoin de pull-to-refresh en revenant
  dessus après une modification ailleurs.
- L'onglet **Tâches** : pareil, les urgences/retards se rafraîchissent
  à l'ouverture si tu as touché à la présence ou autre dans une autre
  surface.

## v0.35.7 — 2026-05-20

### Stats à jour automatiquement
- L'onglet Stats se met à jour automatiquement chaque fois que tu
  l'ouvres : si tu as complété une tâche, modifié une présence ou
  changé quoi que ce soit ailleurs dans l'app, ta série, le
  leaderboard et les badges reflètent ces changements sans
  pull-to-refresh.

## v0.35.6 — 2026-05-20

### Affichage à jour & libellés
- Le sous-titre « Connecté via Tailscale » du profil (Réglages) est
  retiré : trompeur sur la beta qui utilise Scaleway. Le canal effectif
  reste visible dans Réglages > Infos > Build.
- Après modification de la présence dans le modal « Présence & séries »,
  le bloc Série du Résumé (onglet Stats) se met à jour automatiquement
  à la fermeture du modal — plus besoin de pull-to-refresh.

## v0.35.5 — 2026-05-19

### Catalogue de suggestions enrichi
- Deux nouvelles catégories suggérées : « Soin personnel » (masque
  visage, RDV coiffeur/dentiste/médecin) et « Animal » (eau, litière,
  gamelles, panier).
- Catégorie « Entretien » enfin fournie : filtres lave-linge /
  lave-vaisselle, détartrer la cafetière, aiguiser les couteaux,
  dégivrer le congélateur, nettoyer l'aspirateur, les interrupteurs…
- Plus de suggestions Jardinage (tailler, rempoter, saison) et
  Administratif (impôts, déclarer mes heures, abonnements, papiers).
- La catégorie « Courses » et sa suggestion sont retirées (d'autres
  apps le font mieux).

## v0.35.4 — 2026-05-19

### Création de foyer : vraiment corrigé (contrainte base de données)
- Le vrai blocage du « foyer vide » était une contrainte de base trop
  stricte : un nom de pièce ou de catégorie devait être unique sur
  TOUTE l'app, au lieu d'être unique par foyer. Créer un foyer avec des
  pièces/tâches déjà présentes dans un autre de tes foyers échouait.
  Désormais un même nom de pièce/catégorie peut exister dans plusieurs
  foyers — l'import du parcours « Créer un foyer » fonctionne.

## v0.35.3 — 2026-05-19

### Création de foyer : pièces/tâches enfin créées (fix serveur)
- Correction du bug où créer un foyer (en étant déjà membre d'un autre)
  laissait le foyer **vide** : l'import échouait côté serveur quand deux
  éléments du catalogue portaient le même nom. Les pièces, catégories
  et tâches choisies dans le parcours sont maintenant bien créées.

## v0.35.2 — 2026-05-19

### Création de foyer : erreurs visibles
- Si l'import des pièces/tâches échoue pendant « Créer un foyer », un
  message d'erreur explicite s'affiche désormais (avant : le foyer
  pouvait rester vide sans aucune explication).

## v0.35.1 — 2026-05-19

### Correction création de foyer
- Les pièces et tâches choisies dans le parcours « Créer un foyer »
  apparaissent maintenant tout de suite dans le nouveau foyer (avant :
  il fallait changer de foyer puis revenir pour qu'elles s'affichent).

## v0.35.0 — 2026-05-19

### Créer un foyer : vrai parcours guidé
- « Créer un nouveau foyer » ouvre désormais le même parcours que la
  première création (nom du foyer, puis pièces, puis tâches) au lieu
  d'un simple champ « nom ». Le foyer est prêt à l'emploi et l'app
  bascule dessus automatiquement. Ton prénom et ton code de
  récupération ne sont pas redemandés (compte déjà existant).

## v0.34.4 — 2026-05-19

### Nom du foyer à jour dans les Réglages
- Dans les Réglages, la ligne « 🏠 <nom du foyer> » se met maintenant à
  jour quand tu changes de foyer ou en quittes un depuis l'écran Foyer
  (avant : elle pouvait afficher un foyer déjà quitté tant qu'on ne
  rouvrait pas l'écran Foyer).

## v0.34.3 — 2026-05-19

### Foyers quittés masqués de « Changer de foyer »
- Les foyers que tu as quittés n'apparaissent plus dans la liste
  « Changer de foyer » (avant : ils restaient affichés en « Former
  member »).

## v0.34.2 — 2026-05-19

### Foyers : affichage à jour sans rafraîchir
- Après avoir créé un foyer, l'option « Changer de foyer » apparaît
  tout de suite dans l'écran Foyer (plus besoin de revenir ou de
  recharger l'écran).
- Quand tu quittes un foyer, il disparaît immédiatement de la liste
  « Changer de foyer ». Réessayer de quitter un foyer déjà quitté
  n'affiche plus d'erreur (404).

## v0.34.1 — 2026-05-19

### Séries : calcul corrigé + affichage clair
- La série n'était plus calculée correctement quand elle traversait deux
  mois (ex : du 19 avril au 6 mai) et le record était faux. C'est corrigé :
  la série et le record sont maintenant calculés sur tout l'historique
  (plus de troncature au mois affiché).
- Nouveau repérage visuel dans le calendrier : un liseré **or** entoure
  les jours de ta **meilleure série (record)**, un liseré **indigo** ceux
  de la **série en cours**. Le fond des cases reste la présence
  (vert / rouge doux / gris) et l'étoile marque les jours où une tâche a
  été faite. Légende mise à jour.

## v0.34.0 — 2026-05-18

### Présence & séries : un seul agenda
- Le calendrier de présence et celui des séries ne font plus qu'un : un
  seul écran "Présence & séries" où tu poses les jours présent / absent,
  vois les étoiles des jours où une tâche a été faite et la série
  reliée. Ouvrable depuis l'écran Foyer (bouton "Ouvrir l'agenda de
  présence & séries"), depuis Stats (bloc série du résumé) et depuis le
  profil d'un membre.
- L'écran Foyer ne contient plus de calendrier en double : il garde les
  réglages de présence (mise en pause si absent, WiFi maison) et un
  bouton vers l'agenda.
- Modifier un jour de présence recalcule aussitôt le retard des tâches
  maison et la série.

## v0.33.1 — 2026-05-18

### Corrections (écran Foyer / présence / séries)
- Le nom du foyer s'affiche immédiatement à l'ouverture de l'écran Foyer
  (fini le bref "Mon foyer" affiché le temps du chargement).
- Calendrier des séries : les étoiles et le trait de série restent
  désormais à l'intérieur du calendrier (plus de marques qui débordaient
  hors de la grille).
- Modifier la présence (présent / absent) recalcule aussitôt le retard
  des tâches maison et la série : l'affichage est à jour sans avoir à
  recharger.

## v0.33.0 — 2026-05-18

### Écran Foyer dédié
- Les réglages liés au foyer sont regroupés dans un écran "Foyer" dédié
  (titre = nom du foyer), accessible depuis la ligne du foyer dans les
  Réglages et depuis la card "Foyer" de l'accueil. Deux groupes clairs :
  "Ce foyer" (partagé entre membres) et "Mon compte" (vaut pour tous tes
  foyers), avec un (i) qui explique le périmètre.
- L'ancien écran "Mes foyers" disparaît : "Créer un nouveau foyer" est un
  bouton dans les Réglages (bascule automatique sur le foyer créé), et
  "Changer de foyer" est une feuille de sélection directement dans l'écran
  Foyer — affichée seulement si tu as au moins deux foyers.
- Pièces et Catégories sont désormais rangées sous l'écran Foyer. Un bouton
  "Simuler le rattrapage" y ouvre le même simulateur que la card "Foyer".

### Calendrier de présence éditable
- Un calendrier dans l'écran Foyer permet de poser ou effacer les jours
  présents/absents, dans le passé comme dans le futur (planifier une
  absence à l'avance). Trois états : vert = présent, rouge doux = absent
  (tâches maison gelées ce jour), gris = non renseigné (compte absent par
  défaut). Une icône indique la source : Wi-Fi (détecté) ou main (posé
  manuellement).
- Un jour posé à la main n'est jamais écrasé par la détection Wi-Fi
  automatique (priorité au manuel). Légende et (i) détaillée sous la
  grille. C'est la même grille que le rappel "Jours à confirmer", avec un
  lien "Voir tout le calendrier" depuis ce rappel.

### Calendrier des séries
- Depuis l'onglet Stats (bloc série du résumé) et le profil d'un membre,
  un calendrier des séries en lecture seule : une étoile sur chaque jour
  où le membre a fait au moins une tâche, et un trait qui relie les jours
  actifs. La série passe au travers des jours confirmés absents (une
  absence légitime ne casse pas la série) et se casse sur un jour
  incertain ou un jour présent sans tâche. Sélecteur de membre intégré ;
  lien "Modifier la présence" vers l'écran Foyer.

## v0.32.0 — 2026-05-12

### Gamification — désactivation possible
- Nouveau switch "Activer la gamification pour mon compte" en haut des Réglages. Si désactivé, tu ne vois plus les points, les bonus, les stats, le leaderboard, les badges, les streaks et le fair-share dans aucun de tes foyers. La tab Stats disparaît de la barre de navigation pour rendre l'app plus épurée.
- Nouveau switch "Gamification dans ce foyer" dans Réglages > Options avancées. N'importe quel membre peut le toggler — un dialog de confirmation rappelle que l'autre membre sera aussi affecté avant validation.
- Quand la gamification est désactivée, la célébration après une tâche validée reste (confetti + emoji + message d'encouragement) mais sans le "+X pts".
- Nouveau lien "Pourquoi je n'ai pas de points pour certaines tâches ?" dans les Réglages — visible uniquement si tu es dans un état sans gamification (toi-même OU au moins un de tes foyers). Il explique la cause précise (toi, un foyer, plusieurs causes cumulées, multi-foyer mixte) et propose un bouton pour réactiver directement.
- Les textes du popup d'info sur l'effort de la tâche adaptent leur formulation : si la gamification est désactivée, on ne mentionne plus les points ni le fair-share, on parle uniquement d'estimation de temps.

## v0.31.3 — 2026-05-12

### Gamification — lisibilité des points
- Affichage des points prévus sur chaque carte de la page d'accueil ("💰 N pts"), avec mention du bonus retard quand applicable ("🔥 +M pts bonus retard")
- Icône (i) cliquable à côté des tâches solo (0 point) qui ouvre une pop-up explicative : pourquoi un seul participant = pas de points, et pourquoi valider la tâche reste utile
- Même pop-up disponible dans le dialog "Fait !" quand la tâche ne rapportera pas de points

## v0.31.2 — 2026-05-12

### Absences (gel à la maison)
- Le dialog "Jours à confirmer" pré-coche désormais tous les jours en
  absent par défaut. Plus besoin de tout toggler après une longue absence
  — il suffit de marquer les quelques jours où tu étais réellement là.
- Les tâches "à la maison" ne s'affichent plus comme en retard pendant
  les jours d'absence confirmés. Le retard est gelé et reprend à ton
  retour, sans cumul de "dette" injuste.
- Les stats (hebdo, mensuel, total, leaderboard, long-terme, totaux du
  couple, résumé hebdo) ignorent maintenant les complétions tombant sur
  un jour absent — cohérent avec le gel appliqué aux tâches.
- Après avoir résolu un prompt d'absence, les tâches sont automatiquement
  recalculées : l'affichage est toujours bon, plus d'incohérence visuelle.

## v0.31.1 — 2026-05-01

### Sous le capot
- Mise en place d'un export CSV de l'historique côté serveur
  (complétions + tâches). Accessible uniquement via curl avec le
  JWT pour l'instant ; un écran "Mes données" dans les Réglages
  suivra dans une itération ultérieure.
- Cette release embarque aussi v0.31.0 (stats hebdo + globales
  couple + long-terme + objectifs + add-device) qui n'avait jamais
  été installée sur le device.

## v0.31.0 — 2026-04-30

### Statistiques hebdomadaires
- Une nouvelle card "Cette semaine" en haut de l'onglet Stats résume ta
  semaine du foyer : nombre de tâches, points cumulés, classement par
  membre avec emoji 🏆 sur le top, et top 3 des tâches les plus faites.
  Flèches gauche/droite pour naviguer dans les semaines passées.

### Statistiques globales du couple
- Le modal de profil utilisateur (cliquer sur un nom dans Stats) affiche
  désormais une card "Total du foyer" avec 3 colonnes (cette semaine /
  ce mois / au total). Si le foyer enchaîne plusieurs jours d'affilée
  avec >=1 tâche faite par n'importe qui, un compteur "🔥 N jours
  d'affilée" apparaît. La section "Répartition" (barres par membre)
  est conservée en complément.

### Stats long-terme par pièces
- Nouvelle card "Répartition par pièces" sur Stats : barres horizontales
  pour visualiser quelle pièce concentre le plus de tâches sur la
  période (Mois / Trimestre / Année). Affiche aussi le temps cumulé
  estimé (déduit du consensus effort de chaque tâche, 1pt = 5min).

### Objectifs personnels et partagés
- Nouvelle card "Mes objectifs" sur Stats. Tape sur + pour créer un
  objectif (ex: 3 tâches par semaine), avec choix de période (par jour/
  semaine/mois) et de la cible (1 à 30). La progression est calculée
  automatiquement et affichée avec une barre de progression par
  objectif. Long-press pour supprimer.

### Ajouter un de mes appareils
- Une nouvelle card "Ajouter un de mes appareils" dans les Réglages
  permet de générer un QR pour connecter une tablette ou un 2ème
  téléphone à ton compte sans créer de nouveau membre dans le foyer.

## v0.30.1 — 2026-04-30

### Sécurité du foyer (Réglages)
- Nouvelle section "Sécurité du foyer" dans les Réglages avec deux
  actions :
  - "Régénérer mon code de récupération" : remplace ton ancien code
    par un nouveau, affiché une seule fois avec boutons Copier et
    Partager. Utile si tu n'avais pas encore de code, ou si tu
    soupçonnes que l'ancien a fuité.
  - "Quitter le foyer" : tu sors du foyer en préservant ton historique
    côté serveur. Si tu es membre d'un autre foyer, tu y bascules
    automatiquement ; sinon tu retombes sur l'écran d'accueil.

### Soutenir / Discuter avec le dev
- Nouvelle card en bas des Réglages avec un mot du dev (auteur solo,
  solo). Deux boutons : "Payer un café" (ouvre la page Buy Me a
  Coffee dans le navigateur) et "M'écrire" (ouvre l'app mail avec
  un brouillon vers le dev). Les retours, idées et bugs sont les
  bienvenus.

## v0.30.0 — 2026-04-29

### Inviter quelqu'un dans le foyer
- Une nouvelle card "Inviter quelqu'un" dans les Réglages permet
  de générer un QR code d'invitation valide 15 minutes. La personne
  invitée installe l'app, choisit "Rejoindre un foyer" et scanne le
  QR pour devenir membre du foyer.

### Onboarding sans compte
- À la 1re ouverture de l'app (ou après une désinstallation), un
  écran d'accueil propose trois choix : "Créer un foyer",
  "Rejoindre un foyer" (scan QR), ou "Retrouver mon foyer" (code de
  récupération). Plus besoin de créer un compte avec username.
- Le parcours "Créer un foyer" guide en 5 étapes : ton prénom, le
  nom du foyer, les pièces de chez toi, les tâches que tu veux
  suivre (tirées du catalogue), puis ton code de récupération.
- Pour l'instant le réseau Tailscale maison continue de te connecter
  automatiquement comme avant — tu ne verras le nouvel accueil que
  si tu réinstalles l'app.

### Code de récupération
- À la création ou à la jonction d'un foyer, un code unique de 8
  caractères (XXXX-XXXX) est affiché. Note-le : il te permet de
  retrouver ton foyer + tes droits d'admin si tu perds ton téléphone.
  Affiché une seule fois, partageable par mail/messagerie via un
  bouton dédié.

## v0.29.0 — 2026-04-29

### Modal "+ d'info" sur l'effort
- Sur la fiche d'une tâche, dans la section "Effort & Affinité", un
  nouveau lien "Effort & points · + d'info" ouvre un modal qui
  explique à quoi sert le vote effort : calcul des points gagnés
  quand la tâche est validée, partage équitable (fair-share) entre
  membres, et retard global du foyer.
- Le modal liste les votes effort de chaque membre avec la date du
  vote. Boutons "Modifier mon vote" sur ta ligne, "Demander revote"
  sur celle des autres, et "Demander à voter" pour les membres
  assignés qui n'ont pas encore voté.
- Symétrique du modal "+ d'info" déjà existant pour la fréquence.

## v0.28.2 — 2026-04-29

### Icône de notification reconnaissable
- Les notifications affichent désormais la silhouette du chapeau de magicien dans la barre de statut, au lieu d'un carré blanc générique. La pastille de fond reprend la couleur indigo de l'app sur Android 8+.

## v0.28.1 — 2026-04-28

### Notifications push enfin fiables
- Le token de notification est désormais re-synchronisé au démarrage de l'app, plus seulement après un login. Avant ce fix, un utilisateur connecté depuis longtemps via Tailscale ne renvoyait jamais son token au serveur — résultat : aucune notif arrivait. Ouvrir l'app suffit maintenant à corriger.

## v0.28.0 — 2026-04-28

### Bandeau "Tu n'as pas voté" sur le détail des tâches
- Sur le détail d'une tâche, un bandeau bleu apparaît quand tu n'as pas encore exprimé ton avis (fréquence, affinité, ou effort). Cliquer dessus ouvre le formulaire de vote, pré-rempli avec les valeurs déjà saisies. C'est purement une invitation : libre à toi de l'ignorer.
- Le sous-texte sous la fréquence change selon l'état des votes : "Moyenne des votes de toi & l'autre membre", ou "Vote de l'autre membre · tu n'as pas voté", ou "Pas encore voté".

### Modaux "+ d'info" sur la fréquence et l'affinité
- Un lien "+ d'info" sous la fréquence ouvre un modal qui explique le mécanisme de vote, montre la valeur de chacun avec sa date, et permet de modifier ton vote ou de demander à un autre membre de revoter.
- Un modal équivalent sur l'affinité explique pourquoi c'est une préférence perso (qui n'influence que tes propres suggestions), et te laisse modifier ton avis. Pas de bouton "demander à quelqu'un d'autre" pour l'affinité : c'est subjectif et personnel.

### Demander à quelqu'un de revoter
- Depuis le modal "+ d'info" fréquence, un bouton "Demander revote" côté utilisateur envoie une demande dans sa file d'attente. Quand l'autre ouvre l'app, le formulaire de vote pop avec un message "X t'a demandé de revoter sur cette tâche". Idem pour l'effort.
- Une demande déjà envoyée est visible (bouton grisé "Demande envoyée") pour éviter d'envoyer 10 fois la même.

## v0.27.0 — 2026-04-28

### Fréquence des tâches : un vote, simple et clair
- À la création d'une tâche, on parle désormais explicitement de "vote" : ta saisie est ton vote sur la fréquence, pas une règle imposée. Si tu es seul à voter, c'est ton vote qui devient la fréquence affichée.
- Quatre choix de type de tâche, en mots clairs : "Régulière", "Selon le mois", "Irrégulière", "Une seule fois". Trois anciens types techniques (jours fixes de semaine, mensuel à jour fixe, toutes les N semaines) disparaissent — ils n'étaient pas utilisés et compliquaient l'interface.
- Le mini-dialog "Modifier mon vote" propose deux modes : "Régulière" (un slider unique) ou "Selon le mois" (12 sliders mensuels). Pratique pour les tâches saisonnières : tondre la pelouse tous les 7 jours en été et tous les 30 en hiver, par exemple.
- Le slider de création pose la bonne question : *"Selon toi, tous les combien faut-il faire cette tâche ?"*

## v0.26.0 — 2026-04-27

### Filtres de la liste des tâches en mots clairs
- Les six filtres en haut de la liste (à faire / aimées / long / récurrentes / maison / solo) ne sont plus des icônes mais des chips texte. Plus besoin de deviner ce que veut dire la flamme ou le cœur.
- Trois états au lieu de deux : un clic active le filtre positif (fond rouge, "À faire"), un deuxième clic le bascule en filtre inverse (fond vert, "Pas à faire"), un troisième clic l'enlève (juste le texte avec un contour).
- Les chips défilent horizontalement quand il y en a trop pour la largeur de l'écran.

## v0.25.1 — 2026-04-27

### Ajustements de la mini-courbe Retard sur l'Accueil
- La carte du retard du foyer remonte maintenant la phase de création initiale du backlog (mêmes premiers points filtrés que sur l'écran Stats), pour une lecture plus claire des 30 derniers jours utiles.
- La carte est repositionnée tout en bas de l'Accueil, sous les tâches urgentes : c'est une lecture contextuelle, pas une action à entreprendre.

## v0.25.0 — 2026-04-27

### Retard du foyer sur l'Accueil
- L'Accueil affiche désormais une mini-courbe du retard du foyer sur les 30 derniers jours glissants, sous les cartes "Rythme" et "Retard". Permet de voir d'un coup d'œil la tendance récente sans aller dans les Stats.
- Pas de triche sur la phase de création pour cette vue : on montre les 30 derniers jours fidèles à la réalité, contrairement à la vue "Depuis le début" des stats qui démarre au pic.

### Sélecteur de période sur le graphique Retard
- Dans Stats, le graphique "Retard" gagne deux chips en haut : "Depuis le début" (défaut, trim de la phase de création) et "30 derniers jours" (vue glissante récente).

## v0.24.0 — 2026-04-27

### Récap "Cette semaine" en tête des stats
- Une nouvelle carte en haut de l'onglet Résumé montre tes points de la semaine, ton "+/- X% vs semaine précédente", le jour où le foyer a fait le plus de tâches, et la catégorie la plus active.
- Carte automatiquement masquée si rien à afficher (foyer tout neuf ou début de semaine).

### Stats du foyer dans le profil utilisateur
- Quand on clique sur le nom de quelqu'un (dans une carte d'historique, un classement, etc.), la modal de profil affiche désormais aussi la répartition entre les membres sur la semaine et au total, avec une barre colorée.
- Le nom de l'utilisateur ouvert est en gras dans la légende pour le repérage immédiat.

## v0.23.0 — 2026-04-27

### Statistiques enrichies
- Sur les courses de points 7 jours et 30 jours, un "+/- X% vs avant" en couleur (vert si en progrès, rouge si en recul) compare à la période précédente.
- Un nouveau graphique "Répartition par catégorie" arrive en dernière position du swiper : top 8 des catégories que tu fais le plus, avec un breakdown par utilisateur. Sélecteur de période intégré (Semaine / Mois / 3 mois / Tout).
- Le graphique "Par jour de semaine" gagne le même sélecteur de période pour zoomer sur l'évolution récente vs l'historique total.
- La course de points "Depuis toujours" passe automatiquement en buckets mensuels au-delà d'un an d'historique pour rester lisible.

### Tap sur un point de graphique
- Toucher un point sur les courses de points ouvre directement l'historique filtré sur la journée correspondante. Pratique pour enquêter sur un pic ou un creux.

## v0.22.0 — 2026-04-27

### Notifications affinées pour les complétions
- Trois réglages indépendants remplacent l'ancien "Tâches complétées" : "Quand quelqu'un fait une tâche seul", "Quand on fait une tâche ensemble (avec moi)", et "Quand une tâche est faite à plusieurs sans moi" (visible uniquement à 3+ membres dans le foyer).
- Les notifs collectives sont personnalisées : "Alex a fait la vaisselle avec toi", "Alex, Sam & toi avez fait …", ou "Alex & Sam ont fait …" selon que tu participes ou non.

### Historique des tâches repensé pour les collectifs
- Un événement "fait ensemble" apparaît comme une seule carte avec tous les participants ("A, B & C") au lieu d'une ligne par personne.
- Icône groupe distincte des complétions solo, et chaque nom est cliquable vers le profil du participant.
- Long-press sur un collectif supprime tout l'événement d'un coup.

### Onglet Statistiques repensé
- Streaks remontées en premier dans Résumé.
- Nouvelle carte "Statistiques" avec cinq graphiques que l'on swipe : retard du foyer dans le temps, course de points cumulés sur 7 jours, 30 jours, depuis toujours, et fréquence des tâches faites par jour de semaine.
- Le graphique "Retard" (en 1ère position) montre l'évolution du retard du foyer depuis le début, sur les tâches en commun uniquement (les tâches assignées à une seule personne sont exclues). Toggle Minutes / Tâches pour basculer l'unité. Le delta vs début s'affiche en vert quand le retard a baissé.
- Classement (Semaine / Mois / Total) fusionné dans Résumé — l'onglet "Classement" disparaît.

## v0.21.0 — 2026-04-26

### Récurrence : choisir l'unité (jours, semaines, mois)
- Sur le formulaire d'édition d'une tâche, l'unité de saisie de la fréquence se bascule entre Jours, Semaines et Mois via trois petits boutons. Pratique pour créer une tâche mensuelle sans avoir à calculer "30 jours".
- Le mini-dialog de vote sur la cadence et la popup de notation (post-complétion) ont aussi ce switch d'unité : voter "tous les 3 mois" ne demande plus de calcul mental.
- Quand on change d'unité, la valeur s'adapte intelligemment (92 j devient "3 mois", soit 90 j) — la conversion est explicite et le breakdown est affiché en clair.

### Affichage humain des durées
- Les durées longues sont affichées dans la forme la plus lisible : "3 mois 2 j" plutôt que "92 jours", "1 sem. 3 j" plutôt que "10 jours".
- Visible partout où la cadence apparaît : écran détail d'une tâche, formulaire d'édition, popup de vote, popup de notation, votes des autres membres.

## v0.20.0 — 2026-04-25

### Auto-archivage des tâches ponctuelles
- Les tâches ponctuelles (sans récurrence) complétées depuis plus de 7 jours sont automatiquement masquées de la liste principale.
- L'historique reste préservé : les complétions restent visibles dans les stats, le leaderboard et le pace.
- Pas de bouton de restauration : si une tâche archivée redevient pertinente, il suffit de la recréer (l'autocomplete de duplication la propose toujours).

### Dupliquer une tâche existante
- Nouveau bouton "Dupliquer" en haut de l'écran détail d'une tâche : crée une nouvelle tâche pré-remplie avec les mêmes paramètres, qu'il suffit d'ajuster (par ex. changer la pièce) avant de valider.
- Dans le formulaire de création d'une nouvelle tâche, des suggestions apparaissent dès 2 caractères tapés dans le titre : sélectionner une suggestion remplit tout le formulaire d'un coup.
- Un chip "Dupliqué de [titre]" indique qu'on est en train de cloner ; tap dessus pour annuler la duplication et repartir d'un formulaire vierge.
- Le clone hérite du consensus de cadence et de la répartition d'effort de la tâche d'origine, mais démarre avec un historique vide (les votes individuels ne sont pas copiés, chacun re-votera au prochain prompt).

## v0.19.0 — 2026-04-24

### Historique : filtres et recherche
- Une barre de recherche permet de filtrer l'historique par titre de tâche. Tape "lavabo" et tu ne verras que ce qui touche à la tâche "Nettoyer le lavabo".
- Un bouton "Filtres" ouvre un panneau pliable avec :
  - Type d'événement (3 chips multi-select : Complétions, Votes, Modifications) pour ne voir qu'une famille à la fois.
  - Acteur (Tous, par membre du foyer) pour filtrer par personne.
  - Période (Cette semaine, Ce mois, 3 derniers mois, Tout) pour scoper la fenêtre temporelle.
- Les filtres sont combinables et un compteur sur le bouton "Filtres" indique combien sont actifs. Un bouton "Effacer" remet tout à zéro.

### Historique : meilleure lisibilité
- Les modifications de cadence affichent maintenant la transition complète : "modifié la récurrence de 'Aspirateur' (recurrence) : tous les 7j → tous les 10j", au lieu d'un simple "(recurrence)" sans contexte.
- Les tâches définitivement supprimées gardent leur titre suivi de "(supprimée)" au lieu d'apparaître comme '?' dans l'historique.
- Les votes de fréquence n'apparaissent plus en double : avant, chaque vote était visible à la fois comme "vote" et comme "modification de récurrence". Maintenant un seul événement par vote.

## v0.18.1 — 2026-04-24

### Ajustement
- Le slider du mini-dialog "Modifier via le vote" propose maintenant une plage adaptée à la cadence de la tâche (de 30% à 300% du consensus actuel) au lieu de toujours afficher 1 à 365 jours. Sur une tâche à 5 jours, tu vois maintenant un slider de 2 à 15 jours, et ton vote actuel est visible au milieu de la barre au lieu d'être collé à gauche. Si tu avais déjà voté très différemment du consensus actuel, la plage est étendue pour inclure ta valeur — pas de troncature silencieuse.

## v0.18.0 — 2026-04-24

### Cadence des tâches : enfin pilotée 100% par les votes
- Tu ne peux plus écraser la cadence d'une tâche (champ "tous les X jours") en l'éditant directement dans la fiche : le champ devient grisé et affiche "Consensus : 7 j (2 votes)". Pour modifier la cadence, un bouton "Modifier via le vote" ouvre un mini-dialog dédié où tu peux poser ton vote, qui rentre dans le calcul du consensus avec ceux des autres membres du foyer.
- Le mini-dialog affiche le consensus actuel et les votes des autres membres ("Alex : 7 j") pour que tu voies pourquoi la cadence est ce qu'elle est avant de voter.
- À la création d'une tâche récurrente "tous les X jours", la valeur que tu saisis devient automatiquement ton vote initial — plus de cadence "fantôme" qui ne correspond à aucun vote.
- Si tu changes le type de récurrence (ex : passer de "tous les 7 jours" à "variable par mois"), tes votes sont conservés en sommeil. Si tu reviens à "tous les X jours" plus tard, ils reprennent vie automatiquement et la cadence est recalculée.

## v0.17.0 — 2026-04-23

### Mode vacances
- Les streaks (jours d'affilée) ne sont plus cassés par les jours d'absence : si vous êtes en vacances et que vos jours sont marqués absents (via le dialog de résolution de présence), votre série continue comme si rien ne s'était passé. Le compteur reste honnête : il affiche toujours le nombre réel de jours d'activité, mais l'absence ne casse plus la série.
- Même règle pour les badges "Régulier" / "Semaine parfaite" / "Deux semaines" / "Mois parfait" : ils restent débloquables après une période de vacances, sans devoir tout recommencer à zéro.
- Seuls les jours **explicitement** marqués absents gèlent la série. Les jours "à confirmer" (incertains) la cassent toujours — pensez à résoudre via le dialog au prochain lancement de l'app pour que vos vacances soient bien comptées.

## v0.16.2 — 2026-04-23

### Correction
- Quand on crée une nouvelle tâche, on n'est plus prompted instantanément de la noter (affinité + effort). L'app attend maintenant que la tâche ait été faite au moins 2 fois et que la dernière complétion remonte à au moins 1 jour avant de proposer la notation. Évite le double travail "je remplis le formulaire de création puis je remplis le formulaire de notation" et laisse le temps d'éprouver la tâche pour pouvoir la noter sincèrement.
- Les tâches ponctuelles (sans récurrence) ne sont plus jamais proposées à la notation : noter une tâche one-shot n'a pas d'utilité long-terme.

## v0.16.1 — 2026-04-23

### Correction
- L'heure du résumé quotidien choisie dans les Réglages est enfin respectée dès qu'on bouge le slider : on déplace, on relâche, la prochaine notif tombe à la nouvelle heure. Plus besoin de tuer et relancer l'app.

## v0.16.0 — 2026-04-22

### Nouveau
- Bouton pour ignorer une suggestion sur l'écran d'accueil : chaque carte a maintenant une petite croix en haut à droite qui masque la tâche et affiche la suivante du même tier. Les dismiss sont purement locaux et se réinitialisent à chaque redémarrage de l'app.
- Quand toutes les suggestions d'un tier (urgentes / affinité / sans cadence) ont été ignorées, un message apparaît et les tâches ignorées redeviennent visibles — rien n'est jamais perdu.

## v0.15.5 — 2026-04-20

### Corrections
- La notification quotidienne sortait à un horaire arbitraire au lieu de l'heure réglée dans les paramètres. Cause : l'ancienne planification créée par une version antérieure persistait même après mise à jour. Corrigé : chaque montée de version replanifie le rappel avec l'heure choisie dans Réglages.

## v0.15.4 — 2026-04-19

### Ajustements
- Le slider de vote de fréquence dans la popup de notation ne dépasse plus 365 jours, même quand la tâche avait une cadence très longue. Évite de proposer des votes que le serveur rejetterait.

## v0.15.3 — 2026-04-19

### Corrections
- Le bandeau "Synchronisation en cours" qui restait affiché en permanence est corrigé. Certains votes de fréquence saisis via le slider (ex : passer à tous les 120 jours) étaient rejetés par le serveur et l'app les rejouait en boucle sans jamais les abandonner, ce qui laissait le bandeau affiché indéfiniment.
- Le cap sur les votes de fréquence passe de 60 à 365 jours. Tu peux maintenant voter pour des tâches qui reviennent annuellement (contrôle technique voiture, décorations de Noël, etc.).

## v0.15.2 — 2026-04-19

### Notifications
- Le rappel quotidien des tâches à faire fonctionne à nouveau : le worker qui envoie la notification n'était pas authentifié et recevait systématiquement une erreur du serveur, donc aucune notification n'était produite.
- Nouveau : l'heure de la notification quotidienne est maintenant respectée. Choisis l'heure dans Réglages -> Notifications (slider 6h-22h), la notif arrivera autour de cette heure le lendemain. Avant, WorkManager choisissait une fenêtre arbitraire dans la journée.
- Le toggle "Notification quotidienne" dans Réglages désactive correctement le rappel (plus besoin de redémarrer l'app).

## v0.15.1 — 2026-04-19

### Corrections
- Correction d'un bug qui faisait apparaître certaines tâches récurrentes comme "en retard" dans la liste alors qu'elles avaient été faites récemment (exemple : une tâche tous les 45 jours faite il y a 10 jours pouvait s'afficher en retard de 11 jours). L'écran de détail affichait la bonne info — c'est uniquement la liste qui était trompée.

## v0.15.0 — 2026-04-19

### Liste des tâches
- Nouveau filtre Solo / Groupe (icône 👤) pour séparer les tâches attribuées à moi seul des tâches partagées.

### Édition d'une tâche
- On peut maintenant changer le type de récurrence en éditant une tâche existante (ex : passer d'une récurrence régulière à "Quand c'est nécessaire", ou l'inverse).
- La tolérance au retard n'est plus affichée pour les tâches "Quand c'est nécessaire" — elle ne s'appliquait pas à ce type et prêtait à confusion.

### Corrections
- Les notifications fonctionnent enfin sur Android 13+ : l'app demande la permission au lancement (elle était déclarée mais jamais demandée, Android les refusait silencieusement).

## v0.14.0 — 2026-04-19

### Récurrence "Sans cadence"
- Nouveau choix dans le picker de récurrence : "Quand c'est nécessaire" — pour les tâches qu'on fait irrégulièrement sans rythme fixe (ex : lessive, liste de courses).
- Les tâches "sans cadence" apparaissent dans une 3e section dédiée sur l'accueil, toujours verte, avec un libellé factuel ("Pas encore faite" ou "Fait il y a X j") au lieu d'un compte à rebours.
- L'app apprend automatiquement à quelle fréquence tu fais cette tâche et la classe en tête quand elle te l'a pas proposée depuis longtemps.
- Le gel des vacances continue de s'appliquer (les jours d'absence ne comptent pas).

### Détail de tâche : respect des attributions
- Sur une tâche attribuée à une seule personne, l'écran détail affiche maintenant un bandeau "Pour un membre uniquement" (avec son prénom) en haut.
- L'autre personne ne voit plus son propre encart "Effort & Affinité" pour cette tâche (il était trompeur : noter une tâche qu'on ne fait pas n'a pas de sens).
- Le bouton "Modifier" de la note d'affinité/effort est masqué pour qui n'est pas attribué.

## v0.13.1 — 2026-04-18

### Fix recherche
- Le curseur du champ de recherche revenait en arrière quand on tapait vite : corrigé. L'affichage du texte saisi est maintenant synchrone, indépendant du temps que prend le filtrage en arrière-plan.

## v0.13.0 — 2026-04-18

### Recherche dans les tâches
- Nouvelle loupe dans l'écran Tâches, en haut à droite de la rangée de filtres. Cliquer la loupe remplace les chips de filtres par un champ de recherche qui prend toute la largeur.
- Par défaut la recherche ignore tous les filtres actifs (y compris "À faire") et cherche dans toutes les tâches.
- Un petit bouton en forme d'entonnoir à côté du champ permet de réappliquer les filtres courants si besoin.
- La recherche matche le titre, la description, le nom de la catégorie et le nom de la pièce. Insensible aux accents (taper "ecole" trouve "École") et à la casse.
- Recherche multi-mots : "cuisine linge" trouve les tâches qui contiennent les deux.

### Refonte de la détection de présence
- La détection automatique en arrière-plan (qui nécessitait que l'app tourne en permanence) est supprimée : Android la brimait trop pour qu'elle soit fiable.
- Nouveau fonctionnement : l'app détecte ta présence au lancement quand elle voit le WiFi maison. Les jours où personne n'a lancé l'app sont marqués "à confirmer".
- Nouveau dialog de résolution : quand des jours sont incertains, l'app propose au lancement une grille-calendrier où chaque jour peut être marqué présent (vert) ou absent (gris). Pour les longues absences (>30 jours), un mode simplifié "j'étais absent depuis X" apparaît à la place.
- Les permissions de localisation en arrière-plan ne sont donc plus nécessaires — tu peux les retirer dans les réglages Android si tu veux.

## v0.12.0 — 2026-04-17

### Bonus et malus de points
- Les tâches en retard rapportent maintenant un bonus de points (+50% par défaut) quand elles dépassent un seuil configurable. Une pastille "🔥 +50%" apparaît sur les suggestions concernées.
- À l'inverse, compléter une tâche très en avance applique un malus progressif (jusqu'à -50% des points). Plus tu la fais tôt dans le cycle, plus le malus est fort.
- La première complétion d'une tâche donne toujours les points pleins (pas de bonus ni de malus).
- Le dialog "Déjà fait" / "Fait ensemble" affiche maintenant une ligne de preview des points avant validation : tu vois combien tu vas gagner et pourquoi (bonus retard, malus avance, première fois).
- La célébration après validation affiche les points gagnés (+X pts) avec la raison du bonus ou malus.
- La fiche détail d'une tâche affiche le bonus ou malus actuel à côté de l'état d'urgence.

### Détection des tâches pénibles
- Quand une tâche est systématiquement en retard (plus de 70% des complétions en zone bonus sur une fenêtre glissante), l'app propose automatiquement à chaque membre du foyer de bumper la pénibilité de +1.
- Le modal de bump affiche la liste des dernières complétions avec leur statut, et propose soit un bump rapide, soit d'aller modifier les notes (affinité/effort).
- Un seul prompt s'affiche à l'ouverture de l'app (pas de spam). Après un prompt, 2h de silence minimum.

### Réglages avancés
- Nouveau panneau "Bonus / Malus de points" dans les Options avancées des Réglages, avec tous les seuils configurables par foyer (14 curseurs avec explications).

## v0.11.1 — 2026-04-15

### Fin du "flash" après une complétion sur l'accueil
- Avant : après avoir validé une tâche sur l'accueil, l'animation de célébration se jouait, puis la même tâche réapparaissait brièvement pendant 1-2 secondes avant d'être remplacée par une autre. C'était lié au fait que le serveur n'avait pas encore vu la validation (synchro en cours) et renvoyait donc la même suggestion.
- Maintenant : l'accueil charge en arrière-plan un tampon de plusieurs suggestions d'avance (5 urgentes + 5 affinités). Quand tu valides une tâche, la suivante apparaît instantanément après l'animation, sans attendre le serveur. La tâche que tu viens de valider est automatiquement masquée côté client jusqu'à ce que la synchro rattrape.
- En cas de burst de complétions (5 d'affilée sans que la synchro suive), une petite carte "Prochaine suggestion en route..." s'affiche le temps que le backend rattrape, plutôt que de te montrer du vide ou une fausse tâche.

## v0.11.0 — 2026-04-15

### "Fait ensemble" — partager les tâches et les points
- Nouveau bouton **Fait ensemble** (icône groupe) sur chaque tâche, présent sur l'accueil, la liste des tâches et la vue détail. Il permet de dire "on ne l'a pas fait tout seul" : les points sont partagés équitablement entre les participants et chacun progresse dans son compteur.
- Dans un foyer à 2 personnes : cliquer ouvre directement un sélecteur de date, les deux membres sont automatiquement inclus (pas besoin de cocher).
- Dans un foyer à 3 personnes ou plus : une popup apparaît avec les membres assignés pré-cochés, un bouton "Ajouter un membre" pour inclure quelqu'un d'autre du foyer, et un sélecteur de date. Minimum 1 participant coché pour valider.
- Si quelqu'un a déjà validé la tâche dans la fenêtre anti-doublon, la validation collective est refusée en bloc (aucune ligne créée) et un message l'explique.

### "Déjà fait" sur la vue détail d'une tâche
- Le bouton **Déjà fait** (icône calendrier) est maintenant aussi présent sur l'écran détail d'une tâche, en plus de l'accueil et de la liste. Jusqu'ici il manquait à cet endroit.
- Dans la popup "Déjà fait", un nouveau bouton "À plusieurs..." permet de basculer vers le flow "Fait ensemble" sans repartir de zéro.

### Popup de complétion unifiée
- Les deux anciennes popups séparées (choisir qui puis choisir quand) sont remplacées par une seule popup claire avec la date et la liste des participants au même endroit. Plus rapide à valider.

### Nettoyage de l'accueil
- La section "En cours" de l'accueil (qui affichait les tâches démarrées mais pas encore validées) est supprimée. Elle était quasi jamais utilisée et parasitait l'écran.

## v0.10.2 — 2026-04-15

### Fin des crashs après mise à jour
- L'app purge maintenant automatiquement son cache local d'écran d'accueil à chaque nouvelle version installée. Ça évite les crashs comme celui de la v0.10.0 : quand le format des données change d'une version à l'autre, l'ancien contenu en cache pouvait faire planter l'app au premier lancement. Maintenant, la première ouverture après une mise à jour repart d'un cache vide et se re-remplit au premier refresh réseau.

## v0.10.1 — 2026-04-15

### Fix crash au démarrage
- Corrige un crash de l'app au démarrage après la mise à jour v0.10.0 (écran qui se ferme immédiatement quand on ouvre l'app). Cause : les anciennes suggestions encore en cache local n'avaient pas le nouveau champ d'état ; l'app tentait de les afficher avant le refresh et plantait. Maintenant le rendu est tolérant à ce cas et affiche neutre le temps que le refresh arrive.

## v0.10.0 — 2026-04-15

### Refonte du modèle de fraîcheur — fini la demi-vie, place à l'échéance
- Avant : chaque tâche avait un score de "fraîcheur" qui décroissait de façon exponentielle (demi-vie). Problème : pas intuitif. Une tâche "litière tous les 2 jours" faite il y a 4 jours n'était toujours qu'en orange, alors que mentalement elle est "en retard de 2 jours".
- Maintenant : chaque tâche a une **échéance** (`due_date = derniere_completion + intervalle`) et l'état est calculé en jours :
  - **Vert** : pas encore due (loin de l'échéance)
  - **Orange** : dû aujourd'hui ou bientôt (fenêtre de `floor(intervalle * 0.10)` jours avant l'échéance — pour 7j elle est de 0 jour, pour 30j de 3 jours, pour 60j de 6 jours)
  - **Rouge** : en retard (après l'échéance)
- Sur les cartes : couleur + texte countdown ("dans 3j", "à faire aujourd'hui", "en retard de 2 j") affichés systématiquement.
- Au passage, le scénario d'origine est corrigé : litière 2j faite le 11/04 -> rouge "en retard de 2 j" le 15/04 (avant : encore orange, ce qui n'avait aucun sens).

### Tolérance repurposée — uniquement pour le sélecteur "la plus urgente"
- La tolérance (STRICT / NORMAL / SOUPLE) ne change plus rien à l'état affiché d'une tâche ni au moment où elle passe en retard.
- Son seul rôle : prioriser le sélecteur de la "tâche la plus urgente" sur la home page :
  - STRICT en retard passe avant NORMAL en retard, qui passe avant SOUPLE en retard. Au sein d'un tier, on prend celle avec le plus gros ratio (jours de retard / intervalle).
  - Si aucune tâche n'est en retard, fallback sur les Oranges (toutes tolérances confondues, plus proche de l'échéance d'abord).
- Une bulle ℹ à côté du sélecteur de tolérance (dans le détail/édition/création d'une tâche) explique tout ça.

### Nettoyage
- Suppression du knob "Seuil de suggestion" dans les réglages avancés (le modèle est maintenant déterministe, plus besoin de le configurer).

## v0.9.0 — 2026-04-14

### Catalogue de templates
- Nouvel écran "Catalogue" accessible via un petit bouton magique à côté du "+" dans l'écran des tâches. Il propose une bibliothèque de tâches, de pièces et de catégories prêtes à l'emploi (vaisselle, aspirateur, nettoyer la sdb, cuisine, salon, ménage, linge, ...) en trois onglets.
- Sélection multiple (tu coches ce que tu veux) et bouton "Importer N" en bas. Quand les tâches que tu importes référencent des pièces ou des catégories, une popup de rattachement propose soit de les créer, soit de les mapper sur des éléments existants de ton foyer (avec détection automatique des noms similaires — "Cuisine" vs "cuisine" vs "La cuisine" sont détectés comme la même chose).
- Pratique pour peupler un foyer vide en quelques clics, ou pour t'inspirer et ajouter des tâches auxquelles tu n'aurais pas pensé.

## v0.8.1 — 2026-04-14

### Proposition d'objectifs en mode foyer
- Le popup de simulation (tap sur les cartes "Moi" ou "Foyer") contient maintenant un slider par membre du foyer : tu peux simuler un plan complet en ajustant les rythmes de chacun et voir en direct en combien de jours le retard serait rattrapé. Le bouton "Juste pour moi" reste dispo pour sauver uniquement ton objectif.
- Nouveau bouton "Proposer au foyer" (actif dès que tu bouges au moins un slider des autres membres) : il envoie ta proposition en live au reste du foyer. Les autres reçoivent un modal bloquant avec ta proposition, ils ont 3 minutes pour accepter ou refuser. Pendant ce temps, tu vois un écran d'attente qui liste en direct qui a validé, avec un bouton "Annuler la proposition" si tu changes d'avis.
- Quand tout le monde accepte, les objectifs sont sauvés pour chacun et une popup de célébration apparaît sur les apps de tous les membres ("Vous êtes sur la bonne voie vers un foyer propre !") avec un easter egg à découvrir en cliquant sur le lien en bas.
- Si quelqu'un refuse ou si le délai expire, la proposition est annulée, aucun objectif n'est sauvé, et tout le monde est notifié.

## v0.8.0 — 2026-04-14

### Prévision de rattrapage du retard
- Deux nouvelles cartes en haut de l'accueil : "Moi" (minutes faites aujourd'hui, barre de progression vers l'objectif du jour, nombre de jours de rattrapage estimé) et "Foyer" (retard courant du foyer, nombre de jours pour être à zéro, rythme de maintien une fois résorbé).
- Popup de simulation : tap sur une carte ouvre un slider pour fixer ton rythme cible en minutes ou en tâches par jour, avec feedback live sur le nombre de jours de rattrapage. Le bouton "Définir comme objectif" sauve ton engagement personnel.
- L'unité d'affichage (minutes ou tâches) est choisissable dans le popup et sauvée par foyer.

### Accueil plus compact
- Suppression du liseré rouge et de la barre de progression sur les suggestions de tâches : la pastille rouge suffisait déjà à signaler l'urgence.
- Suppression du titre "Suggestions" (évident vu le contexte).
- Déplacement du message "Tu as fait plus que ta part..." depuis l'accueil vers l'onglet "Résumé" des Stats — pour que les cartes de tâches et les cartes de prévision tiennent en un seul écran.

## v0.7.4 — 2026-04-14

### Mise en pause des tâches "à la maison" en cas d'absence
- Quand personne n'est détecté au WiFi maison pendant plus de 24h, les tâches des catégories marquées "à la maison" arrêtent de vieillir jusqu'au retour. Au-delà des 24h de tolérance, le compteur est figé — plus de retard accumulé pendant les vacances.
- Popup "Bon retour à la maison !" au retour, avec le nombre de jours d'absence et un rappel que les tâches concernées ont été mises en pause.
- Détection automatique de la présence WiFi en arrière-plan toutes les 6h et au lancement de l'app.

### Réglage du foyer
- Bouton "modifier" sur chaque foyer (admins uniquement) dans Réglages > Mes foyers : permet de renommer le foyer et d'activer/désactiver la mise en pause des tâches "à la maison" en cas d'absence.

### Tooltip adaptatif "à la maison"
- Le tooltip d'info à côté de "À la maison uniquement" (édition de catégorie) explique maintenant l'état réel du freeze : si la mise en pause est désactivée, si tu n'as pas configuré ton WiFi maison, si on attend ta première connexion, ou si d'autres membres du foyer bloquent encore la détection.

## v0.7.3 — 2026-04-14

### Multi-foyer : isolation users + wifi maison
- Les listes d'utilisateurs dans les pickers (assignation de tâche, "qui a fait ?", fair-share, stats, leaderboard) n'affichent plus que les membres du foyer actif. Avant, tous les users de la base apparaissaient même dans un foyer où ils n'étaient pas.
- Les SSIDs WiFi maison sont maintenant stockés par foyer : dans ta maison tu configures tes SSIDs, dans la colloc d'un ami tu en configures d'autres, sans mélanger. Quand tu basculais de foyer, les SSIDs de l'autre foyer apparaissaient — corrigé.

## v0.7.2 — 2026-04-14

### Multi-foyer : stats isolées par foyer
- Les statistiques (points, tâches complétées, leaderboard, streaks, badges, historique) ne mélangent plus les données de tous tes foyers : chaque écran stats affiche uniquement les chiffres du foyer actif.
- Les badges gagnés sont spécifiques à chaque foyer (un même badge peut être gagné dans plusieurs foyers séparément).

## v0.7.0 — 2026-04-14

### Multi-foyer (refonte invisible mais fondamentale)
- L'app est maintenant prête à gérer plusieurs foyers : tu peux créer un nouveau foyer (par exemple pour une colloc d'amis) et basculer entre tes foyers sans perdre les données de l'autre.
- Nouvel écran **Réglages → Mes foyers** : tu vois la liste des foyers auxquels tu appartiens, tu peux en créer un nouveau via le bouton + et basculer en cliquant dessus. Au basculement, l'app vide son cache local et recharge les données du nouveau foyer (quelques secondes).
- Pour l'instant les membres existants partagent un seul foyer "Maison" — toutes les tâches, catégories et pièces existantes y ont été migrées automatiquement.
- L'invitation d'amis par QR code pour créer un foyer commun sera ajoutée dans une prochaine version (ça nécessite une infra de déploiement publique).

### Tâches ponctuelles visibles
- Une tâche créée sans récurrence (ponctuelle) apparaît maintenant dans la liste "à faire" et dans les suggestions de l'accueil tant qu'elle n'a pas été faite. Avant, elle était invisible tant qu'on n'avait pas désactivé le filtre "à faire".

### Pièce correctement sauvegardée
- Quand on crée ou modifie une tâche et qu'on lui assigne une pièce, la pièce est maintenant bien enregistrée. Avant, la pièce choisie pouvait être perdue après sauvegarde.

### Popup de notation moins intrusive
- La popup qui demande de noter l'affinité/effort d'une tâche ne réapparaît plus à chaque fois qu'on revient sur l'accueil. Elle s'affiche à l'ouverture de l'app et au retour depuis le multitâche, puis plus jusqu'à la prochaine session.

## v0.6.2 — 2026-04-13

### Félicitations à chaque tâche validée
- Quand tu valides une tâche (peu importe où : accueil, liste ou fiche détail, et que ce soit "fait" ou "déjà fait"), la carte affiche maintenant un emoji de félicitations ("Bravo !", "Bien joué !", etc.) et des confetti tombent sur l'écran pendant 2 secondes avant que la tâche disparaisse / la suivante apparaisse. Plus de validation invisible.

## v0.6.1 — 2026-04-13

### Pièces visibles partout
- Sur la page d'accueil, les cartes "Suggestions" et "En cours" affichent maintenant la pièce (avec son emoji si tu en as défini un) et la catégorie en pastille colorée sous le titre.
- Les cartes de suggestions très en retard ont un liseré rouge autour pour attirer l'œil.
- La fiche détail d'une tâche affiche aussi la pièce (read-only — pour la modifier passe par le crayon en haut à droite).
- La popup de notation affiche la pièce sous le titre de la tâche pour rappel visuel.

## v0.6.0 — 2026-04-13

### Pièces : nouvelle gestion
- Les pièces de la maison sont maintenant des entités de plein droit (Cuisine, SDB, Chambre, Salon, etc.) au lieu d'être cachées dans les titres des tâches.
- Nouvel écran "Gérer les pièces" dans Réglages : ajoute, modifie, supprime tes pièces. Chacune a un nom, une couleur et un emoji optionnel.
- Quand tu crées ou modifies une tâche, un sélecteur "Pièce" apparaît à côté de "Catégorie".
- Les filtres par pièce dans la liste affichent maintenant l'emoji à côté du nom.
- Dans la liste des tâches, le nom de la pièce (avec son emoji si défini) apparaît sous le titre.
- Migration automatique : tes 29 tâches existantes ont été liées aux pièces extraites de leurs titres. Les titres ont été nettoyés du suffixe " — Pièce". Les 13 tâches sans suffixe restent sans pièce, tu peux les éditer pour en attribuer une.

## v0.5.8 — 2026-04-13

### Liste des tâches
- Plus d'air superflu en haut de l'écran : la 1ère rangée de filtres colle vraiment au status bar Android (correction du double padding).

## v0.5.7 — 2026-04-13

### Liste des tâches
- Encore plus de fluidité au scroll (optimisations internes : Compose peut maintenant détecter les cartes inchangées et ne pas les redessiner inutilement quand la liste se met à jour).

## v0.5.6 — 2026-04-13

### Liste des tâches
- Marge en haut de la liste réduite : la 1ère rangée de filtres colle directement sous la barre d'état Android (avant : trop d'air en haut).
- Défilement nettement plus fluide grâce à plusieurs optimisations internes (pipeline de filtrage déporté hors du thread principal, callbacks stabilisés pour éviter les recompositions inutiles).

## v0.5.5 — 2026-04-13

### Liste des tâches : refonte visuelle
- Le bandeau violet "Torchons & Serviettes" en haut a été retiré — il prenait de la place sans rien proposer de cliquable.
- Nouvelle barre de synchronisation fine et discrète tout en haut, qui apparaît pendant les chargements (au lieu d'un gros spinner plein écran qui masquait les tâches).
- Espacements entre les rangées de filtres revus : plus d'air en haut, espacement uniforme entre les rangées.
- Cartes de tâche simplifiées : on ne garde plus que le nom, la pièce et un indicateur de retard sous forme de bordure colorée à gauche (vert/jaune/orange/rouge). Plus de badge catégorie, d'étoiles, de barre de progression ni de texte "Rouge X%". Les cartes sont plus compactes, on en voit plus à l'écran et le scroll est plus fluide.

## v0.5.4 — 2026-04-13

### Liste des tâches
- Au démarrage de l'app, le filtre "🔥 À faire" est maintenant activé par défaut : tu vois directement les tâches urgentes, sans scroller dans toute la liste. Tu peux désactiver le filtre en tapant sur la chip rouge si tu veux voir toutes les tâches.

## v0.5.3 — 2026-04-13

### Liste des tâches
- Le swipe pour valider une tâche est supprimé — il déclenchait parfois des validations accidentelles.
- Les deux boutons d'action sur chaque carte (calendrier "déjà fait" et check "valider") sont plus gros, avec un fond coloré (vert pour valider, gris pour le calendrier) et clairement identifiables comme boutons cliquables.
- Le défilement de la liste est nettement plus fluide, surtout quand il y a beaucoup de tâches.

## v0.5.2 — 2026-04-13

### Notation unifiée
- Le bouton "Modifier" sur la fiche d'une tâche ouvre maintenant la même popup de notation que celle de l'accueil — avec affinité, effort ET fréquence dans un seul écran.
- Les valeurs précédentes sont préchargées correctement (avant, le bouton "Modifier" affichait toujours 5/10 et 15min par défaut).
- Affichage de la date de dernière notation au-dessus des réglages : "Dernière notation il y a 3 jours" / "Jamais noté".

### Fréquence
- La fréquence d'une tâche existante ne se modifie plus depuis l'écran d'édition de la tâche : il faut passer par la popup de notation (chacun vote sa fréquence et la moyenne est utilisée).
- Lors de la création d'une tâche, le choix de la fréquence reste disponible.
- Le slider d'effort est cap à 1h max (avant : 1h30 sur la fiche détail, 1h sur l'accueil — incohérent).

## v0.5.1 — 2026-04-13

### Corrections
- L'app ne crashe plus au démarrage après mise à jour vers v0.5.0 (incompatibilité de schéma de base locale sur la table d'historique).

## v0.5.0 — 2026-04-13

### Historique des modifications
- Nouvelle section « Historique des modifications » dans la fiche détail d'une tâche, sous l'historique des complétions : affiche qui a voté quoi et comment le consensus a évolué (affinité, effort, fréquence).
- Chaque vote s'affiche avec son impact sur la moyenne : par exemple « Alex a voté 7j fréquence → consensus 6j » ou « Sam a voté 3/10 affinité → consensus 4/10 ».
- Les modifications directes de la tâche (changement de titre, récurrence, catégorie, tolérance, affectations) apparaissent aussi dans cette timeline.

### Fréquence votable
- Quand la popup de notation propose d'ajuster la fréquence d'une tâche, c'est maintenant un vote individuel : chaque participant a sa propre valeur et le consensus affiché est la moyenne des votes.
- Avant, le dernier qui ajustait la fréquence écrasait la valeur pour tout le monde — ce n'est plus le cas.

## v0.4.1 — 2026-04-12

### Corrections
- Les réglages (WiFi maison, notifications, seuil de suggestions, etc.) se sauvegardent maintenant correctement — avant, les modifications pouvaient être perdues à la réouverture de l'app.
- La popup de notation d'affinité n'apparaît plus en double à l'ouverture de l'app.
- Le bouton "Une autre" dans la popup de notation fonctionne : il passe bien à la tâche suivante au lieu de simplement fermer la popup.
- Le bouton "?" affiche maintenant une tâche différente à chaque utilisation (avant, il montrait toujours la même).
- La popup de notation affiche le nombre de tâches restantes à noter.
- Quand toutes les tâches sont déjà notées, un message le confirme au lieu de ne rien faire.

## v0.4.0 — 2026-04-12

### Toute l'app fonctionne hors ligne
- Les écrans Tâches et Détail d'une tâche affichent maintenant les dernières données connues même quand le backend ne répond pas (avant, ils restaient vides).
- Le bandeau "Hors ligne" affiche la date et l'heure des dernières données ("données du 12/04 14:32").
- Quand la connexion revient, les données se rafraîchissent automatiquement sans avoir à fermer et rouvrir l'écran.

### Création et validation de tâches sans réseau
- On peut maintenant créer, modifier, supprimer et valider des tâches même sans connexion au backend.
- Les opérations s'appliquent instantanément dans l'app et se synchronisent automatiquement quand la connexion revient.
- Un badge "X opération(s) en attente" s'affiche dans le bandeau quand des opérations n'ont pas encore été envoyées au serveur.
- Quand la synchronisation se fait, un indicateur "Synchronisation en cours..." s'affiche brièvement.

## v0.3.0 — 2026-04-11

### Mode hors ligne sur l'accueil
- L'accueil affiche maintenant les dernières données connues même quand le backend ne répond pas, au lieu de rester bloqué ou de mentir avec "Tout est frais !" alors que rien n'a pu charger.
- Quand le backend est injoignable, un bandeau orange "Hors ligne" apparaît en haut de l'app avec un bouton "Réessayer".
- Quand le backend revient (par exemple après redémarrage du serveur), l'app le détecte automatiquement et le bandeau disparaît tout seul.
- Si l'accueil n'a vraiment aucune donnée à afficher faute de backend, il affiche un message clair "Données indisponibles" au lieu du "Tout est frais !" trompeur.

### Démarrage plus rapide
- L'app n'attend plus 30 secondes en blanc au démarrage si le backend rame ou est injoignable. Au-delà de quelques secondes elle passe directement en mode hors ligne.

## v0.2.2 — 2026-04-11

### Tolérance au retard sur les tâches récurrentes
- Tu peux maintenant choisir, pour chaque tâche récurrente, à quel point l'app est sévère sur le retard : Strict, Normal ou Souple. Le choix est dans le formulaire d'édition de la tâche, juste après la récurrence.
- Strict : la tâche devient orange/rouge avant l'échéance.
- Normal (défaut) : la tâche est jaune à l'échéance — comportement actuel.
- Souple : la tâche reste verte même un peu après l'échéance.

### Félicitations fair-share plus intelligentes
- Le message "Tu as fait plus que ta part" sur l'accueil arrive maintenant uniquement quand tu as fait *vraiment* plus que ta part, et non au moindre écart. Par défaut il faut avoir fait 30% de plus que ton ratio cible.
- Ce seuil de tolérance est réglable dans Réglages avancés ("Tolérance fair-share", de 0% à 100%).
- Le message ne s'affiche plus quand l'app n'a pas assez de données (moins de 10 tâches complétées au total sur les 30 derniers jours).
- Le texte du message dit maintenant "ces 30 derniers jours" au lieu de "ce mois-ci", plus précis par rapport au calcul réel.

## v0.2.1 — 2026-04-11

### Maintenance
- Améliorations internes du stockage des configurations sensibles. Transparent pour l'utilisateur.

## v0.2.0 — 2026-04-11

### Filtres puissants sur la liste des tâches
- Cinq filtres rapides en haut de la liste : "À faire" (rouge), Affinité (aimées / détestées), Effort (long / court), Récurrence (récurrentes / ponctuelles), Maison (à la maison / hors maison).
- Chaque filtre est tri-state : un clic active "haut", deux clics active "bas", trois clics désactive.
- Filtre par pièce, extrait automatiquement du titre des tâches (ex : "— Cuisine", "— Salon").
- Possibilité de sélectionner plusieurs catégories en même temps au lieu d'une seule.
- Les catégories vides ne sont plus affichées dans la barre de filtres.
- Un petit récapitulatif des filtres actifs s'affiche au-dessus de la liste.

### Popup de notation enrichie
- La popup d'affinité et d'effort se pré-remplit maintenant avec ta dernière note (ou la moyenne du foyer si tu n'as jamais noté).
- Pour une tâche ponctuelle, tu peux la transformer en tâche récurrente directement depuis la popup, et choisir la fréquence.
- Pour une tâche déjà récurrente, tu peux ajuster sa fréquence (de 50 % à 200 % de la valeur actuelle).

### "Qui a fait cette tâche ?"
- Quand tu valides une tâche en mode "déjà fait" (date passée), un nouveau sélecteur te demande qui (parmi les membres) l'a faite, en plus de la date.
- Le sélecteur est skippé automatiquement si une seule personne est assignée à la tâche.

### Profil utilisateur cliquable partout
- Tu peux maintenant cliquer sur le nom de n'importe quel membre n'importe où dans l'app : feed historique, classement, résumé stats, streaks, complétions de tâches.
- Un modal s'ouvre avec son historique personnel, son nombre de complétions et son total de points.
- Les tâches dans cet historique sont elles-mêmes cliquables pour aller à leur détail.

### Historique nettoyé
- Plus de doublons sur les affinités et efforts : les saves sans changement réel ne polluent plus le flux.
- Quand une tâche est modifiée, le feed indique maintenant précisément quels champs ont changé (titre, catégorie, récurrence, etc.) au lieu d'un "modifié" opaque.
- Cliquer sur une carte du feed ouvre directement la tâche correspondante.

### Anti-double-validation
- Impossible de valider la même tâche deux fois dans la même heure.
- Pour les tâches hebdomadaires ou plus longues, blocage sur la journée entière.
- Évite les doubles taps accidentels sur le bouton "Faite".

### Nouveautés & Roadmap dans l'app
- Nouvelle entrée "Nouveautés" dans les Réglages.
- Affiche la version actuelle, les notes de version et la roadmap des features prévues.

### Cosmétique
- Nouvelle icône d'app : un chapeau de magicien posé sur une serpillère, sur fond bleu nuit étoilé.
- La version de l'app affichée en bas de l'accueil et dans les Réglages est maintenant lue dynamiquement (fini le "v1.0.0" en dur).

## v0.1.0 — 2026-04-08

### Première version
- CRUD tâches avec récurrence (intervalle, jours de semaine, mensuel, custom).
- Catégories, attribution, complétions avec points.
- Sync offline bidirectionnelle.
- Gamification : freshness score, streaks, leaderboard, badges.
- Fair-share : ratio configurable par couple.
- Notifications push Firebase + rappels locaux.
- Import initial des 39 tâches depuis la config Home Assistant.
