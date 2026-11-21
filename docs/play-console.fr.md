# Play Console : les réglages qu'on a choisi de ne pas activer

English version: [play-console.md](play-console.md).

Chaque réglage de la Play Console qu'on a lu et décidé de laisser tel quel s'écrit ici, avec sa
raison, pour ne pas refaire la discussion à chaque publication. Un réglage qu'on change s'écrit
aussi, avec la date.

## Tester et publier → Paramètres avancés

Lus le 2026-11-21 (v0.47.0 en Tests internes), décidés avec Quentin le même jour.

| réglage | état | décision |
|---|---|---|
| Installations intégrées | désactivé | on n'y touche pas |
| Optimisation de l'appli | désactivée | on n'y touche pas avant le lancement officiel |

**Installations intégrées.** Ce réglage permet d'installer d'autres applis depuis l'intérieur de
la nôtre : il suppose d'intégrer l'API Google Play des installations intégrées, de remplir des
critères d'éligibilité et d'accepter des conditions d'utilisation. T&S ne fait installer aucune
autre appli : rien à gagner.

**Optimisation de l'appli.** Activée, Play peut modifier les APK qu'il tire de nos app bundles
« pour améliorer ses performances, sa taille et sa sécurité » ; les conditions du service de
signature d'application Play lui en donnent la licence pour toute la vie de l'appli. Désactivée,
Play produit des APK standards. Le gain est vague pour une appli de quelques Mo, et le coût est
précis : l'APK installé depuis le store ne serait plus celui qu'on a construit et testé. Un
défaut vu seulement sur la version du store ne se départagerait plus entre notre code et la
transformation de Play. L'APK `beta` installé à la main n'est pas concerné. À rediscuter au
lancement officiel.
