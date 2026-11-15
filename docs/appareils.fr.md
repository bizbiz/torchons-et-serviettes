# Appareils : voir ses sessions, en couper une

Version anglaise : [`appareils.md`](appareils.md).

Le jeton de session de T&S **n'expire jamais** et c'est un choix, pas un oubli : une app de
tâches ménagères qui redemande un code toutes les semaines coûte un agacement quotidien à
tout le foyer contre un bénéfice qui ne se matérialise presque jamais. Ce document décrit ce
qui rend ce jeton **révocable** malgré tout, appareil par appareil.

## Le problème, mesuré

Deux faits, établis le 2026-09-02, qui ne se voient pas en lisant le code de loin.

**Le jeton est éternel et irrévocable.** `backend/app/auth.py` le fabrique avec
`{"sub": user_id, "device_id": device_id}` — aucun champ `exp` — et le serveur ne porte
aucune liste de révocation, aucun `token_version`, aucun registre d'appareils. Le seul geste
disponible était de changer `jwt_secret`, ce qui déconnecte **tout le foyer d'un coup**,
enfants et conjoint compris. Le jour où un téléphone est perdu ou revendu, ce n'était pas un
choix, c'était une mauvaise surprise.

**La sauvegarde Android recopie l'identité.** `allowBackup` est actif (décision assumée, voir
le commentaire au-dessus de `<application>` dans `AndroidManifest.xml`). Mesuré :
`bmgr backupnow` envoie 812 Ko, et après une désinstallation complète suivie d'une
réinstallation, **la session revient sans reconnexion**. Le `device_id` vivait alors dans
`torchons_prefs`, donc il voyageait avec — deux téléphones issus d'une même sauvegarde
portaient la même identité, et les distinguer était impossible.

Le commentaire du code disait « UUID stable par installation ». C'était vrai avant Auto
Backup, et faux depuis, sans que rien ne le signale.

## Ce qui a été construit

### Côté serveur

| Pièce | Rôle |
|---|---|
| `backend/app/models/device.py` | une ligne par (user, appareil), avec `revoked_at` |
| `backend/app/utils/devices.py` | résolution à chaque appel : refus si révoqué, adoption sinon |
| `backend/app/api/devices.py` | lister, enregistrer, révoquer, se déconnecter |
| `backend/app/api/recovery.py` | régénérer le code coupe aussi les autres appareils |
| `backend/app/utils/errors.py` | `code` machine dans le corps d'erreur, en plus de `detail` |
| `backend/app/api/notifications.py` | lie le jeton FCM à l'appareil, depuis le JWT |

### Côté application

| Pièce | Rôle |
|---|---|
| `res/xml/data_extraction_rules.xml` | exclut l'identité de la sauvegarde (API 31+) |
| `res/xml/full_backup_content.xml` | la même exclusion pour API 26-30 |
| `data/local/DeviceIdentityStore.kt` | l'identité, dans son propre DataStore exclu |
| `core/network/DeviceRegistrar.kt` | la déclare au démarrage, gère la transition |
| `core/network/DeviceRevocationInterceptor.kt` | repère `DEVICE_REVOKED` dans un 401 |
| `core/network/DeviceRevocationMonitor.kt` | efface Room, les préférences et l'identité |
| `ui/devices/DevicesScreen.kt` | l'écran, atteint depuis Foyer → Mes appareils |

## Les sept décisions, et ce qui les a tranchées

**1. On révoque un APPAREIL, et son identité sort de la sauvegarde.** C'est la seule
exclusion : jeton, préférences, WiFi maison et base Room continuent d'être restaurés. Sans
elle, deux téléphones partagent une identité et couper l'un coupe l'autre.

**2. Chacun ne voit que SES appareils.** Le champ `UserFoyer.role` (`admin`/`member`) existe
en base mais n'est consulté nulle part dans le produit. Donner à un membre le pouvoir de
déconnecter un autre membre serait le premier pouvoir asymétrique de l'app ; la révocation
d'appareil n'est pas la fonctionnalité qui doit l'introduire.

**3. Révoquer efface aussi les données locales.** L'app est offline-first : couper l'accès
serveur laisse **sept tables Room** lisibles — tâches, complétions, historique, contextes,
membres du foyer, snapshots, file d'attente. `clearAuthData()` seul ne les touche pas.
L'effacement se déclenche sur le code machine `DEVICE_REVOKED`, **jamais** sur un 401
ordinaire : un jeton expiré ou une panne d'authentification ne doivent rien effacer.

**4. Le nom ne suffit pas à distinguer, les dates si.** Mesuré sur un Pixel 7 :
`Build.MODEL`, `settings global device_name` et `bluetooth_name` rendent **tous les trois
« Pixel 7 »**. L'écran s'appuie donc sur le marqueur « cet appareil » (fourni par le serveur)
puis sur la date d'**ajout** — au moment précis où l'on révoque un ancien téléphone, les deux
viennent d'être vus, mais un seul vient d'être ajouté.

**5. Régénérer le code coupe les autres appareils.** Un code régénéré signifie « l'ancien a
pu fuiter ». Fermer la porte du code sans fermer celles déjà ouvertes ne protège de rien,
puisqu'une session existante ne dépend plus du code. L'appareil qui déclenche l'opération
reste connecté : sinon on se met dehors en cliquant sur le bouton censé protéger.

**6. La transition après restauration est SILENCIEUSE.** C'est la décision qui a été corrigée
en cours de route. Il paraissait naturel qu'un appareil restauré redemande le code — c'est
faux : il détient le jeton restauré, qui est valide et prouve l'identité à lui seul. Le
serveur enregistre donc la nouvelle identité et rebascule la session dessus, sans rien
demander. Distinguer les appareils ne coûte **aucune manipulation**.

**7. Révoquer coupe aussi les notifications** *(ajouté le 2026-09-03)*. Sans cela, un
téléphone dont l'accès était coupé continuait d'annoncer les tâches du foyer — l'API était
fermée, ce canal restait ouvert. `notification_tokens` porte désormais un `device_id`, rempli
**depuis le JWT** et non depuis le corps de la requête : le client ne peut pas mentir dessus,
et surtout les APK déjà installés sont couverts sans attendre une mise à jour de l'app. Les
lignes créées avant cette colonne ont un `device_id` nul et se rattachent d'elles-mêmes au
prochain enregistrement FCM.

**8. Quitter le foyer ou supprimer son compte révoque aussi** *(ajouté le 2026-10-05)*.
`get_current_user` revérifie à chaque appel que l'utilisateur existe et qu'il est membre ACTIF
du foyer de son jeton (`user_foyers.left_at` nul). Avant, un jeton sans `exp` survivait à un
départ ou à `DELETE /me` : lecture et écriture conservées, sans limite. Le refus est
`DEVICE_REVOKED` et pas un 401 ordinaire, parce que c'est le seul que l'app traite en
s'effaçant : les APK déjà installés retombent sur l'accueil au lieu de rester bloqués sur des
erreurs. Le téléphone qui quitte lui-même le foyer n'est pas touché, `/leave` lui rend un
jeton sur son autre foyer ; un AUTRE téléphone du même utilisateur, resté sur le foyer quitté,
est révoqué, et « Retrouver mon foyer » le ramène sur le foyer restant. Sa file hors ligne est
perdue : choix assumé, le cas est rare dans un foyer à deux. Décidé le 2026-10-12 : pas
d'« exclure un membre » pour l'instant, seul le départ volontaire existe.

## Comment ça se passe, concrètement

**Changement de téléphone.** Le nouveau restaure tout, sauf l'identité. Au démarrage,
`DeviceRegistrar` déclare une identité neuve ; le serveur voit un jeton valide portant une
identité inconnue, enregistre le nouvel appareil et rend un jeton qui lui est propre.
L'utilisateur ne voit rien. Les deux téléphones apparaissent séparément dans la liste.

**Donner son ancien téléphone.** Réglages → Foyer → Mes appareils, « Couper l'accès » sur
l'ancien. Il est déconnecté, et ses données locales sont effacées à sa prochaine connexion.

**Code de récupération divulgué.** Régénérer le code : le nouveau s'affiche une seule fois,
et tous les autres appareils sont coupés dans le même geste.

**Quitter son dernier foyer, ou supprimer son compte : 30 jours pour revenir** (décidé le
2026-11-02, même règle pour les deux).
- **Au départ** : le membre est retiré des tâches partagées. Ses tâches solo sont désactivées,
  mais restent à son nom.
- **Pendant 30 jours** : « Retrouver mon foyer » avec son code le ramène dans le foyer quitté,
  avec ses tâches. Les autres voient son nom barré. Au retour, ses tâches solo se réactivent et
  il est remis sur les tâches partagées qu'il avait au départ (décidé le 2026-11-03), sauf
  celles supprimées depuis. Seul compte le dernier départ.
- **Ensuite** : le nettoyage de nuit anonymise le compte, sans le supprimer. Son nom devient
  « Ancien membre » et son code est effacé, donc il n'y a plus de retour possible. Ses données
  personnelles et ses tâches solo sont supprimées. Ses validations restent dans l'historique du
  foyer.

Le détail est dans `backend/app/services/member_departure.py` et `task_departure.py`.
L'anonymisation remplace une purge qui échouait sur tout compte ayant agi : six clés
étrangères vers `users` n'avaient pas de règle `ON DELETE`.

## Dépannage : un membre a perdu l'accès à son compte, sans code

⚠️ **Cette procédure ne devrait jamais servir.** Elle écrit en base de prod hors migration, ce
que la règle interdit (la base de prod n'est modifiée que par une migration déployée) :
chaque usage est une exception, accordée par le mainteneur pour ce cas-là
seulement. Si elle sert, c'est que le produit a laissé un membre sans issue — les deux pistes
qui l'éviteraient sont dans la roadmap (`backend/app/content/roadmap.md`, « Plus tard »),
pas dans cette page.

Le cas, vécu le 2026-10-18 : une mise à jour refusée par Android, l'app désinstallée puis
réinstallée sans la session, aucun code de récupération noté. Le membre a rejoint le foyer par
un QR « Rejoindre » et s'est retrouvé sur un compte **neuf** et vide ; son compte d'origine,
avec tout son historique, était intact en base. On rend donc un code à l'ancien compte au lieu
de fusionner les deux : une fusion réécrirait une vingtaine de tables pointant vers `users.id`,
dont plusieurs à clé primaire (user, tâche).

1. **Lire** (lecture seule) les membres du foyer et l'activité de chacun, pour identifier
   l'ancien compte et vérifier que le doublon n'a rien produit.
2. **Dump** : base de prod, avant toute écriture (script de sauvegarde du mainteneur, hors dépôt).
3. **Régénérer** le code de l'ancien compte par un script lancé une fois dans `ts_api_prod`,
   qui fait les mêmes écritures que `/recovery/regenerate` (`generate_code`, `hash_code`,
   `lookup_key`, `revoke_other_devices`), vérifie l'identité du compte avant d'écrire et relit
   le hash après. Le code s'affiche dans le terminal, jamais dans un fichier, un commit ou un
   log Actions. Script effacé du conteneur et de l'hôte ensuite.
4. **Sur le téléphone** : Réglages → Foyer → « Quitter le foyer » sur le compte en double,
   puis « Retrouver mon foyer » avec le code. L'écran de confirmation doit montrer le nombre de
   complétions de l'ancien compte, pas zéro.
5. Le membre **régénère aussitôt** son code depuis l'app et le note : celui de l'étape 3 est
   passé par un terminal.

## Ce que ça ne protège pas — à dire, pas à cacher

**Un appareil qui ne se reconnecte jamais garde son cache.** Aucune application ne peut
effacer un téléphone hors ligne. L'écran le dit à l'utilisateur au moment de confirmer,
plutôt que de laisser croire à un effacement à distance.

**Les jetons antérieurs à la v0.7.0 ne sont pas révocables.** Ils ne portent pas de claim
`device_id`, donc aucune ligne ne leur correspond. Les brancher créerait une ligne par
requête, l'ancien code fabriquant un UUID neuf à chaque appel. Le remède, si le cas se
présente, reste la rotation de `jwt_secret`.

**Tourner `jwt_secret` déconnecte tout le monde, et c'est voulu.** Depuis `8ce7850`, un jeton
dont la signature ne se vérifie pas rend `DEVICE_REVOKED` (`auth.py::decode_token`) : chaque
téléphone s'efface et revient à l'accueil, au lieu de rester sur des 401 ordinaires.

- **Faite une fois, le 2026-11-02 en prod.** L'ancien secret était lisible dans le commit
  `5d45e42c` : il est toujours servi par GitHub et était commun avec le dev.
- **Avant de tourner** : chaque membre doit avoir noté son code de récupération. Ils sont tous
  déconnectés en même temps, donc aucun ne peut en faire revenir un autre par QR.
- **Comment c'est fait** :
  - La valeur est générée sur le serveur et n'en sort jamais. On ne compare que des empreintes.
  - Le conteneur `api` est recréé avec `--force-recreate`. Attention : recréé ainsi, il perd
    `git_sha` dans `/api/health` jusqu'au prochain `deploy.yml`.
- **Preuve que la rotation a pris** : un jeton signé avec l'ancien secret qui porte un claim
  `scope` passe de « 401 ordinaire » (signature valide, puis refus par `get_current_user`) à
  `DEVICE_REVOKED`.
- **Ce que la rotation coûte** : les actions hors ligne pas encore envoyées sont perdues. Les
  écrans ne suivent pas tout de suite, voir le paragraphe suivant.

Ces deux chemins sont énumérés dans l'en-tête de `backend/tests/test_devices.py`, avec ceux
qui sont couverts — l'inventaire vit à côté des tests, pas seulement ici.

**L'écran ne suit pas l'effacement (constaté le 2026-11-02, piste « Plus tard » de la
roadmap).** Lors de la rotation du `JWT_SECRET` de prod, le téléphone de Quentin n'est pas revenu
à l'accueil : l'app ouverte affichait des erreurs « serveur injoignable » et y restait. Il a fallu
la tuer et la relancer pour retomber sur l'écran d'accueil, puis « Retrouver mon foyer ».

- **Cause, lue dans le code** : `DeviceRevocationMonitor.revoked` est un `StateFlow` dont la
  KDoc dit qu'il renvoie l'interface vers l'onboarding, mais aucun écran ne le collecte. Seul
  `versionGateMonitor.blocked` est collecté, dans `MainActivity.setContent`. L'effacement a bien
  lieu (tables Room, jeton, identité), mais l'interface garde son état. Au lancement suivant,
  comme il n'y a plus de jeton, l'app ouvre l'onboarding.
- **Non vérifié** : pourquoi le message affiché parle de serveur injoignable plutôt que d'accès
  coupé. Une hypothèse est que les appels partent sans jeton une fois celui-ci effacé, et que leur
  erreur passe dans le message générique. À confirmer avant de corriger.
- **Remède envisagé** : collecter `revoked` dans `MainActivity`, sur le modèle du version gate.
  L'app afficherait alors un écran terminal « Cet appareil n'a plus accès au foyer », avec un
  bouton « Retrouver mon foyer ».
  - Le texte doit couvrir les deux causes, parce que le serveur rend le même `DEVICE_REVOKED`
    pour l'une et l'autre : un autre membre a coupé l'appareil, ou le secret a tourné.
  - L'écran pourrait aussi dire que les actions faites hors ligne et pas encore envoyées sont
    perdues, puisque la table des opérations en attente est effacée avec le reste.
- **Vérifier** : sur l'émulateur (`ts-test-runner`), couper l'appareil depuis un autre membre
  pendant que l'app est ouverte. Le retour à l'accueil doit se faire sans relancer l'app.

## Pièges pour qui touchera à ça

**Ne jamais remettre `device_id` dans `torchons_prefs`.** Il redeviendrait sauvegardé, et
l'échec est **silencieux** : l'app fonctionne parfaitement, la liste affiche simplement un
appareil là où il y en a deux.

**Les deux fichiers de règles s'éditent ensemble.** `minSdk` vaut 26 : n'en déclarer qu'un
laisse la moitié du parc au comportement par défaut, sans aucun signal.

**L'adoption s'applique à une identité INCONNUE, jamais RÉVOQUÉE.** C'est le point porteur.
Si `/devices/register` adoptait une identité neuve présentée avec un jeton révoqué, un
téléphone coupé se re-délivrerait un accès et la révocation serait décorative. Le test
`test_revoked_device_cannot_adopt_new_identity` garde exactement ce point.

**`resolve_device` doit committer.** Elle tourne dans une dépendance d'authentification,
avant le corps de la route : sans `commit`, la ligne meurt avec la transaction et l'appareil
est ré-adopté à chaque appel, donc jamais révocable. Mesuré : les trois tests de révocation
échouaient en `404` avant que cette ligne existe.

**Un test qui appelle `/recovery/regenerate` révoque tout le compte.** La suite partage un
seul `TEST_USER_ID` : sans le nettoyage de `test_devices.py`, un seul test met 17 tests de
`test_sync` en 401. Mesuré : 60 lignes révoquées, 17 rouges ailleurs.

## Vérifier

```bash
cd backend && ./.venv/Scripts/python.exe -m pytest tests/test_devices.py -q   # 9 cas
```

Le contrôle négatif a été joué : en débranchant le refus des appareils révoqués, **4 tests
rougissent — exactement les 4 qui testent cette règle**, et les 5 autres restent verts.
