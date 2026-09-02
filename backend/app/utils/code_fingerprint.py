"""A fingerprint of the Python code this process is actually running.

Why this exists
---------------
`/api/health` used to answer `version`, read from the first `## vX.Y.Z` line of
`app/content/changelog.md`. That string is COPIED by hand between machines, so it
describes an intention, never the running code. It has lied in both directions:

  2026-08-20  beta announced 0.36.1 while running recent code -- the gap SHOWED.
  2026-09-01  beta announced 0.38.0, the exact version of origin/main, while
              running code from twelve days earlier, missing two fixes. Nothing
              showed. That direction is the dangerous one.

A git SHA injected at build time would be an improvement, but it is still a
DECLARATION: it says where the code was meant to come from. This is a
MEASUREMENT of what is in the files being executed, and its decisive property is
that it can be recomputed from the repository by anyone, over plain HTTP, with
no ssh and no deployment metadata -- see `scripts/deployed_versions.sh`.

Definition (must match the repo-side computation exactly)
---------------------------------------------------------
sha256 over the sorted list of `app/**/*.py`, each entry contributing its POSIX
relative path, a NUL, and the sha256 of its content with CR bytes stripped.

- CR stripped: the dev workstation checks out with `core.autocrlf=true`, so its
  working tree carries CRLF the repository does not. Normalising here means the
  fingerprint describes the CODE, not the checkout that produced it.
- `.py` only: the fingerprint answers "which code runs", and content files
  (catalog.yaml, changelog.md) change on their own schedule. `version` already
  covers the changelog.
- `__pycache__` excluded: build residue, not source.
"""

from __future__ import annotations

import hashlib
import os
from pathlib import Path

# app/ -- the package root, i.e. the parent of utils/
_APP_DIR = Path(__file__).resolve().parent.parent


def compute_fingerprint(root: Path | None = None) -> str:
    """Return the short fingerprint, or "unknown" if it cannot be computed.

    Never raises: a health endpoint that dies because it could not read its own
    source would turn a diagnostic aid into an outage.
    """
    base = root or _APP_DIR
    try:
        files = sorted(
            p for p in base.rglob("*.py") if "__pycache__" not in p.parts
        )
        if not files:
            return "unknown"
        digest = hashlib.sha256()
        for path in files:
            rel = path.relative_to(base).as_posix()
            content = path.read_bytes().replace(b"\r", b"")
            digest.update(rel.encode("utf-8"))
            digest.update(b"\0")
            digest.update(hashlib.sha256(content).hexdigest().encode("ascii"))
            digest.update(b"\n")
        return digest.hexdigest()[:16]
    except OSError:
        return "unknown"


# Computed once at import: the files cannot change under a running process in
# any way we could serve, and re-reading ~90 files per health call would make a
# monitoring endpoint do pointless I/O.
CODE_FINGERPRINT = compute_fingerprint()

# Optional, and deliberately secondary: where the code was MEANT to come from.
# Set by the deployment (GitHub Actions passes github.sha). Empty when the code
# was pushed by hand -- which is itself the useful signal, since only the
# fingerprint can then say what is running.
GIT_SHA = os.environ.get("GIT_SHA", "")
