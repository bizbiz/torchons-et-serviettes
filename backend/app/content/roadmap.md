# Roadmap

## Prochainement

- **Onboarding guide a la creation d'un foyer** : quand on cree un nouveau foyer, un ecran propose directement de cocher des pieces (cuisine, salon, chambre, sdb, ...) puis des tachees (faire la vaisselle, passer l'aspirateur, ...) tirees du catalogue pour passer de foyer vide a utilisable en 30 secondes, sans avoir a tout creer a la main.
- **Statistiques hebdomadaires** : resume de fin de semaine par utilisateur.
- **Stats globales du couple** dans le modal profil utilisateur, en plus des stats personnelles : combien le foyer a fait au total, repartition Quentin / ***REMOVED*** sur la semaine, etc.
- **Statistiques long-terme et par dimension** : prolonger les graphiques de la v0.22-23 avec des vues plus analytiques — evolution du retard moyen sur plusieurs semaines, repartition des taches par piece (camembert), temps cumule par jour deduit de l'effort estime. Aujourd'hui les graphiques montrent surtout les points cumules ; on aimerait pouvoir aussi comprendre ou passe le temps.
- **Objectifs** personnels et partages (ex : "nettoyer la cuisine 3× par semaine").

## Plus tard

- **Grace period differenciee accept/refus pour le bump penibilite** : quand l'app propose de bumper l'effort d'une tache souvent en retard, appliquer un cooldown court (50% de la fenetre d'analyse) si le user accepte le bump (pour laisser l'ajustement produire son effet), et un cooldown long (100%) si le user refuse (pour respecter le "non"). Pour l'instant un seul cooldown de 70% est applique dans les deux cas.
- **Planning partage** : vue calendrier avec les taches prevues de la semaine.
- **Export** des donnees (CSV / ICS).
- **Tablette / Borne** : vue mural always-on pour l'appartement.
