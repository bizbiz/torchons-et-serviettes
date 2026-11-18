# « À faire aujourd'hui » : confier une tâche à un autre membre

Décidé le 18/11/2026 (grill). Statut : implémenté le 18/11 (non commité, non déployé) ; restent
l'écran d'onboarding, le site et le test sur appareil.

## Pourquoi

Le discours « couple équilibré, partage équitable » touche peu de foyers. Le cas courant est
celui d'un membre qui porte presque tout et doit dire à l'autre quoi faire, sinon rien n'est
fait. La fonctionnalité lui permet de **confier** une tâche à un ou plusieurs autres membres,
pour aujourd'hui. Celui qui confie sait que la tâche est à faire : s'il voulait la faire, il la
ferait. On confie donc toujours à **quelqu'un d'autre**.

Le nom affiché est « À faire aujourd'hui », pas « urgent » : ce mot désigne déjà le retard
calculé (liseré rouge/orange/vert, carte « La plus urgente » de l'accueil).

## Cible et discours (décisions)

- Le site garde son titre ; « À faire aujourd'hui » s'ajoute à la liste des fonctionnalités.
- Pas de question de profil à l'onboarding. Un écran d'onboarding présente la fonctionnalité à
  tout le monde.
- Le partage équitable ne change pas : une tâche confiée compte pour qui la fait. Aucune trace de
  qui l'a confiée en V1.

## Comportement

### Carte de la liste des tâches

Trois boutons, de gauche à droite :

1. **Date + Qui** (icône calendrier) : un seul modal à cases à cocher, qui remplace les modes
   « déjà fait » (radio) et « fait ensemble ». À l'ouverture, **moi seul** est coché (en prod,
   162 validations antidatées sur 179 sont faites par une seule personne). Le bouton de
   validation suit la sélection : « Toi aujourd'hui », « Toi hier », « Lo & Toi mardi ».
   Une tâche solo ne propose que son assigné, comme aujourd'hui.
2. **« ! »** (remplace « ensemble ») : confier, ou annuler.
3. **Fait par moi** (coche verte), inchangé.

Le « ! » existe aussi sur l'écran de détail. Pas sur les cartes de l'accueil.

### À qui on confie

Destinataires possibles = membres qui **voient** la tâche (`visible_tasks`), moins moi.

- Un seul : le « ! » confie directement, sans modal.
- Plusieurs : modal à cases à cocher, tous cochés par défaut, sans moi.
- Aucun (tâche solo à moi) : pas de « ! ».

Une tâche confiée affiche « ! » allumé (rouge) et « confiée à X » sous le titre. Seul l'auteur
peut annuler en retouchant le « ! ». Chez un autre membre, le « ! » allumé affiche
« confiée par A à C » et ne change rien.

### Fin d'une tâche confiée

- Faite par n'importe qui : elle sort de « À faire aujourd'hui ».
- Annulée par son auteur : elle sort.
- À minuit, rien : elle reste, avec « depuis hier » / « depuis mardi ».
- Le destinataire ne peut pas la rendre ; il peut seulement la passer avec la croix.

### Accueil

- **Destinataire** : une pile rouge « À faire aujourd'hui » en tête de l'accueil, au-dessus de
  « La plus urgente ». Une carte à la fois, « 1 sur 3 » dans l'en-tête, mêmes boutons de
  validation que les autres cartes. La croix passe à la suivante ; une fois la pile épuisée,
  tout réapparaît (même mécanisme que le réaffichage des suggestions ignorées). Une tâche de
  cette pile n'apparaît dans aucune autre pile.
- **Auteur** : la tâche sort de ses suggestions ; pas de pile rouge chez lui.

### Notifications

- **Groupée** : 5 minutes après la dernière tâche confiée par un auteur à un destinataire,
  « A a mis 3 tâches à faire aujourd'hui : X, Y et Z ». Une tâche annulée dans ces 5 minutes
  n'est pas annoncée. Elle ne se coupe pas individuellement.
- **Rappels** : à 12 h, 16 h et 20 h (Europe/Paris), s'il reste au moins une tâche confiée au
  destinataire et que sa dernière notification de ce type date de plus d'1 h. Un message qui
  liste les tâches restantes. Coupable dans les réglages perso.
- Toucher une notification ouvre l'accueil.

### Réglages

- Foyer : interrupteur « À faire aujourd'hui », **allumé par défaut**. Éteint : plus de « ! »,
  plus de pile, plus de notification.
- Perso : interrupteur des rappels.

## Choix techniques

- L'état vit **en base** : auteur, destinataires, date, « annoncée le », « soldée le ». La
  notification groupée est cherchée en base par la tâche planifiée qui tourne toutes les 30 s,
  pas dans une file en mémoire comme les validations (`SYNC_QUIET_WINDOW`) : un redémarrage ne
  perd rien.
- Offline-first (`docs/cache-conventions.md`) : confier et annuler passent par `/sync`,
  écriture locale optimiste, pile recalculée sans réseau, rafraîchissement en REPLACE.
- Ordre de déploiement : serveur avant l'APK.

## Options écartées pendant le grill

- Nom « Urgent » ; nom « Pour toi ».
- Expiration à minuit, avec ou sans prévenir.
- « ! » sur les cartes de l'accueil.
- Encart de suivi ou pile rouge chez l'auteur.
- Rappel 4 h après la notification groupée ; pas de rappel en V1.
- Interrupteur foyer seulement ; interrupteur perso qui coupe tout.
- Pile affichée en entier ; pile sans compteur.
- Tous cochés par défaut dans « Date + Qui ».
- Mention « confié par » dans l'historique ; statistique de tâches confiées.
- Bouton « Pas aujourd'hui » pour le destinataire ; annulation par n'importe qui.
- Discours du site centré sur la charge mentale ; deux portes ; question de profil.
