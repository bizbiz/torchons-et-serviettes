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
# The beta lives on a private host: no default, the environment must say where.
#   TS_BETA_HEALTH  health URL of the beta (required to measure it)
#   TS_BETA_HOST    ssh host of the beta (only for the indirect fallback)
#   TS_BETA_PATH    backend directory on that host, relative to $HOME (idem)
# They may be set in deployed_versions.local.env at the repo root (git-ignored), or in the
# file named by TS_ENV_FILE; read if present. Variables already set in the environment win.
LOCAL_ENV="${TS_ENV_FILE:-$(dirname "$0")/../deployed_versions.local.env}"
if [ -f "$LOCAL_ENV" ]; then
  while IFS='=' read -r key value; do
    case "$key" in
      TS_BETA_HEALTH|TS_BETA_HOST|TS_BETA_PATH)
        [ -z "${!key:-}" ] && export "$key=$value" ;;
    esac
  done < <(grep -E '^TS_[A-Z_]+=' "$LOCAL_ENV" | tr -d '\r')
fi
BETA_HOST=${TS_BETA_HOST:-}
BETA_PATH=${TS_BETA_PATH:-}
BETA_HEALTH=${TS_BETA_HEALTH:-}
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
  [0-9a-f]*) echo "fingerprint : $REF_FINGERPRINT" ;;
  *) REF_FINGERPRINT=""; echo "fingerprint : not computable ($PYTHON missing?) -- indirect measurement only" ;;
esac

# Fingerprint of THIS machine's working tree. It names the most frequent legitimate
# case: during a work in progress, the beta deliberately serves work that is not
# committed yet. Without this comparison, that normal case and a real deployment
# lag both print the same "GAP".
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
  [ -n "$sha" ] && echo "  git_sha        : $sha  (declared by the deployment)"
  echo "  fingerprint    : $served  (measured on the served code)"
  if [ "$served" = "$REF_FINGERPRINT" ]; then
    echo "  ALIGNED: $1 serves exactly the code of $REF."
  elif [ -n "$WT_FINGERPRINT" ] && [ "$served" = "$WT_FINGERPRINT" ]; then
    # Naming this cause changes what the output asks of the reader: nothing to
    # hunt for, only a decision on whether this work should be committed.
    echo "  EXPECTED GAP: $1 serves this machine's UNCOMMITTED work"
    echo "                 (identical to the working tree, different from $REF)."
    echo "                 Normal during a work in progress; commit it to close the gap."
    GAP=1
  else
    echo "  GAP: the served code is neither $REF nor this machine's working tree"
    echo "       (expected $REF_FINGERPRINT). The announced version does not say so."
    GAP=1
  fi
  return 0
}

# --- prod: fallback on the deployment workflow, the only other witness -------
check_prod() {
  echo "== prod =="
  local announced deployed short behind backend_behind
  if ! fetch_health "$PROD_HEALTH"; then
    echo "  CANNOT MEASURE: $PROD_HEALTH did not answer"
    UNMEASURED=1; echo; return
  fi
  announced=$(json_field version)
  [ -n "$announced" ] || { echo "  CANNOT MEASURE: no version field"; UNMEASURED=1; echo; return; }
  echo "  announced      : $announced  ($PROD_HEALTH)"

  if check_fingerprint prod; then echo; return; fi
  echo "  (no served fingerprint: older backend, INDIRECT measurement)"

  if ! command -v gh >/dev/null 2>&1; then
    echo "  CANNOT MEASURE: gh missing, cannot read the deploy.yml runs"
    UNMEASURED=1; echo; return
  fi
  deployed=$(gh run list --workflow=deploy.yml --status=success --limit=1 \
               --json headSha --jq '.[0].headSha' 2>/dev/null)
  case "$deployed" in
    ''|null) echo "  CANNOT MEASURE: no successful deploy.yml run"; UNMEASURED=1; echo; return ;;
    *[!0-9a-f]*) echo "  CANNOT MEASURE: gh returned something other than a SHA"; UNMEASURED=1; echo; return ;;
  esac
  if ! git cat-file -e "${deployed}^{commit}" 2>/dev/null; then
    echo "  CANNOT MEASURE: deployed SHA $deployed unknown locally"
    UNMEASURED=1; echo; return
  fi
  short=$(git rev-parse --short "$deployed")
  echo "  last deploy    : $short -- $(git log -1 --date=short --format='%ad %s' "$deployed")"

  if [ "$deployed" = "$REF_SHA" ]; then
    echo "  ALIGNED (indirect): the last deployment is $REF."
    echo; return
  fi
  if ! git merge-base --is-ancestor "$deployed" "$REF_SHA" 2>/dev/null; then
    echo "  GAP: $short is not an ancestor of $REF."
    GAP=1; echo; return
  fi
  behind=$(git rev-list --count "${deployed}..${REF_SHA}")
  backend_behind=$(git rev-list --count "${deployed}..${REF_SHA}" -- backend/)
  echo "  behind $REF   : $behind commit(s), $backend_behind of them touching backend/"
  if [ "$backend_behind" -eq 0 ]; then
    echo "  ALIGNED (indirect): the $behind undeployed commit(s) do not touch the backend."
  else
    echo "  GAP: $backend_behind undeployed backend commit(s):"
    git log --date=short --format='    %h %ad %s' "${deployed}..${REF_SHA}" -- backend/
    GAP=1
  fi
  echo
}

# --- beta: fallback on ssh fingerprints, nothing else witnesses a `tar | ssh` -
check_beta() {
  echo "== beta =="
  local announced tmp n_repo rc missing extra common joined differing gapcount
  if [ -z "$BETA_HEALTH" ]; then
    echo "  CANNOT MEASURE: beta not measured, TS_BETA_HEALTH is not set"
    UNMEASURED=1; echo; return
  fi
  if ! fetch_health "$BETA_HEALTH"; then
    echo "  CANNOT MEASURE: $BETA_HEALTH did not answer"
    UNMEASURED=1; echo; return
  fi
  announced=$(json_field version)
  [ -n "$announced" ] || { echo "  CANNOT MEASURE: no version field"; UNMEASURED=1; echo; return; }
  echo "  announced      : $announced  ($BETA_HEALTH)"

  if check_fingerprint beta; then echo; return; fi
  echo "  (no served fingerprint: older backend, measurement over ssh)"

  if [ -z "$BETA_HOST" ] || [ -z "$BETA_PATH" ]; then
    echo "  CANNOT MEASURE: TS_BETA_HOST / TS_BETA_PATH not set, no ssh fallback"
    UNMEASURED=1; echo; return
  fi
  if ! ssh -o ConnectTimeout=10 -o BatchMode=yes "$BETA_HOST" true 2>/dev/null; then
    echo "  CANNOT MEASURE: $BETA_HOST unreachable over ssh"
    UNMEASURED=1; echo; return
  fi
  if ! ssh -o ConnectTimeout=10 "$BETA_HOST" "test -d ~/$BETA_PATH/app" 2>/dev/null; then
    echo "  CANNOT MEASURE: ~/$BETA_PATH/app missing on $BETA_HOST"
    UNMEASURED=1; echo; return
  fi
  tmp=$(mktemp -d) || { echo "  CANNOT MEASURE: mktemp"; UNMEASURED=1; echo; return; }

  # On the repository side we read the BLOBS: core.autocrlf=true on this machine puts
  # CRLFs in the working tree that the repository does not have.
  git ls-tree -r --name-only "$REF_SHA" -- backend/app \
    | grep '\.py$' | sed 's|^backend/||' | sort > "$tmp/list"
  n_repo=$(wc -l < "$tmp/list")
  if [ "$n_repo" -le 50 ]; then
    echo "  CANNOT MEASURE: $n_repo .py file(s) -- suspicious read"
    UNMEASURED=1; rm -rf "$tmp"; echo; return
  fi
  while IFS= read -r f; do
    printf '%s  %s\n' "$(git show "$REF_SHA:backend/$f" | sha256sum | cut -c1-64)" "$f"
  done < "$tmp/list" | sort -k2 > "$tmp/repo"

  ssh -o ConnectTimeout=15 "$BETA_HOST" "cd ~/$BETA_PATH && find app -name '*.py' -not -path '*__pycache__*' | sort | while IFS= read -r f; do printf '%s  %s\n' \"\$(tr -d '\r' < \"\$f\" | sha256sum | cut -c1-64)\" \"\$f\"; done" \
    > "$tmp/beta" 2>/dev/null
  rc=$?
  if [ "$rc" -ne 0 ] || [ ! -s "$tmp/beta" ]; then
    echo "  CANNOT MEASURE: fingerprint listing failed (code $rc)"
    UNMEASURED=1; rm -rf "$tmp"; echo; return
  fi
  sort -k2 -o "$tmp/beta" "$tmp/beta"

  cut -d' ' -f3- "$tmp/repo" > "$tmp/n-repo"
  cut -d' ' -f3- "$tmp/beta" > "$tmp/n-beta"
  missing=$(comm -23 "$tmp/n-repo" "$tmp/n-beta")
  extra=$(comm -13 "$tmp/n-repo" "$tmp/n-beta")

  # A `join` that matches nothing reports "no difference" -- green, for the worst
  # reason. We require the denominator before believing the zero.
  common=$(comm -12 "$tmp/n-repo" "$tmp/n-beta" | grep -c '[^[:space:]]')
  join -j 2 -o 0,1.1,2.1 "$tmp/repo" "$tmp/beta" > "$tmp/joined" 2>/dev/null
  joined=$(grep -c '[^[:space:]]' "$tmp/joined")
  if [ "$joined" -ne "$common" ]; then
    echo "  CANNOT MEASURE: join matched $joined line(s) for $common common file(s)"
    UNMEASURED=1; rm -rf "$tmp"; echo; return
  fi
  differing=$(awk '$2!=$3{print $1}' "$tmp/joined")
  gapcount=$(printf '%s\n%s\n%s\n' "$missing" "$extra" "$differing" | grep -c '[^[:space:]]')

  echo "  .py files      : $n_repo in $REF, $(wc -l < "$tmp/beta") on beta, $common compared"
  if [ "$gapcount" -eq 0 ]; then
    echo "  ALIGNED: beta serves the code of $REF."
  else
    echo "  GAP: $gapcount file(s) differ from $REF"
    if [ -n "$missing" ];   then echo "    missing on beta   :"; echo "$missing"   | sed 's/^/      /'; fi
    if [ -n "$extra" ];     then echo "    extra on beta     :"; echo "$extra"     | sed 's/^/      /'; fi
    if [ -n "$differing" ]; then echo "    content differs   :"; echo "$differing" | sed 's/^/      /'; fi
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
  echo "VERDICT: at least one environment could not be measured -- not a green light."
  exit 2
fi
if [ "$GAP" -eq 1 ]; then
  echo "VERDICT: an environment serves code different from $REF without its version saying so."
  exit 1
fi
echo "VERDICT: every measured environment serves $REF."
exit 0
