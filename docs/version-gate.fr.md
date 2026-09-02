# Version gate — retirer une version d'APK de la circulation

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

Chaque canal a déjà son backend (prod sur Scaleway, beta sur <beta-host>), donc le
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

## Se débloquer

Le seuil est la seule chose à corriger, et elle vit côté serveur : remettre
`MIN_APP_VERSION_CODE=0` dans `.env`, redémarrer. Les apps se débloquent au
prochain appel réussi… **sauf celles qui ont déjà persisté le verdict** — leur
écran ne se lève qu'en installant un APK dont le `versionCode` atteint le seuil
mémorisé. C'est voulu (sinon le mode avion contournerait le gate), et c'est
exactement pourquoi l'étape 1 ci-dessus n'est pas négociable : **on ne lève un
seuil que sur une version déjà distribuable.**
