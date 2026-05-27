# Politique de sécurité

> 🇬🇧 [Read in English](SECURITY.md)

## Versions supportées

| Version | Supportée |
|---------|-----------|
| `main` (HEAD) | ✓ |
| Tags `v0.32.x` | ✓ correctifs de sécurité |
| Tags antérieurs | ✗ pas de support |

Le projet est en cycle de dev rapide pré-public — il n'y a pas
encore de branche de stabilisation. Toutes les corrections de
sécurité partent sur `main` puis sont taggées dans la release
suivante.

## Reporter une vulnérabilité

**Ne pas ouvrir d'issue publique** pour un problème de sécurité.
Contacter le mainteneur en privé :

- GitHub Security Advisory (préféré) : onglet « Security » du repo
  → « Report a vulnerability ». Le rapport reste privé jusqu'à
  publication du fix.
- À défaut : email à `contact@torchonsetserviettes.app`.

Inclure dans le rapport :

- Une description du problème
- Les étapes pour reproduire (idéalement avec une PoC minimaliste)
- L'impact estimé (lecture/écriture non autorisée, DoS, RCE…)
- Toute version connue affectée

## Ce qui est in-scope

- Bypass d'authentification ou d'autorisation (JWT, scoping foyer,
  rôles admin)
- Injection (SQL, command, header, template)
- Fuite de données entre foyers (un user A qui voit les données
  d'un foyer B sans y appartenir)
- Élévation de privilège (passage member → admin sans légitimité)
- Vulnérabilités côté Android (Intent injection, stockage de
  secrets, etc.)
- Vulnérabilités dans les dépendances utilisées en production

## Ce qui est out-of-scope

- Self-DoS volontaire (envoyer 10 000 requêtes avec ton propre token)
- Bypass de rate limiting non implémenté à ce stade
- Vulnérabilités dans une instance auto-hébergée tierce (configure
  Caddy / Tailscale toi-même, ce n'est pas le code applicatif)
- Suggestions UX/UI déguisées en sécurité

## Process de réponse

1. Acknowledgement du rapport sous 7 jours.
2. Investigation + reproduction sous 14 jours.
3. Fix développé sur une branche privée, puis tag de release dès que
   possible (généralement < 30j pour une faille critique).
4. Coordinated disclosure : tu peux publier ta découverte une fois
   le fix taggé et déployé, avec crédit dans le changelog si tu le
   souhaites.

## Pas de bug bounty

Le projet n'a pas de programme de bug bounty et n'est pas
financièrement en mesure d'en proposer un. Le crédit (changelog +
remerciement public) est la seule contrepartie offerte.
