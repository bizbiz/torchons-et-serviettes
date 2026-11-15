# Héberger T&S : savoir quel code tourne

Version anglaise : [`self-hosting.md`](self-hosting.md).

Ce document s'adresse à qui fait tourner son propre serveur T&S. Il ne décrit pas un
déploiement particulier : il dit **comment vérifier quel code un serveur exécute**, quels
pièges ont été mesurés, et comment lire une trace de crash de l'APK minifié. L'ordre
client/serveur est dans [`architecture.fr.md`](architecture.fr.md) § Ordre de synchronisation ;
l'activation du garde-fou de version est dans [`version-gate.fr.md`](version-gate.fr.md).

## Vérifier : pourquoi `version` ne suffit pas

Le champ `version` de `/api/health` est la première ligne `## vX.Y.Z` d'un changelog
**recopié à la main** entre machines. Il dit ce qu'on a voulu livrer, jamais ce qui tourne,
et il a menti dans les deux sens :

- **2026-08-20** : un serveur annonçait `0.36.1` en servant du code récent. L'écart se voyait.
- **2026-09-01** : un serveur annonçait `0.38.0`, la version exacte de `origin/main`, en
  servant un code vieux de douze jours auquel manquaient deux correctifs. **Rien ne se
  voyait.** C'est ce sens-là qui est dangereux.

D'où `code_fingerprint` : un sha256 des `.py` réellement exécutés, calculé au démarrage
(`backend/app/utils/code_fingerprint.py`) et recalculable depuis n'importe quelle référence git
par `scripts/fingerprint_of_ref.py` (`python scripts/fingerprint_of_ref.py [ref]`, par défaut
`origin/main`). Le script importe `compute_fingerprint` du backend au lieu de le réécrire, et
lit les blobs de la référence, jamais l'arbre de travail (fins de ligne). C'est le seul champ
de `/api/health` qui ne peut pas être recopié par erreur.

`git_sha` est déclaratif : il vient de la variable d'environnement `GIT_SHA` au démarrage et
reste vide si le déploiement n'en fournit pas. Pour un transport sans commit à déclarer
(archive envoyée depuis un poste), l'empreinte est alors la seule mesure.

### `scripts/deployed_versions.sh`

```bash
bash scripts/deployed_versions.sh          # ou `prod` / `beta`
```

Il compare, pour chaque environnement, l'empreinte servie (`/api/health`) à celle de la
référence. Il se règle par des variables d'environnement `TS_*`, qui peuvent aussi être posées
dans `deployed_versions.local.env` à la racine (ignoré par git) ou dans le fichier désigné par
`TS_ENV_FILE`. Une variable déjà définie dans l'environnement l'emporte.

| Variable | Rôle |
|---|---|
| `TS_DEPLOY_REF` | référence comparée (défaut `origin/main`) |
| `TS_PROD_HEALTH` | URL de santé de la prod ; à pointer sur votre serveur |
| `TS_BETA_HEALTH` | URL de santé d'un second environnement ; sans elle, il est non mesuré |
| `TS_BETA_HOST` | hôte ssh de cet environnement, pour le repli indirect seulement |
| `TS_BETA_PATH` | dossier du backend sur cet hôte, relatif à `$HOME` (idem) |
| `TS_PYTHON` | interpréteur Python utilisé pour recalculer l'empreinte |

Le script sort `0` si tout est aligné, `1` sur un écart, `2` si un environnement n'a pas pu
être mesuré. **Un `2` ne se lit jamais comme un feu vert.** Quand l'empreinte servie est
celle du travail non commité de la machine qui lance le script, il répond « EXPECTED GAP » :
c'est le régime normal pendant un chantier, pas un défaut.

**L'empreinte ne couvre pas les migrations.** `backend/alembic/` vit hors de `app/`, et le
démarrage joue `alembic upgrade head` : un déploiement qui n'apporte **qu'une migration**
laisse `code_fingerprint` inchangé, et le script répondrait « ALIGNED » alors que le schéma
diffère (mesuré le 2026-09-02 : 46 migrations et `env.py` hors du périmètre). Le témoin du
schéma est ailleurs : `SELECT version_num FROM alembic_version`.

## Pièges mesurés

**Alembic lit `DATABASE_URL_SYNC`, pas `DATABASE_URL`** (`backend/app/config.py`). Surcharger
la mauvaise variable fait tourner la migration sur la base par défaut **sans rien dire** :
mesuré le 2026-08-20, silence complet, révision inchangée. Relire
`SELECT version_num FROM alembic_version` plutôt que de conclure de l'absence d'erreur.

**Une répétition de migration se fait sur une base jetable restaurée depuis un dump**, jamais
sur un environnement dont les données comptent.

**Un `docker compose up` qui réussit ne prouve pas que le nouveau code est servi** : le
conteneur peut redémarrer sur son image précédente. Comparer les empreintes plutôt que de
s'arrêter au code de retour de compose.

**Un changement de fichier compose ne se voit pas dans l'empreinte**, qui ne porte que sur
les `.py`. Si le transport n'envoie que le code, copier le fichier compose à part, en gardant
l'ancien, avant de reconstruire ; sinon tout passe au vert (rebuild réussi, empreinte
identique) et le conteneur tourne avec l'ancienne configuration (vécu le 2026-09-21 avec
`init: true`).

**Attendre la santé, jamais dormir un temps fixe.** Le démarrage enchaîne les migrations puis
uvicorn ; une boucle sans temporisation épuise ses essais en une seconde pendant que le
conteneur démarre (mesuré le 2026-09-02 : 60 essais consommés avant que le port écoute).

## L'APK est minifié : une trace de crash ne se lit pas telle quelle

R8 est actif en `release` depuis le 2026-09-03 (`android/app/build.gradle.kts`). L'APK descend
de 37,41 à 25,13 Mo, et **toutes les classes sont renommées** : une trace remontée d'un
téléphone est illisible sans le fichier de correspondance du build EXACT qui a produit cet APK.

Ce fichier ne survit pas tout seul : R8 l'écrit sous `build/`, qu'un nettoyage supprime, et il
pèse ~72 Mo. La tâche Gradle `archiveR8Mapping` le recopie donc après chaque build de release :

    android/mapping-archive/<variante>-v<versionCode>-<versionName>-mapping.txt

Pour désobfusquer une trace, `retrace` n'est pas dans le SDK Android (ni `tools/proguard/`, ni
`cmdline-tools/`) : il est livré avec Android Studio. Vérifié le 2026-09-03 (Retrace 9.2.7) :

    JBR="<android-studio>/jbr"
    R8="<android-studio>/plugins/android/lib/r8.jar"
    M=android/mapping-archive/<variante>-v<versionCode>-<versionName>-mapping.txt
    "$JBR/bin/java" -cp "$R8" com.android.tools.r8.retrace.Retrace "$M" trace.txt

Le mapping est celui du build INSTALLÉ : son `versionCode` se lit par
`adb shell dumpsys package <applicationId> | grep versionCode`. Si aucun `java` n'est dans le
PATH, utiliser le JDK d'Android Studio ; un build Gradle lancé depuis le shell exige de même
`JAVA_HOME="$JBR"`, sinon il sort en 49.

Deux limites à connaître avant d'en avoir besoin. Le dossier est **gitignoré** et vit sur le
poste de build seulement : une réinstallation le perd, et avec lui la lisibilité des crashs
des APK déjà distribués. Et **rebâtir le même `versionCode` écrase l'archive précédente** :
si l'on reconstruit plusieurs fois une même version, seule la dernière correspondance subsiste,
alors qu'un téléphone peut encore porter une build antérieure.
