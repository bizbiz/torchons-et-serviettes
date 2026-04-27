# Roadmap

## Prochainement

- **Onboarding guide a la creation d'un foyer** : quand on cree un nouveau foyer, un ecran propose directement de cocher des pieces (cuisine, salon, chambre, sdb, ...) puis des tachees (faire la vaisselle, passer l'aspirateur, ...) tirees du catalogue pour passer de foyer vide a utilisable en 30 secondes, sans avoir a tout creer a la main.
- **Catalogue triable par popularite** : en plus du tri alphabetique par defaut, une option de tri remonte en tete les taches / pieces / categories les plus utilisees par les autres foyers. La popularite est calculee silencieusement cote backend (pas de badge "N foyers l'utilisent" sur chaque item, c'est trop intrusif), juste le resultat du tri.
- **Statistiques hebdomadaires** : resume de fin de semaine par utilisateur.
- **Stats globales du couple** dans le modal profil utilisateur, en plus des stats personnelles : combien le foyer a fait au total, repartition Quentin / ***REMOVED*** sur la semaine, etc.
- **Statistiques long-terme et par dimension** : prolonger les graphiques de la v0.22 avec des vues plus analytiques — evolution du retard moyen sur plusieurs semaines, repartition des taches par categorie ou par piece (camembert ou bar chart), temps cumule par jour deduit de l'effort estime. Aujourd'hui les graphiques montrent surtout les points cumules ; on aimerait pouvoir aussi comprendre ou passe le temps.
- **Selecteur de periode sur le graphique "Par jour de semaine"** : aujourd'hui le graphique cumule tout l'historique. Pouvoir le filtrer sur "30 derniers jours" ou "ce mois-ci" pour voir si une tendance recente apparait (p.ex. depuis le retour de vacances je fais plus le week-end).
- **Frequence par categorie dans la carte Statistiques** : un graphique additionnel qui montre quelles categories chaque user fait le plus (cuisine / sdb / linge / etc.). Complete naturellement le graphique "Par jour de semaine".
- **Comparaison semaine vs semaine precedente** : sous la course de points 7 jours, afficher "+15% vs semaine derniere" ou "-20%" avec une fleche. Perspective instantanee de progression sans avoir a comparer les deux courbes a la main.
- **Buckets mensuels pour la course "Depuis toujours"** : aujourd'hui on bascule sur des buckets hebdomadaires des que l'historique depasse 90 jours. Pour des historiques tres longs (1 an+), passer a des buckets mensuels pour garder le graphique lisible.
- **Tap sur un point du graphique = liste des completions de ce jour** : sur la course de points, taper un point ouvre l'historique pre-filtre sur la date du point. Permet d'enqueter sur un pic ou un creux.
- **Objectifs** personnels et partages (ex : "nettoyer la cuisine 3× par semaine").

## Plus tard

- **Grace period differenciee accept/refus pour le bump penibilite** : quand l'app propose de bumper l'effort d'une tache souvent en retard, appliquer un cooldown court (50% de la fenetre d'analyse) si le user accepte le bump (pour laisser l'ajustement produire son effet), et un cooldown long (100%) si le user refuse (pour respecter le "non"). Pour l'instant un seul cooldown de 70% est applique dans les deux cas.
- **Nettoyage du code mort "En cours"** : apres suppression de la section sur l'accueil en v0.11.0, les endpoints backend `start task` / `cancel in-progress` et le modele `TaskInProgress` ne sont plus appeles. A retirer lors d'une iteration de maintenance pour ne pas trainer du code mort.
- **Planning partage** : vue calendrier avec les taches prevues de la semaine.
- **Export** des donnees (CSV / ICS).
- **Tablette / Borne** : vue mural always-on pour l'appartement.
