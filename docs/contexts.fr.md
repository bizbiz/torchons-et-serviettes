# Contextes — le modèle qui remplace catégories et pièces

Version anglaise : [`contexts.md`](contexts.md).

Un **contexte** est ce qui explique qu'une tâche existe : une pièce, un animal,
un extérieur, une situation de vie, ou un genre de travail. Il remplace à la
fois `Category` et `Room`, et une tâche peut en porter **plusieurs**.

## Pourquoi une seule table

Les deux anciennes tables étaient déjà quasi identiques — même `foyer_id`,
`name`, `color`, `icon`, `template_key` ; elles ne différaient que d'un champ
chacune (`requires_home` pour l'une, `emoji` pour l'autre). Ce qui les
séparait vraiment était une **contrainte de cardinalité** : une tâche portait
exactement une pièce et exactement une catégorie.

Cette contrainte était fausse. « Nettoyer la litière » relève du Salon (où
elle est), du Chat (pourquoi elle existe) et du Ménage (ce que c'est). Il
fallait choisir, et n'importe quel choix perdait deux informations sur trois.

## Les deux drapeaux, et pourquoi pas un seul

`requires_home` répondait à **deux questions distinctes avec une seule
valeur**, donc se trompait forcément sur l'une des deux :

| Drapeau | Question | Ce qu'il pilote |
|---------|----------|-----------------|
| `requires_presence` | faut-il être sur place ? | masquage hors WiFi maison |
| `accrues_when_away` | le besoin monte-t-il sans nous ? | gel pendant les absences |

Le jardin et le chat le prouvent **en sens inverse** :

- Jardin : il faut être là pour tondre (masquage juste) mais l'herbe pousse
  quand même (gel faux).
- Chat : il faut être là pour la litière (masquage faux, il était désactivé)
  mais rien ne s'accumule si le chat part en pension (gel juste).

Aucune valeur unique ne satisfaisait les deux cas.

**Résolution en OU.** Une tâche portant plusieurs contextes prend le OU logique
de leurs drapeaux : dès qu'un seul contexte l'exige, la règle s'applique. Une
tâche de jardin rattachée aussi à Ménage continue donc de courir pendant une
absence, parce que Jardin porte `accrues_when_away`.

## Contextes implicites : ce qu'on ne demande pas

Un contexte est `selectable: false` quand tout foyer l'a — `menage`, `linge`,
`entretien`. Demander « faites-vous du ménage ? » n'a pas de sens ; ces
contextes servent au **filtrage** (« montre-moi tout le ménage »), jamais au
choix.

**Règle de visibilité, la seule qui compte.** Une tâche apparaît si l'un de ses
contextes **sélectionnables** est coché. Les implicites ne révèlent rien à eux
seuls. Sans cette restriction, toute tâche portant `menage` s'afficherait quoi
qu'il arrive — c'est exactement le défaut corrigé : 41 % du catalogue était
affiché sans condition, y compris les tâches de chat à qui n'a pas de chat.

Le code de référence est `Catalog.tasks_for()`
(`backend/app/services/catalog.py`), redit à l'identique côté Android dans
`CreateFoyerWizardViewModel.visibleContextTasks()`.

## Le contexte d'affichage est le premier SÉLECTIONNÉ

`position` retient l'ordre du catalogue, mais l'affichage prend le premier
contexte que **l'utilisateur a coché**, pas le premier déclaré. Une tâche
rattachée à `[chat, chien]` doit apparaître sous Chien chez quelqu'un qui n'a
qu'un chien.

## Ce qu'un import crée, et ce qu'il ne crée pas

Une tâche déclare souvent plus de contextes que le foyer n'en possède : « Faire
les comptes communs » porte `[colocation, administratif]`. Un foyer d'une
personne qui coche Administratif ne doit pas hériter d'un contexte Colocation.

`kept_context_keys()` (`backend/app/api/catalog.py`) ne garde donc que **les
contextes choisis plus les implicites**, avec un repli sur le premier déclaré
pour ne jamais laisser une tâche orpheline — sans contexte, elle sortirait de
tous les filtres.

⚠️ Cette règle vit à **deux endroits du même flux** : `resolve` propose les
pièces à créer, `apply` les crée. Corriger `apply` seul ne suffit pas — mesuré :
le contexte écarté revenait par les `room_decisions` que le client construit à
partir de la réponse de `resolve`, avec l'air d'avoir été demandé.

## Les deux tables héritées ont été supprimées

`rooms` et `categories` ont vécu en double écriture le temps qu'un APK basculé
sorte. Elles n'existent plus : `e5f6a1b2c3d4_drop_rooms_categories.py` les
supprime, avec `tasks.room_id`, `tasks.category_id`, et convertit
`objectives.scope_room_id`/`scope_category_id` en un `scope_context_id` unique.

La migration **échoue plutôt que de supprimer à l'aveugle** : si une tâche
vivante a une pièce ou une catégorie mais aucun contexte, elle s'arrête en
donnant le compte. C'est le seul cas où la suppression perdrait de
l'information, et une migration destructrice qui répare en silence ce qu'elle
ne comprend pas transforme un problème visible en donnée corrompue.

⚠️ **Ordre de déploiement inversé.** La règle de synchronisation impose le
serveur AVANT le client pour un *ajout* ; pour un *retrait*, c'est l'inverse —
le client doit avoir cessé d'utiliser ce qui disparaît. Cette migration vient
donc APRÈS la diffusion d'un APK basculé, jamais avant.

Deux noms de champs survivent volontairement : `room_keys` en entrée d'import
et `category_id` dans les réponses de statistiques. Ils portent désormais des
contextes. Les renommer ne changerait rien au modèle et casserait tout client
en vol pendant le déploiement ; les nouveaux noms (`context_keys`, `contexts`)
existent à côté.

## Migration

`c3d4e5f6a9b0_contexts.py`. Les pièces passent en premier pour gagner la
déduplication sur `lower(trim(name))` — elles portent l'emoji et le
rattachement le plus riche. Pas de repli d'accents : `unaccent` exigerait des
droits superutilisateur indisponibles sur Scaleway, et le cas réel (« Cuisine »
pièce contre « Cuisine » catégorie) porte les mêmes accents.

`accrues_when_away` ne se déduit pas de l'existant : il est mis à `true` par
`template_key` puis, pour les contextes créés à la main, par une **liste courte
de noms sans ambiguïté**. « Perso », « Maison » ou « Hygiène » restent à
`false` — le faux négatif est le bon côté de l'erreur ici, puisque `false` = on
gèle = le comportement actuel, donc personne ne voit son app changer sans
l'avoir demandé.

Répétée sur un dump de production (18 foyers, 101 tâches) : 28 contextes, 0
doublon, 136 rattachements, 54 tâches multi-contextes.

## Proposer des tâches : `GET /api/catalog/suggestions`

Le catalogue compte 181 tâches. Les présenter toutes, c'est une liste qu'on
referme. L'endpoint ne renvoie que celles qui **touchent un contexte que le
foyer possède déjà** : un foyer avec un chat se voit proposer la litière, un
foyer sans chat ne la voit jamais.

Trois règles portent le résultat :

- **Même visibilité qu'ailleurs** — un contexte implicite (`menage`, `linge`,
  `entretien`) ne rend rien visible à lui seul, sinon la moitié du catalogue
  remonterait pour tout le monde.
- **Appariement par `template_key` puis par nom** — un contexte créé à la main
  et nommé « Chat » doit ramener les tâches de chat, sans quoi la fonction ne
  servirait qu'aux foyers issus du wizard.
- **Exclusion sur `template_key`** — ce que le foyer a déjà n'est pas
  reproposé. Une tâche recréée à la main sous le même titre repassera : mieux
  vaut reproposer que masquer.

Le tri met les tâches les plus légères d'abord. Une suggestion doit donner
envie, et une corvée de 45 minutes en tête de liste fait fermer l'écran.

`context_ids` est **résolu côté serveur** vers les contextes du foyer : le
client n'a pas la table de correspondance entre clés de catalogue et contextes
locaux, et la lui faire deviner serait dupliquer l'appariement.

Côté app, ces propositions vivent **dans l'écran de création de tâche**, sous
le champ titre, qui les filtre à mesure qu'on tape. En choisir une remplit le
titre, les contextes, la fréquence, l'effort et la tolérance d'un coup — tout
restant modifiable. Il n'y a plus d'écran « catalogue » séparé.

## Récurrence calendaire

Le type `calendar` ancre l'échéance sur le **calendrier** et non sur la dernière
complétion, ce qui supprime la dérive : faire ses impôts le 10 juin une année
ne décale pas l'échéance de l'année suivante.

```json
{"type": "calendar", "months": [5], "day": 15, "grace_days": 0}
```

- `months` : 1..12, triés et dédupliqués ; `day` : plafonné à 28 pour qu'aucun
  mois ne saute une occurrence.
- `grace_days` : délai après l'échéance avant le **rouge**. C'est ce qui
  exprime « mai–août » : une occurrence au 1er mai tolérée jusqu'à fin août.
  L'orange, lui, tombe à la date prévue — la tâche est signalée à son heure et
  seule l'alerte est repoussée.

Les trois anciens types `weekdays`, `monthly` et `custom` sont convertis en
`interval` équivalent à la normalisation (`_legacy_interval`) : plus aucune
tâche n'est silencieusement inerte. Leur refus en entrée reste à faire
(`todo.md`).

**Phrases explicatives.** `RecurrenceSentence.kt` rend chaque règle en français
ou en anglais au moment où l'utilisateur la paramètre. L'exemple donné utilise
volontairement un jour **en retard** (`n+3`) plutôt qu'un jour pile : c'est le
cas où interval et calendar divergent, donc le seul qui montre ce qu'on choisit.
