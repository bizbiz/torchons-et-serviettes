# Roadmap

## Important

- **Mode vacances** : geler les streaks ET les taches pendant une absence. Si vous partez 10 jours, les taches ne s'accumulent pas pendant la periode et les streaks ne sont pas casses au retour. Une tache nettoyee juste avant le depart n'a pas besoin d'etre refaite tout de suite en revenant.

## Prochainement

- **Onboarding guide a la creation d'un foyer** : quand on cree un nouveau foyer, un ecran propose directement de cocher des pieces (cuisine, salon, chambre, sdb, ...) puis des tachees (faire la vaisselle, passer l'aspirateur, ...) tirees du catalogue pour passer de foyer vide a utilisable en 30 secondes, sans avoir a tout creer a la main.
- **Catalogue triable par popularite** : en plus du tri alphabetique par defaut, une option de tri remonte en tete les taches / pieces / categories les plus utilisees par les autres foyers. La popularite est calculee silencieusement cote backend (pas de badge "N foyers l'utilisent" sur chaque item, c'est trop intrusif), juste le resultat du tri.
- **Filtres** sur le feed d'historique (par type, par periode).
- **Statistiques hebdomadaires** : resume de fin de semaine par utilisateur.
- **Stats globales du couple** dans le modal profil utilisateur, en plus des stats personnelles : combien le foyer a fait au total, repartition Quentin / ***REMOVED*** sur la semaine, etc.
- **Objectifs** personnels et partages (ex : "nettoyer la cuisine 3× par semaine").

## Plus tard

- **Grace period differenciee accept/refus pour le bump penibilite** : quand l'app propose de bumper l'effort d'une tache souvent en retard, appliquer un cooldown court (50% de la fenetre d'analyse) si le user accepte le bump (pour laisser l'ajustement produire son effet), et un cooldown long (100%) si le user refuse (pour respecter le "non"). Pour l'instant un seul cooldown de 70% est applique dans les deux cas.
- **Audit edition frequence vs votes** : quand des votes de frequence individuels existent pour une tache (via le dialog de notation), l'edition directe du champ recurrence dans la fiche tache ne devrait plus etre possible car le dernier a editer ecrase les votes de tout le monde. A la place, forcer a passer par le flow de re-vote pour que chaque avis compte.
- **Undo 1-clic d'une completion "Fait ensemble"** : aujourd'hui pour annuler une validation collective il faut supprimer les lignes une par une dans l'historique. Un bouton "Annuler" unique, qui supprime d'un coup toutes les lignes du meme evenement (groupees par `collective_id`), serait plus pratique.
- **Nettoyage du code mort "En cours"** : apres suppression de la section sur l'accueil en v0.11.0, les endpoints backend `start task` / `cancel in-progress` et le modele `TaskInProgress` ne sont plus appeles. A retirer lors d'une iteration de maintenance pour ne pas trainer du code mort.
- **Planning partage** : vue calendrier avec les taches prevues de la semaine.
- **Export** des donnees (CSV / ICS).
- **Tablette / Borne** : vue mural always-on pour l'appartement.
