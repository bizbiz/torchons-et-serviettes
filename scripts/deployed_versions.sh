#!/usr/bin/env bash
# What code does each deployed environment actually run?
#
# `/api/health` answers `version`, read from the first `## vX.Y.Z` line of
# app/content/changelog.md. That string is COPIED by hand between machines, so it
# says what someone MEANT to ship. It has lied in both directions:
#
#   2026-08-20  beta announced 0.36.1 while running recent code -- the gap SHOWED.
#   2026-09-01  beta announced 0.38.0, the exact version of origin/main, while
#               running code from twelve days earlier, missing two fixes.
#               Nothing showed. That direction is the dangerous one.
#
# So no environment is judged on `version`. Two measurements, in order:
#
#   DIRECT (preferred)   `code_fingerprint` from /api/health, a sha256 over the
#                        .py files the process is actually running, recomputed
#                        here from the ref. Works over plain HTTP: no ssh, no
#                        deployment metadata, same method for every environment.
#   INDIRECT (fallback)  for backends deployed before the fingerprint existed:
#                        prod via the last successful deploy.yml SHA, beta via
#                        an ssh file-by-file comparison. Both are reported as
#                        indirect, because they witness what was SENT, not what
#                        is being served.
#
# Exit codes: 0 = every environment aligned, 1 = a gap, 2 = could not measure.

set -uo pipefail
export LC_ALL=C   # stable collation: `join` and `comm` depend on it

REF=${TS_DEPLOY_REF:-origin/main}
BETA_HOST=${TS_BETA_HOST:-<beta-host>}
BETA_PATH=deploiements/ts-beta/backend
BETA_HEALTH=${TS_BETA_HEALTH:-http://<beta-host-ip>:8002/api/health}
PROD_HEALTH=${TS_PROD_HEALTH:-https://api.torchonsetserviettes.app/api/health}
PYTHON=${TS_PYTHON:-backend/.venv/Scripts/python.exe}

WANTED=${1:-all}
case "$WANTED" in
  all|prod|beta) ;;
  *) echo "usage: $0 [all|prod|beta]"; exit 2 ;;
esac

cd "$(dirname "$0")/.." || { echo "CANNOT MEASURE: repository root not found"; exit 2; }
git rev-parse --git-dir >/dev/null 2>&1 \
  || { echo "CANNOT MEASURE: not inside a git repository"; exit 2; }
git fetch --quiet 2>/dev/null
git rev-parse --verify --quiet "$REF" >/dev/null \
  || { echo "CANNOT MEASURE: reference $REF does not exist"; exit 2; }

REF_SHA=$(git rev-parse "$REF")
echo "reference : $REF ($(git rev-parse --short "$REF_SHA")) -- $(git log -1 --date=short --format='%ad %s' "$REF_SHA")"

# Computed once: identical for every environment, and it costs ~90 `git show`.
REF_FINGERPRINT=$([ -x "$PYTHON" ] && "$PYTHON" scripts/fingerprint_of_ref.py "$REF_SHA" 2>/dev/null)
case "$REF_FINGERPRINT" in
  [0-9a-f]*) echo "empreinte : $REF_FINGERPRINT" ;;
  *) REF_FINGERPRINT=""; echo "empreinte : non calculable ($PYTHON absent ?) -- mesure indirecte seulement" ;;
esac

# Empreinte du working tree de CE poste. Elle sert a nommer le cas legitime le
# plus frequent : pendant un chantier, la beta sert deliberement du travail pas
# encore commite (docker-compose.beta.yml le documente). Sans cette comparaison,
# ce cas normal et un vrai retard de deploiement rendent le meme « GAP ».
WT_FINGERPRINT=$([ -x "$PYTHON" ] && "$PYTHON" -c "
import sys; sys.path.insert(0, 'backend')
from pathlib import Path
from app.utils.code_fingerprint import compute_fingerprint
print(compute_fingerprint(Path('backend/app')))" 2>/dev/null)
case "$WT_FINGERPRINT" in [0-9a-f]*) ;; *) WT_FINGERPRINT="" ;; esac
echo

GAP=0
UNMEASURED=0
HEALTH_BODY=""

fetch_health() {   # $1 = health URL ; fills HEALTH_BODY
  HEALTH_BODY=$(curl -s -m 15 "$1" 2>/dev/null)
  [ -n "$HEALTH_BODY" ]
}

json_field() {   # $1 = field name ; reads HEALTH_BODY
  printf '%s' "$HEALTH_BODY" \
    | sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"
}

# Returns 0 when the direct measurement concluded (aligned or gap), 1 when the
# environment predates the fingerprint and the caller must fall back.
check_fingerprint() {   # $1 = env label
  local served
  served=$(json_field code_fingerprint)
  [ -n "$served" ] || return 1
  [ -n "$REF_FINGERPRINT" ] || return 1

  local sha; sha=$(json_field git_sha)
  [ -n "$sha" ] && echo "  git_sha        : $sha  (declare par le deploiement)"
  echo "  empreinte      : $served  (mesuree sur le code servi)"
  if [ "$served" = "$REF_FINGERPRINT" ]; then
    echo "  ALIGNED: $1 sert exactement le code de $REF."
  elif [ -n "$WT_FINGERPRINT" ] && [ "$served" = "$WT_FINGERPRINT" ]; then
    # Nommer cette cause change ce que la sortie demande au lecteur : rien a
    # chercher, seulement a decider si ce travail doit etre commite.
    echo "  ECART ATTENDU: $1 sert le travail NON COMMITE de ce poste"
    echo "                 (identique au working tree, different de $REF)."
    echo "                 Normal pendant un chantier ; a commiter pour refermer."
    GAP=1
  else
    echo "  GAP: le code servi n'est ni $REF ni le working tree de ce poste"
    echo "       (attendu $REF_FINGERPRINT). La version annoncee ne le dit pas."
    GAP=1
  fi
  return 0
}

# --- prod: fallback on the deployment workflow, the only other witness -------
check_prod() {
  echo "== prod =="
  local announced deployed short behind backend_behind
  if ! fetch_health "$PROD_HEALTH"; then
    echo "  CANNOT MEASURE: $PROD_HEALTH n'a pas repondu"
    UNMEASURED=1; echo; return
  fi
  announced=$(json_field version)
  [ -n "$announced" ] || { echo "  CANNOT MEASURE: pas de champ version"; UNMEASURED=1; echo; return; }
  echo "  annonce        : $announced  ($PROD_HEALTH)"

  if check_fingerprint prod; then echo; return; fi
  echo "  (pas d'empreinte servie : backend anterieur, mesure INDIRECTE)"

  if ! command -v gh >/dev/null 2>&1; then
    echo "  CANNOT MEASURE: gh absent, impossible de lire les runs deploy.yml"
    UNMEASURED=1; echo; return
  fi
  deployed=$(gh run list --workflow=deploy.yml --status=success --limit=1 \
               --json headSha --jq '.[0].headSha' 2>/dev/null)
  case "$deployed" in
    ''|null) echo "  CANNOT MEASURE: aucun run deploy.yml reussi"; UNMEASURED=1; echo; return ;;
    *[!0-9a-f]*) echo "  CANNOT MEASURE: gh a rendu autre chose qu'un SHA"; UNMEASURED=1; echo; return ;;
  esac
  if ! git cat-file -e "${deployed}^{commit}" 2>/dev/null; then
    echo "  CANNOT MEASURE: SHA deploye $deployed inconnu localement"
    UNMEASURED=1; echo; return
  fi
  short=$(git rev-parse --short "$deployed")
  echo "  dernier deploy : $short -- $(git log -1 --date=short --format='%ad %s' "$deployed")"

  if [ "$deployed" = "$REF_SHA" ]; then
    echo "  ALIGNED (indirect): le dernier deploiement est $REF."
    echo; return
  fi
  if ! git merge-base --is-ancestor "$deployed" "$REF_SHA" 2>/dev/null; then
    echo "  GAP: $short n'est pas un ancetre de $REF."
    GAP=1; echo; return
  fi
  behind=$(git rev-list --count "${deployed}..${REF_SHA}")
  backend_behind=$(git rev-list --count "${deployed}..${REF_SHA}" -- backend/)
  echo "  retard sur $REF : $behind commit(s), dont $backend_behind touchant backend/"
  if [ "$backend_behind" -eq 0 ]; then
    echo "  ALIGNED (indirect): les $behind commit(s) non deployes ne touchent pas le backend."
  else
    echo "  GAP: $backend_behind commit(s) backend non deployes :"
    git log --date=short --format='    %h %ad %s' "${deployed}..${REF_SHA}" -- backend/
    GAP=1
  fi
  echo
}

# --- beta: fallback on ssh fingerprints, nothing else witnesses a `tar | ssh` -
check_beta() {
  echo "== beta =="
  local announced tmp n_repo rc missing extra common joined differing gapcount
  if ! fetch_health "$BETA_HEALTH"; then
    echo "  CANNOT MEASURE: $BETA_HEALTH n'a pas repondu"
    UNMEASURED=1; echo; return
  fi
  announced=$(json_field version)
  [ -n "$announced" ] || { echo "  CANNOT MEASURE: pas de champ version"; UNMEASURED=1; echo; return; }
  echo "  annonce        : $announced  ($BETA_HEALTH)"

  if check_fingerprint beta; then echo; return; fi
  echo "  (pas d'empreinte servie : backend anterieur, mesure par ssh)"

  if ! ssh -o ConnectTimeout=10 -o BatchMode=yes "$BETA_HOST" true 2>/dev/null; then
    echo "  CANNOT MEASURE: $BETA_HOST injoignable en ssh"
    UNMEASURED=1; echo; return
  fi
  if ! ssh -o ConnectTimeout=10 "$BETA_HOST" "test -d ~/$BETA_PATH/app" 2>/dev/null; then
    echo "  CANNOT MEASURE: ~/$BETA_PATH/app absent sur $BETA_HOST"
    UNMEASURED=1; echo; return
  fi
  tmp=$(mktemp -d) || { echo "  CANNOT MEASURE: mktemp"; UNMEASURED=1; echo; return; }

  # Cote depot on lit les BLOBS : core.autocrlf=true sur ce poste met des CRLF
  # dans le working tree que le depot n'a pas.
  git ls-tree -r --name-only "$REF_SHA" -- backend/app \
    | grep '\.py$' | sed 's|^backend/||' | sort > "$tmp/list"
  n_repo=$(wc -l < "$tmp/list")
  if [ "$n_repo" -le 50 ]; then
    echo "  CANNOT MEASURE: $n_repo fichier(s) .py — lecture suspecte"
    UNMEASURED=1; rm -rf "$tmp"; echo; return
  fi
  while IFS= read -r f; do
    printf '%s  %s\n' "$(git show "$REF_SHA:backend/$f" | sha256sum | cut -c1-64)" "$f"
  done < "$tmp/list" | sort -k2 > "$tmp/repo"

  ssh -o ConnectTimeout=15 "$BETA_HOST" "cd ~/$BETA_PATH && find app -name '*.py' -not -path '*__pycache__*' | sort | while IFS= read -r f; do printf '%s  %s\n' \"\$(tr -d '\r' < \"\$f\" | sha256sum | cut -c1-64)\" \"\$f\"; done" \
    > "$tmp/beta" 2>/dev/null
  rc=$?
  if [ "$rc" -ne 0 ] || [ ! -s "$tmp/beta" ]; then
    echo "  CANNOT MEASURE: releve des empreintes echoue (code $rc)"
    UNMEASURED=1; rm -rf "$tmp"; echo; return
  fi
  sort -k2 -o "$tmp/beta" "$tmp/beta"

  cut -d' ' -f3- "$tmp/repo" > "$tmp/n-repo"
  cut -d' ' -f3- "$tmp/beta" > "$tmp/n-beta"
  missing=$(comm -23 "$tmp/n-repo" "$tmp/n-beta")
  extra=$(comm -13 "$tmp/n-repo" "$tmp/n-beta")

  # Un `join` qui ne rapproche rien rend « aucune difference » — donc vert, pour
  # la pire des raisons. On exige le denominateur avant de croire le zero.
  common=$(comm -12 "$tmp/n-repo" "$tmp/n-beta" | grep -c '[^[:space:]]')
  join -j 2 -o 0,1.1,2.1 "$tmp/repo" "$tmp/beta" > "$tmp/joined" 2>/dev/null
  joined=$(grep -c '[^[:space:]]' "$tmp/joined")
  if [ "$joined" -ne "$common" ]; then
    echo "  CANNOT MEASURE: join a rapproche $joined ligne(s) pour $common fichier(s) communs"
    UNMEASURED=1; rm -rf "$tmp"; echo; return
  fi
  differing=$(awk '$2!=$3{print $1}' "$tmp/joined")
  gapcount=$(printf '%s\n%s\n%s\n' "$missing" "$extra" "$differing" | grep -c '[^[:space:]]')

  echo "  fichiers .py   : $n_repo dans $REF, $(wc -l < "$tmp/beta") sur beta, $common compares"
  if [ "$gapcount" -eq 0 ]; then
    echo "  ALIGNED: beta sert le code de $REF."
  else
    echo "  GAP: $gapcount fichier(s) different de $REF"
    if [ -n "$missing" ];   then echo "    absents de beta   :"; echo "$missing"   | sed 's/^/      /'; fi
    if [ -n "$extra" ];     then echo "    en trop sur beta  :"; echo "$extra"     | sed 's/^/      /'; fi
    if [ -n "$differing" ]; then echo "    contenu different :"; echo "$differing" | sed 's/^/      /'; fi
    GAP=1
  fi
  rm -rf "$tmp"
  echo
}

case "$WANTED" in
  all)  check_prod; check_beta ;;
  prod) check_prod ;;
  beta) check_beta ;;
esac

if [ "$UNMEASURED" -eq 1 ]; then
  echo "VERDICT: un environnement au moins n'a pas pu etre mesure -- pas un feu vert."
  exit 2
fi
if [ "$GAP" -eq 1 ]; then
  echo "VERDICT: un environnement sert un code different de $REF sans que sa version le dise."
  exit 1
fi
echo "VERDICT: tous les environnements mesures servent $REF."
exit 0
