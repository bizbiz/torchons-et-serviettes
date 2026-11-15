# Version gate — retirer une version d'APK de la circulation

Version anglaise : [`version-gate.md`](version-gate.md).

Le serveur peut refuser de servir les routes de données à un APK trop ancien.
L'app affiche alors un écran terminal « Mise à jour requise » et ne fait plus
rien d'autre.

Ce document dit **ce que le mécanisme protège**, **ce qu'il ne protège pas**, et
la **procédure d'activation** — dont l'ordre des étapes est la seule chose qui
sépare un retrait propre d'un foyer bloqué sans issue.

## Ce que ça protège, et ce que ça ne protège pas

Ça retire de la circulation **une version buguée chez des utilisateurs de bonne
foi**. C'est le vrai risque de cette app, et le mécanisme y répond bien.

Ça ne protège **pas** contre quelqu'un qui contrôle l'APK. Le client déclare
lui-même sa version : il peut mentir, ou retirer le test. Le blocage côté app
est de l'ergonomie — il dit « mets à jour » ; la barrière est le refus serveur,
et elle-même repose sur une déclaration du client.

Face à une **fuite de secrets** (jetons volés), le geste utile est de révoquer
les jetons, pas de lever un seuil de version. Garder les deux mécanismes
distincts : ils répondent à deux menaces différentes.

## Comment ça marche

| Pièce | Rôle |
|---|---|
| `MIN_APP_VERSION_CODE` (`.env`) | le seuil. `0` = désactivé, défaut |
| `MIN_APP_DOWNLOAD_URL` (`.env`) | où récupérer l'APK, affiché sur l'écran de blocage |
| `backend/app/utils/version_gate.py` | middleware : lit `X-App-Version-Code`, répond `426` |
| `AppVersionInterceptor.kt` | envoie le header, lit le `426` |
| `VersionGateMonitor.kt` | porte le verdict, le persiste, le lève à la mise à jour |
| `AppUpdateRequiredScreen.kt` | l'écran terminal |

`versionCode` est comparé, jamais `versionName` : c'est un entier monotone,
alors que `"0.9.0"` est supérieur à `"0.10.0"` en comparaison de chaînes.

Chaque canal a déjà son backend (prod sur Scaleway, beta sur son propre hôte), donc le
seuil est par déploiement — aucun canal n'a besoin d'être nommé dans le header.

### Trois décisions qui ne sont pas des détails

**Une requête SANS le header passe.** Tout APK construit avant ce mécanisme
n'envoie rien. Les refuser bloquerait tous les téléphones du foyer au moment
même du déploiement, avant qu'un APK corrigé existe. Le trou se referme tout
seul : quand toutes les versions en circulation envoient le header, le seuil
suffit à les filtrer par leur numéro.

**Le seuil n'est jamais dérivé d'`APP_VERSION`.** Cette valeur est la première
ligne `## vX.Y.Z` d'un changelog recopié à la main entre machines, et elle a
déjà menti dans les deux sens (cf. `scripts/deployed_versions.sh`). Un seuil
assis dessus hériterait du mensonge — sauf qu'ici le mensonge verrouille des
téléphones.

**Hors ligne : on ne bloque pas sur l'absence de réponse, on bloque sur un
refus déjà reçu.** Tant qu'aucun `426` n'est arrivé, l'app fonctionne, réseau ou
pas — bloquer sur un silence tuerait une app offline-first dans le métro. Une
fois le `426` reçu, le verdict est persisté et s'applique même hors ligne, sinon
le mode avion suffirait à contourner le gate. Le verdict se lève tout seul quand
le `versionCode` installé rattrape le seuil mémorisé.

## Activer un seuil — l'ordre compte

1. **Construire et diffuser l'APK corrigé d'abord.** Le seuil se lève sur une
   version qui existe déjà et que le foyer peut installer.
2. **Renseigner `MIN_APP_DOWNLOAD_URL`** avec l'adresse réelle. Un seuil levé
   sans adresse laisse l'écran dire « demande à l'administrateur », ce qui est
   honnête mais inutile à 22 h.
3. **Poser `MIN_APP_VERSION_CODE`** au `versionCode` du plus ancien APK qu'on
   accepte encore — c'est un seuil inclusif : cette valeur passe, celle du
   dessous non.
4. **Redémarrer le backend**, la valeur étant lue au démarrage.
5. **Vérifier avant d'y croire** :

```bash
# doit rendre le seuil qu'on vient de poser
curl -s https://api.torchonsetserviettes.app/api/health

# un client sous le seuil doit recevoir 426
curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'X-App-Version-Code: 1' https://api.torchonsetserviettes.app/api/tasks

# et /api/health doit RESTER joignable pour ce même client, sinon un seuil
# mal posé devient une panne muette qu'on ne peut plus diagnostiquer
curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'X-App-Version-Code: 1' https://api.torchonsetserviettes.app/api/health
```

Mesuré sur la beta le 2026-09-02, seuil à 999 : client v95 sur `/api/tasks`
→ `426` avec le corps complet ; client v999 → `401` (passe le gate, refusé par
l'auth) ; sans header → `401` (laissé passer) ; client v95 sur `/api/health`
→ `200`.

## Se débloquer

Le seuil est la seule chose à corriger, et elle vit côté serveur : remettre
`MIN_APP_VERSION_CODE=0` dans `.env`, redémarrer. Les apps se débloquent au
prochain appel réussi… **sauf celles qui ont déjà persisté le verdict** — leur
écran ne se lève qu'en installant un APK dont le `versionCode` atteint le seuil
mémorisé. C'est voulu (sinon le mode avion contournerait le gate), et c'est
exactement pourquoi l'étape 1 ci-dessus n'est pas négociable : **on ne lève un
seuil que sur une version déjà distribuable.**

## Éprouvé de bout en bout — 2026-09-02

Le maillon Android a été joué sur un Pixel 7, APK beta `debug` v95, seuil beta
posé à 96 puis retiré. Les quatre pièces client ont tourné pour la première fois
depuis leur livraison : l'en-tête part (`X-App-Version-Code: 95` dans
`logcat`), le `426` remonte (`<-- 426 Upgrade Required` sur `/api/auth/auto`),
l'écran s'affiche en nommant les deux versions, et le verdict survit au
redémarrage sans réseau.

**Une app bloquée n'émet plus AUCUNE requête.** C'est plus fort que « son écran
ne se lève pas » : elle ne parle plus au serveur du tout, donc rien de ce qu'on
y change ne peut l'atteindre. Vérifié en remettant le seuil à `0` — le serveur
répondait de nouveau `401` à un client v95, et l'app est restée sur son écran
terminal, zéro requête émise. Ce que cette mesure ajoute au paragraphe
ci-dessus : aucun correctif serveur, quel qu'il soit, ne peut servir de secours.

La levée automatique, elle, fonctionne : APK `versionCode` 96 installé
par-dessus, l'app repart normalement et annonce `X-App-Version-Code: 96`.

Deux détails pour qui rejouera ce constat :

- Le constat ci-dessus passait par `/api/auth/auto`, que l'app beta appelait au
  démarrage. Cette route est retirée depuis le 2026-10-05 : sans session,
  l'accueil n'appelle plus le serveur, et le gate frappe au premier appel non
  exempté — l'écran principal d'une app déjà connectée, ou une étape
  d'onboarding.
- Le premier rendu d'une app déjà bloquée est l'écran de gate, jamais l'UI
  normale : le splash système reste affiché ~1,1 s, ce qui couvre largement la
  relecture du verdict par `VersionGateMonitor.restore()`. Mesuré par rafale de
  captures, résolution ~420 ms — un affichage plus bref n'est pas exclu.

⚠️ **Ce constat est à rejouer : ses conditions n'existent plus.** Il a tourné le
2026-09-02 sur un build `debug`, à une époque où `isMinifyEnabled` valait `false`
en `release` aussi — R8 ne pouvait donc expliquer aucun écart. **R8 a été activé
le 2026-09-03** (`android/app/build.gradle.kts`, buildType `release`) : un APK de
release est désormais minifié et obfusqué, et ce constat n'a jamais tourné dans
cette configuration. Ce qui est en jeu n'est pas hypothétique — le gate traverse
la lecture d'un corps JSON par `org.json` puis la persistance DataStore du
verdict, deux chemins que R8 peut casser. Et la panne est muette : un gate
inopérant ne produit aucune erreur, l'app continue simplement de fonctionner
alors qu'elle aurait dû être bloquée.
