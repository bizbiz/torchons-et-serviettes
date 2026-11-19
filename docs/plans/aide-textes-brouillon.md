# Brouillon des aides « ? », à relire

Brouillon du 19/11/2026 pour `aide-et-bienvenue.md`, relu et validé par Quentin le 19/11. La
version anglaise suit le français, en fin de fichier : c'est la source des futures chaînes
`values-en/strings.xml`.

Entre crochets, la condition d'affichage de la section : `[toujours]`, ou la clé du module qui
doit être allumé. Une section suit la même condition que le bloc qu'elle décrit dans l'écran.
« À faire aujourd'hui » passe dans le noyau : ses sections sont `[toujours]`.

---

## 1. Accueil

**Ce que tu vois ici** `[toujours]`
Les tâches que l'app te propose maintenant, trois au plus. Fais-en une, la suivante arrive.

**Les trois étiquettes** `[toujours]`
« La plus urgente », c'est la tâche commune la plus en retard. « Tu aimes bien », une tâche que
tu as dit apprécier. « Sans cadence », une tâche sans fréquence fixe, à faire quand le besoin se
fait sentir.

**Valider** `[toujours]`
La coche verte veut dire « fait par moi, maintenant ». Le calendrier sert si elle a été faite un
autre jour, par quelqu'un d'autre, ou à plusieurs. La croix la passe : elle reviendra plus tard.

**À faire aujourd'hui** `[toujours]`
Quand quelqu'un te confie une tâche, elle s'affiche en rouge tout en haut, jusqu'à ce que
quelqu'un la fasse. Toi aussi tu peux en confier, avec le « ! » d'une tâche dans la liste.

**Moi et Foyer** `[objectives]`
Où tu en es de ton objectif, et le retard du foyer avec le temps par jour qu'il faudrait pour le
rattraper. Touche une carte pour essayer un autre rythme.

**Les points** `[motivation]`
Chaque tâche rapporte des points selon le temps qu'elle prend. Le 🔥 annonce un bonus pour une
tâche en retard.

**La bulle 💬** `[toujours]`
L'app te pose une question sur une tâche : tu l'aimes ? combien de temps elle prend ? tous les
combien la faire ? Tes réponses l'aident à mieux te proposer.

**La courbe du bas** `[objectives]`
Le retard du foyer sur 30 jours. Plus elle descend, mieux c'est.

## 2. Tâches

**La liste** `[toujours]`
En haut, les tâches à faire, les plus en retard d'abord, puis celles sans fréquence. Les tâches à
jour sont cachées : le bouton tout en bas les affiche.

**Filtrer, chercher** `[toujours]`
Les puces du haut filtrent par contexte (cuisine, chat, jardin…), tu peux en cocher plusieurs.
La loupe cherche dans toutes les tâches, à jour comprises.

**Les trois boutons d'une tâche** `[toujours]`
Le calendrier : elle a été faite un autre jour, par quelqu'un d'autre, ou à plusieurs. Le « ! » :
tu la confies pour aujourd'hui. La coche : fait par moi, maintenant.

**Confier une tâche** `[toujours]`
Le « ! » l'envoie en rouge en haut de l'accueil des autres membres qui la voient, avec des
rappels à 12 h, 16 h et 20 h, jusqu'à ce que quelqu'un la fasse. Retouche le « ! » pour annuler.

**« Pas à la maison »** `[presence]`
Hors de ton WiFi maison, les tâches qui se font sur place se cachent. « Tout afficher » les fait
revenir.

**Ajouter une tâche** `[toujours]`
Le bouton + en bas.

## 3. Stats

**Résumé** `[toujours]`
Ta semaine (ce qui a été fait, et par qui), le temps passé par contexte, et des graphiques à
faire glisser : jours de la semaine, catégories.

**Le retard du foyer** `[objectives]`
Le premier graphique : le retard du foyer sur la période choisie. Plus il descend, mieux c'est.

**Mes objectifs** `[objectives]`
Fixe-toi un rythme, par exemple 3 tâches par semaine, et suis-le ici.

**Ta part** `[repartition]`
L'app compare ce que chacun fait sur 30 jours. Si tu en as fait plus que ta part, elle te le
dit.

**Points, séries, classement** `[motivation]`
Ta série en cours et ton record, le classement de la semaine, du mois et depuis le début, et
l'onglet Badges.

**Historique** `[statistics]`
Tout ce qui s'est passé dans le foyer : tâches faites, votes, modifications, avec des filtres.

## 4. Réglages

**Toi et ton foyer** `[toujours]`
Ton prénom, puis la carte de ton foyer : elle ouvre les réglages communs à tous les membres.

**Fonctionnalités** `[toujours]`
Ici, celles qui ne concernent que toi. Celles du foyer sont dans la carte de ton foyer.

**Points pour moi** `[motivation]`
Tu peux masquer points et séries pour toi seul, sans rien changer pour les autres.

**Notifications** `[reminders]`
Choisis ce qui te prévient : résumé du jour, tâche passée en retard, tâches faites par les
autres.

**Rappels « À faire aujourd'hui »** `[toujours]`
Coupe les rappels de 12 h, 16 h et 20 h. Tu restes prévenu quand on te confie une tâche.

**Un mot de moi** `[toujours]`
Tout en bas : pour m'écrire un bug, une idée, ou m'offrir un café.

## 5. Réglages du foyer

**Pour tout le monde** `[toujours]`
Ce que tu changes ici s'applique à tous les membres du foyer, sauf la partie « Mon compte », en
bas, qui ne concerne que toi.

**Inviter** `[toujours]`
Un QR code à scanner par la personne qui vous rejoint.

**Contextes** `[toujours]`
Les pièces, animaux, extérieurs… qui classent les tâches.

**Fonctionnalités du foyer** `[toujours]`
Allumez ce qui vous sert. Le ⓘ de chaque ligne dit à quoi elle sert.

**Présence et absences** `[presence]`
Quand tout le monde est absent, les tâches à faire sur place se mettent en pause. Le WiFi
maison sert à savoir qui est là.

**Simuler le rattrapage** `[objectives]`
Essaie un rythme et vois en combien de temps le retard du foyer serait rattrapé.

**Bonus et malus** `[bonus_malus]`
Plus de points pour une tâche faite en retard, moins pour une faite trop tôt. Les réglages fins
sont repliés.

**Part visée de chacun** `[repartition]`
Par défaut chacun vise la même part. Vous pouvez la répartir autrement, par exemple si l'un
travaille à temps partiel.

**Mon compte** `[toujours]`
Tes appareils connectés, ton code de récupération : ils valent pour tous tes foyers.

## 6. Fiche d'une tâche

**L'état** `[toujours]`
À jour, bientôt, ou en retard, et dans combien de temps elle revient.

**Pour qui** `[toujours]`
Tout le foyer, ou seulement certains membres.

**Effort et affinité** `[toujours]`
Chacun vote, l'app fait la moyenne. L'effort compte pour les points, l'affinité pour te
proposer ce que tu aimes. Les « + d'info » donnent le détail.

**Fréquence** `[toujours]`
Tous les combien la tâche revient. Chacun peut voter, et le « + d'info » donne le détail.

**Tolérance** `[toujours]`
Elle ne change pas le moment où la tâche passe en retard : elle sert seulement à départager les
tâches en retard pour choisir « La plus urgente ».

**Faire la tâche** `[toujours]`
« Marquer comme fait », « Déjà fait » pour un autre jour ou une autre personne, « Ensemble »
pour une tâche faite à plusieurs, et le « ! » pour la confier.

**Historique** `[toujours]`
Qui l'a faite et quand, et ce qui a changé. Tu peux corriger ou supprimer une validation.

**En haut** `[toujours]`
Dupliquer la tâche, ou la modifier.

## 7. Créer ou modifier une tâche

**Commence par les contextes** `[toujours]`
Ils filtrent les propositions juste en dessous. Le premier coché (★) s'affiche sur la carte de la
tâche.

**Les propositions** `[toujours]`
Choisis-en une : fréquence, effort et contextes se remplissent. Tout reste modifiable.

**Assigné à** `[toujours]`
Tout le monde coché : tâche commune. Une seule personne : tâche perso, visible par elle seule.

**Fréquence** `[toujours]`
Régulière, selon le mois, irrégulière, à date fixe, anniversaire, ou une seule fois. La phrase
sous le champ dit exactement quand la tâche reviendra.

**Effort** `[toujours]`
Le temps que prend la tâche. Chacun pourra donner son estimation ensuite.

**Supprimer** `[toujours]`
La tâche disparaît pour tout le monde.

## 8. Contextes

**À quoi ça sert** `[toujours]`
Un contexte dit pourquoi une tâche existe chez toi : une pièce (Cuisine), un animal (Chat), un
extérieur (Jardin), une situation (Bébé). Une tâche peut en avoir plusieurs. Ils servent de
filtres dans la liste.

**Les deux réglages** `[presence]`
« Il faut être sur place » : la tâche se met en pause pendant une absence. « Ça continue sans
nous » : elle ne s'arrête jamais (la pelouse pousse même sans vous).

**Supprimer** `[toujours]`
Les tâches de ce contexte le perdent, mais restent.

## 9. Mes appareils

**Ce que tu vois** `[toujours]`
Les appareils connectés à ton compte, avec la date où chacun a été vu pour la dernière fois.

**Couper un accès** `[toujours]`
Un téléphone perdu ? Coupe son accès : il sera déconnecté et effacé à sa prochaine connexion.

**Ajouter un appareil** `[toujours]`
Une tablette, un deuxième téléphone : il scanne le QR et se connecte avec ton compte, sans créer
de nouveau membre.

---

## Ce que je propose de retirer des écrans

Les écrans portent peu de paragraphes permanents : surtout des sous-titres courts, des messages
d'état vide et des aides collées à un champ, qui restent. Trois paragraphes partent dans l'aide :

| Écran | Texte retiré | Où il va |
|---|---|---|
| Contextes | l'intro « Un contexte dit pourquoi une tâche existe chez toi… » (`contexts_help`) et son ⓘ | section « À quoi ça sert » |
| Mes appareils | « Les appareils connectés à ton compte. » (`mydevices_intro`) | section « Ce que tu vois » |
| Créer ou modifier | « Coche tout ce qui concerne la tâche… » sous les contextes (`task_contexts_help`) | section « Commence par les contextes » (le ⓘ du champ reste) |

Restent en place : la phrase qui décrit la fréquence choisie, les aides de tolérance et
d'anniversaire, les aides des deux réglages de contexte, les sous-titres des cartes de
navigation, les messages d'état vide.

## Blocs conditionnés à leur module (fait le 19/11)

Cinq blocs s'affichaient même module éteint. Ils sont désormais cachés avec lui, et leur aide
suit : simulation de rattrapage, courbe du bas de l'accueil et graphique du retard des Stats
avec `objectives` ; curseurs
« Tolérance fair-share » avec `repartition`. Depuis le 19/11, l'affinité et le vote d'effort sont
du noyau : le curseur « Renouvellement » et le bloc « Effort et affinité » de la fiche sont
toujours visibles, comme la fréquence.

---

# English version

Same structure and same conditions as the French above. Labels quoted in it are the app's
English strings (`values-en/strings.xml`).

## 1. Home

**What you see here** `[always]`
The tasks the app suggests right now, three at most. Do one and the next one comes up.

**The three labels** `[always]`
"Most urgent" is the most overdue shared task. "You like it" is a task you said you enjoy.
"As needed" is a task with no fixed frequency, to do when the need arises.

**Completing** `[always]`
The green tick means "done by me, now". The calendar is for when it was done on another day, by
someone else, or by several people. The cross skips it: it will come back later.

**To do today** `[always]`
When someone hands you a task, it shows in red at the very top until someone does it. You can
hand out tasks too, with the "!" on a task in the list.

**Me and Household** `[objectives]`
Where you stand on your goal, and the household backlog with the time per day it would take to
catch up. Tap a card to try another pace.

**Points** `[motivation]`
Each task earns points based on how long it takes. The 🔥 announces a bonus for an overdue
task.

**The 💬 bubble** `[always]`
The app asks you about a task: do you like it? how long does it take? how often should it be
done? Your answers help it suggest better.

**The bottom curve** `[objectives]`
The household backlog over 30 days. The lower it goes, the better.

## 2. Tasks

**The list** `[always]`
At the top, the tasks to do, most overdue first, then those with no frequency. Up-to-date tasks
are hidden: the button at the very bottom shows them.

**Filter, search** `[always]`
The chips at the top filter by context (kitchen, cat, garden…), and you can tick several. The
magnifier searches all tasks, up-to-date ones included.

**A task's three buttons** `[always]`
The calendar: it was done on another day, by someone else, or by several people. The "!": you
hand it out for today. The tick: done by me, now.

**Handing out a task** `[always]`
The "!" sends it in red to the top of the home screen of the other members who can see it, with
reminders at 12:00, 16:00 and 20:00, until someone does it. Tap the "!" again to cancel.

**"Away from home"** `[presence]`
Off your home WiFi, tasks that can only be done on site are hidden. "Show all" brings them
back.

**Adding a task** `[always]`
The + button at the bottom.

## 3. Stats

**Summary** `[always]`
Your week (what was done, and by whom), time spent per context, and charts to swipe through:
days of the week, categories.

**Household backlog** `[objectives]`
The first chart: the household backlog over the chosen period. The lower it goes, the better.

**My goals** `[objectives]`
Set yourself a pace, for example 3 tasks a week, and follow it here.

**Your share** `[repartition]`
The app compares what everyone does over 30 days. If you did more than your share, it tells
you.

**Points, streaks, leaderboard** `[motivation]`
Your current streak and your record, the leaderboard for the week, the month and all time, and
the Badges tab.

**History** `[statistics]`
Everything that happened in the household: tasks done, votes, changes, with filters.

## 4. Settings

**You and your household** `[always]`
Your first name, then your household's card: it opens the settings shared by all members.

**Features** `[always]`
Here, the ones that only concern you. The household ones are in your household's card.

**Points for me** `[motivation]`
You can hide points and streaks just for yourself, without changing anything for the others.

**Notifications** `[reminders]`
Choose what alerts you: daily summary, a task becoming overdue, tasks done by the others.

**"To do today" reminders** `[always]`
Turns off the 12:00, 16:00 and 20:00 reminders. You are still notified when someone hands you a
task.

**A word from me** `[always]`
At the very bottom: to write to me about a bug or an idea, or to buy me a coffee.

## 5. Household settings

**For everyone** `[always]`
What you change here applies to every member of the household, except the "My account" part at
the bottom, which only concerns you.

**Invite** `[always]`
A QR code for the person joining you to scan.

**Contexts** `[always]`
The rooms, pets, outdoor areas… that sort the tasks.

**Household features** `[always]`
Turn on what helps you. The ⓘ on each line says what it is for.

**Presence and absences** `[presence]`
When everyone is away, tasks that must be done on site are paused. The home WiFi tells who is
there.

**Simulate the catch-up** `[objectives]`
Try a pace and see how long it would take to clear the household backlog.

**Bonus and penalty** `[bonus_malus]`
More points for a task done late, fewer for one done too early. The fine settings are folded
away.

**Target share of each member** `[repartition]`
By default everyone aims for the same share. You can split it differently, for example if one
of you works part-time.

**My account** `[always]`
Your connected devices and your recovery code: they apply to all your households.

## 6. Task details

**Status** `[always]`
Up to date, due soon, or overdue, and how long until it comes back.

**Who for** `[always]`
The whole household, or only some members.

**Effort and affinity** `[always]`
Everyone votes, the app takes the average. Effort counts for points, affinity for suggesting
what you like. The "more info" links give the details.

**Frequency** `[always]`
How often the task comes back. Everyone can vote, and "more info" gives the details.

**Tolerance** `[always]`
It does not change when the task becomes overdue: it only ranks overdue tasks to pick the "Most
urgent" one.

**Doing the task** `[always]`
"Mark as done", "Already done" for another day or another person, "Together" for a task done by
several people, and the "!" to hand it out.

**History** `[always]`
Who did it and when, and what changed. You can correct or delete a completion.

**At the top** `[always]`
Duplicate the task, or edit it.

## 7. Create or edit a task

**Start with the contexts** `[always]`
They filter the suggestions just below. The first one ticked (★) shows on the task's card.

**Suggestions** `[always]`
Pick one: frequency, effort and contexts fill in. Everything stays editable.

**Assigned to** `[always]`
Everyone ticked: a shared task. A single person: a personal task, visible to them only.

**Frequency** `[always]`
Regular, by the month, irregular, fixed date, birthday, or just once. The sentence under the
field says exactly when the task will come back.

**Effort** `[always]`
How long the task takes. Everyone can give their own estimate later.

**Delete** `[always]`
The task disappears for everyone.

## 8. Contexts

**What they are for** `[always]`
A context says why a task exists in your home: a room (Kitchen), a pet (Cat), an outdoor area
(Garden), a situation (Baby). A task can have several. They act as filters in the list.

**The two settings** `[presence]`
"You must be there": the task pauses during an absence. "It keeps going without us": it never
stops (the grass grows even without you).

**Delete** `[always]`
The tasks in this context lose it, but stay.

## 9. My devices

**What you see** `[always]`
The devices connected to your account, with the date each one was last seen.

**Cutting off access** `[always]`
Lost a phone? Cut off its access: it will be signed out and wiped the next time it connects.

**Adding a device** `[always]`
A tablet, a second phone: it scans the QR code and signs in with your account, without creating
a new member.

## Welcome screen and Settings card

The French texts are in `aide-et-bienvenue.md`.

> **Hi, I'm Quentin 👋**
>
> I made this app. I tried to keep it as simple as possible while letting it do lots of things,
> and that is really not easy!
>
> Some features are switched off while you get the hang of the app: they will be offered to you
> little by little. To turn them on sooner: Settings, then your household's card.
>
> If something is unclear, tap the "?" at the top of the screen: it explains the page you are
> looking at.
>
> A bug, an idea? Write to me: Settings, at the very bottom.

Button: "Let's go".

> **A word from Quentin**
>
> I build this app in my spare time. A bug, an idea, something unclear? Write to me, I read
> everything.
>
> And if it helps you out, you can buy me a coffee ☕

Buttons, in this order: "Write to me", "Buy me a coffee".
