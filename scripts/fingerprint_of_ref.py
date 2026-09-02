"""Compute the code fingerprint of a git ref, as the backend would report it.

Usage:  python scripts/fingerprint_of_ref.py [ref]        (default: origin/main)

Deliberately imports `compute_fingerprint` from the backend instead of
reimplementing it. A comparator that reimplements the thing it compares is
comparing two implementations, not two states -- and the day one of them changes
its normalisation, the mismatch reads as a deployment gap that does not exist.

Files are materialised from the ref's BLOBS, never from the working tree: the
dev workstation checks out with core.autocrlf=true, so its files carry CRLF the
repository does not.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile

RACINE = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RACINE / "backend"))

from app.utils.code_fingerprint import compute_fingerprint  # noqa: E402


def _run(args: list[str]) -> bytes:
    out = subprocess.run(args, cwd=RACINE, stdout=subprocess.PIPE, check=True)
    return out.stdout


def fingerprint_of(ref: str) -> str:
    listing = _run(
        ["git", "ls-tree", "-r", "--name-only", ref, "--", "backend/app"]
    ).decode("utf-8").splitlines()
    paths = [p for p in listing if p.endswith(".py")]
    if not paths:
        raise SystemExit("no .py file under %s:backend/app -- wrong ref?" % ref)

    with tempfile.TemporaryDirectory() as tmp:
        base = pathlib.Path(tmp) / "app"
        for path in paths:
            blob = _run(["git", "show", "%s:%s" % (ref, path)])
            rel = path[len("backend/app/"):]
            dest = base / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(blob)
        return compute_fingerprint(base)


if __name__ == "__main__":
    ref = sys.argv[1] if len(sys.argv) > 1 else "origin/main"
    print(fingerprint_of(ref))
