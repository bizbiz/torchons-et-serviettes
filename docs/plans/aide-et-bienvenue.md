# Aide « ? » sur chaque écran, et écran de bienvenue

Décidé le 18/11/2026 (grill). Remplace l'écran d'onboarding prévu pour « À faire aujourd'hui »
(`a-faire-aujourdhui.md`). Statut : textes FR validés par Quentin le 19/11, traduits en anglais
le même jour (`aide-textes-brouillon.md`) ; les cinq blocs qui s'affichaient module éteint sont
conditionnés ; reste le code de l'aide.

## Pourquoi

Les écrans se chargent d'explications à mesure que l'app grandit. Une aide toujours disponible,
qui explique l'écran affiché, permet de les alléger et de présenter chaque fonctionnalité au même
endroit, au lieu d'un écran d'onboarding par fonctionnalité.

## Le bouton « ? »

- En haut à droite de **9 écrans** : Accueil, Tâches, Stats, Réglages, Réglages du foyer, fiche
  d'une tâche, création/modification d'une tâche, Contextes, Mes appareils. L'assistant de
  création du foyer et Nouveautés n'en ont pas.
- Il ouvre une **feuille du bas** (`ModalBottomSheet` de Material 3) : titre de l'écran, puis des
  sections (titre + texte), défilante si elle est longue.
- **Rien d'automatique** : la feuille ne s'ouvre jamais seule, aucune mémoire « déjà vu » par
  écran. Seul l'écran de bienvenue annonce le « ? ».
- **Chaque section est rattachée à un module** quand elle en décrit un, et disparaît s'il est
  éteint dans le foyer : l'aide décrit ce qu'on voit, rien de plus.
- **Accueil** : le « ? » actuel lançait « Noter une tâche » (`HomeGreeting.kt`). Il devient l'aide ;
  « Noter une tâche » passe sur une icône de bulle de message, à côté.

## Ce qui reste dans les écrans, ce qui en sort

- **Restent** : les 22 infobulles ⓘ accrochées à un champ (`InfoTooltip`), et les 5 fenêtres
  d'explication, toutes accrochées à un élément précis (effort et fréquence dans les votes,
  affinité dans la fiche, points solo dans la validation et la suggestion, « pourquoi pas de
  points » dans les Réglages, qui porte aussi des boutons de réactivation).
- **Sortent** : les paragraphes d'explication affichés en permanence dans les écrans. Leur liste,
  écran par écran, fait partie du brouillon à relire.

## Écran de bienvenue

- **Page entière**, un seul bouton « C'est parti ».
- Affichée à la **première arrivée sur l'accueil**, une fois le foyer créé ou rejoint ; vaut
  aussi pour qui rejoint un foyer existant.
- Les téléphones **déjà installés la voient une fois** à la mise à jour. Mémoire locale au
  téléphone : elle revient après une réinstallation.
- Texte validé (FR ; l'anglais se traduit au codage) :

> **Bonjour, je suis Quentin 👋**
>
> C'est moi qui ai fait cette appli. J'ai essayé de la rendre la plus simple possible tout en
> permettant plein de choses, et ce n'est vraiment pas facile !
>
> Certaines fonctionnalités sont éteintes le temps que tu prennes l'appli en main : elles te
> seront proposées petit à petit. Pour les allumer plus tôt : Réglages, puis la carte de ton
> foyer.
>
> Si quelque chose n'est pas clair, touche le « ? » en haut de l'écran : il explique la page que
> tu as sous les yeux.
>
> Un bug, une idée ? Écris-moi : Réglages, tout en bas.

Vérifié le 18/11 : 7 modules sur 9 sont éteints dans un foyer neuf (`default_new: false`) ; ils
s'allument sur proposition acceptée, jamais seuls ; tout membre peut les basculer (pas de
contrôle de rôle dans `api/modules.py::toggle_module`). L'entrée des Réglages est la carte
« 🏠 *nom du foyer* », d'où « la carte de ton foyer ».

## Bas des Réglages

La carte « Soutenir / Discuter avec le dev » (`SupportDevCard`) devient :

> **Un mot de Quentin**
>
> C'est moi qui fais cette appli, sur mon temps libre. Un bug, une idée, un truc pas clair ?
> Écris-moi, je lis tout.
>
> Et si elle te rend service, tu peux m'offrir un café ☕

Boutons, dans cet ordre : « M'écrire », « M'offrir un café ».

## Ordre de travail

1. Brouillon FR des 9 aides et de la liste des paragraphes à retirer, relu par Quentin :
   `aide-textes-brouillon.md`.
2. Code : composant de feuille d'aide, « ? » sur les 9 écrans, bulle « Noter une tâche », écran de
   bienvenue, carte du bas des Réglages, retrait de l'interrupteur « À faire aujourd'hui ».
3. Traduction EN (`values-en/strings.xml`), passage `android-design` (clair, sombre, grande
   police) sur l'émulateur.

## Options écartées pendant le grill

- Ouverture automatique de l'aide à la première visite d'un écran, ou « ? » mis en valeur tant
  qu'il n'est pas lu : écartées au profit de « rien d'automatique ».
- Retirer aussi les ⓘ des champs : écarté, l'explication reste collée au réglage qu'elle décrit.
- Aide sous forme de page entière ou de fenêtre centrale : écartées, la feuille du bas laisse voir
  l'écran expliqué.
- Écran de bienvenue avant le choix Créer / Rejoindre : écarté, il parlerait du « ? » et des
  Réglages avant qu'on les ait vus.
- Sections des modules éteints montrées avec une mention : écarté, l'aide ne décrit que ce qu'on
  voit.
- Couper aussi l'interrupteur perso des rappels : écarté.
