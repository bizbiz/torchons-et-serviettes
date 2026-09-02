"""Tests for the running-code fingerprint (app/utils/code_fingerprint.py).

Invariant under test: **the fingerprint identifies the code being executed** --
equal fingerprints mean equal code, different fingerprints mean different code.

Paths by which that invariant can be violated, enumerated from the function's
own steps (walk, sort, normalise, hash) before any case was written:

  1. line endings change the fingerprint          -> covered. THE dangerous one:
     the dev workstation has core.autocrlf=true, so repo blobs (LF) and working
     tree (CRLF) hold the same code. A fingerprint sensitive to CR would report
     every environment as divergent, always -- a comparator that is wrong 100%
     of the time and looks like a real finding.
  2. build residue counted (__pycache__)          -> covered; would make the
     fingerprint drift between two boots of the SAME code.
  3. filesystem walk order leaks in               -> covered by sorting; an
     unsorted rglob would make it machine-dependent.
  4. content hashed without the PATH              -> covered: moving code from
     one file to another must change the fingerprint.
  5. adding or removing a file goes unnoticed     -> covered.
  6. an unreadable tree raises                    -> covered: /api/health must
     not die because it failed to read its own source.
  7. the repo-side comparator drifts from this
     implementation                               -> NOT covered by a case, and
     removed by construction: scripts/fingerprint_of_ref.py IMPORTS this
     function instead of reimplementing it.
  8. two different codes collide on 16 hex chars  -> NOT covered: 64 bits of
     sha256 prefix. Accepted, this guards against accident, not forgery.
"""

from __future__ import annotations

from pathlib import Path

from app.utils.code_fingerprint import compute_fingerprint


def _write(root: Path, rel: str, content: bytes) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(content)


def test_line_endings_do_not_change_the_fingerprint(tmp_path):
    """Path 1. Same code, two checkouts -- one fingerprint."""
    lf = tmp_path / "lf"
    crlf = tmp_path / "crlf"
    body = b"def f():\n    return 1\n"
    _write(lf, "a.py", body)
    _write(crlf, "a.py", body.replace(b"\n", b"\r\n"))
    assert compute_fingerprint(lf) == compute_fingerprint(crlf)


def test_pycache_is_ignored(tmp_path):
    """Path 2. Otherwise the same code fingerprints differently after a run."""
    root = tmp_path / "app"
    _write(root, "a.py", b"x = 1\n")
    before = compute_fingerprint(root)
    _write(root, "__pycache__/a.cpython-311.py", b"garbage\n")
    assert compute_fingerprint(root) == before


def test_changed_content_changes_the_fingerprint(tmp_path):
    """The invariant itself, in the direction that matters for detection."""
    root = tmp_path / "app"
    _write(root, "a.py", b"x = 1\n")
    before = compute_fingerprint(root)
    _write(root, "a.py", b"x = 2\n")
    assert compute_fingerprint(root) != before


def test_moving_code_between_files_changes_the_fingerprint(tmp_path):
    """Path 4. The path is hashed, not just the contents."""
    one = tmp_path / "one"
    two = tmp_path / "two"
    _write(one, "a.py", b"x = 1\n")
    _write(two, "b.py", b"x = 1\n")
    assert compute_fingerprint(one) != compute_fingerprint(two)


def test_adding_a_file_changes_the_fingerprint(tmp_path):
    """Path 5. The 2026-09-01 beta gap was exactly one missing file."""
    root = tmp_path / "app"
    _write(root, "a.py", b"x = 1\n")
    before = compute_fingerprint(root)
    _write(root, "b.py", b"y = 2\n")
    assert compute_fingerprint(root) != before


def test_same_tree_twice_gives_the_same_value(tmp_path):
    """Path 3. Stability is what makes it comparable across machines."""
    root = tmp_path / "app"
    _write(root, "b.py", b"b = 1\n")
    _write(root, "a.py", b"a = 1\n")
    _write(root, "sub/c.py", b"c = 1\n")
    assert compute_fingerprint(root) == compute_fingerprint(root)


def test_an_empty_or_missing_tree_says_unknown_instead_of_raising(tmp_path):
    """Path 6. A diagnostic endpoint must never be the thing that breaks.

    "unknown" is also the right answer semantically: it is not a fingerprint
    that happens to match nothing, it is the absence of a measurement -- and the
    comparator must treat it as such rather than as a mismatch.
    """
    assert compute_fingerprint(tmp_path / "does-not-exist") == "unknown"
    (tmp_path / "empty").mkdir()
    assert compute_fingerprint(tmp_path / "empty") == "unknown"


def test_health_exposes_the_fingerprint_of_the_running_code():
    """Wiring: computed correctly and never surfaced is the same as absent."""
    from app.utils.code_fingerprint import CODE_FINGERPRINT

    assert CODE_FINGERPRINT != "unknown", "the backend cannot read its own source"
    assert len(CODE_FINGERPRINT) == 16
