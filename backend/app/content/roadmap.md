# Roadmap

## Prochainement

- **Planning partage** : vue calendrier avec les taches prevues de la semaine, par jour, par membre.
- **Vue tablette / borne** : layout adapte tablette + mode always-on pour afficher l'app dans l'entree, le salon ou la cuisine.
- **Mini-onboarding au 1er vote effort/frequence** : en plus des modaux "+ d'info" deja en place, un petit tutoriel pas-a-pas la 1re fois pour expliquer ce qu'on demande.

## Plus tard

- **Grace period differenciee accept/refus pour le bump penibilite** : quand l'app propose de bumper l'effort d'une tache souvent en retard, appliquer un cooldown court (50% de la fenetre d'analyse) si le user accepte le bump (pour laisser l'ajustement produire son effet), et un cooldown long (100%) si le user refuse (pour respecter le "non"). Pour l'instant un seul cooldown de 70% est applique dans les deux cas.
- **Prévenir quand on rejoint le foyer sous un prénom déjà pris** : après une réinstallation, un
  membre qui scanne le QR « Rejoindre » au lieu de « Retrouver mon foyer » repart aujourd'hui sur
  un compte neuf, sans ses tâches ni son historique. L'app lui demanderait d'abord s'il est déjà
  membre et le guiderait vers son code de récupération.
- **Retrouver son compte sans code de récupération** : un autre membre du foyer pourrait
  rattacher le nouveau téléphone au compte de celui qui a perdu son code. Cette fonction doit être
  protégée pour qu'un membre ne puisse pas prendre le compte d'un autre.
- **Prévenir clairement quand un téléphone perd l'accès au foyer** : aujourd'hui, l'app efface
  bien ses données, mais elle reste sur l'écran en cours et affiche des erreurs de connexion
  jusqu'à ce qu'on la relance. Elle afficherait tout de suite un écran qui explique que
  l'accès a été coupé et propose « Retrouver mon foyer ».
- **Mettre le foyer en pause pendant une absence** : quand personne n'a ouvert l'app ni validé de
  tâche depuis 3 jours ou plus, le foyer passerait en pause. Les fréquences calculées
  automatiquement ne bougeraient plus. Aujourd'hui, des vacances font croire à l'app que la
  vaisselle se fait tous les 44 jours. On regardera aussi ce qui doit se mettre en pause avec
  elles. Au retour du premier membre, un message d'accueil l'aiderait à reprendre.
- **Notifications de tous ses foyers** : un membre de plusieurs foyers n'est prévenu que pour
  le foyer ouvert dans l'app. Les tâches en retard et les validations d'un autre foyer ne lui
  arrivent qu'une fois qu'il y a basculé. Il serait prévenu pour chacun de ses foyers, chaque
  notification nommant le sien.
- **Mode "famille etendue"** : plusieurs foyers relies (parents + grands-parents qui se voient les taches), version pro pour airbnb / colocation longue.
