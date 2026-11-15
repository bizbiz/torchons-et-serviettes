# Hosting T&S: knowing which code is running

French version: [`self-hosting.fr.md`](self-hosting.fr.md).

This document is for anyone running their own T&S server. It does not describe a particular
deployment: it says **how to verify which code a server executes**, which pitfalls have been
measured, and how to read a crash trace from the minified APK. Client/server ordering is in
[`architecture.md`](architecture.md) § Sync ordering; enabling the version gate is in
[`version-gate.md`](version-gate.md).

## Verifying: why `version` is not enough

The `version` field of `/api/health` is the first `## vX.Y.Z` line of a changelog that is
**copied by hand** between machines. It says what someone meant to ship, never what runs, and
it has lied in both directions:

- **2026-08-20**: a server announced `0.36.1` while running recent code. The gap showed.
- **2026-09-01**: a server announced `0.38.0`, the exact version of `origin/main`, while
  running code twelve days old, missing two fixes. **Nothing showed.** That direction is the
  dangerous one.

Hence `code_fingerprint`: a sha256 over the `.py` files actually executed, computed at startup
(`backend/app/utils/code_fingerprint.py`) and recomputable from any git ref by
`scripts/fingerprint_of_ref.py` (`python scripts/fingerprint_of_ref.py [ref]`, default
`origin/main`). The script imports `compute_fingerprint` from the backend instead of
reimplementing it, and reads the ref's blobs, never the working tree (line endings). It is the
only `/api/health` field that cannot be copied by mistake.

`git_sha` is declarative: it comes from the `GIT_SHA` environment variable at startup and stays
empty if the deployment does not provide one. For a transport with no commit to declare (an
archive sent from a workstation), the fingerprint is then the only measurement.

### `scripts/deployed_versions.sh`

```bash
bash scripts/deployed_versions.sh          # or `prod` / `beta`
```

For each environment it compares the served fingerprint (`/api/health`) to the one of the
reference. It is configured through `TS_*` environment variables, which may also be set in
`deployed_versions.local.env` at the repo root (git-ignored) or in the file named by
`TS_ENV_FILE`. A variable already set in the environment wins.

| Variable | Role |
|---|---|
| `TS_DEPLOY_REF` | reference compared against (default `origin/main`) |
| `TS_PROD_HEALTH` | health URL of production; point it at your own server |
| `TS_BETA_HEALTH` | health URL of a second environment; without it, it is not measured |
| `TS_BETA_HOST` | ssh host of that environment, for the indirect fallback only |
| `TS_BETA_PATH` | backend directory on that host, relative to `$HOME` (idem) |
| `TS_PYTHON` | Python interpreter used to recompute the fingerprint |

The script exits `0` if everything is aligned, `1` on a gap, `2` if an environment could not be
measured. **A `2` is never read as a green light.** When the served fingerprint is that of the
uncommitted work of the machine running the script, it answers "EXPECTED GAP": the normal
regime during a work in progress, not a defect.

**The fingerprint does not cover migrations.** `backend/alembic/` lives outside `app/`, and
startup runs `alembic upgrade head`: a deployment that brings **only a migration** leaves
`code_fingerprint` unchanged, and the script would answer "ALIGNED" while the schema differs
(measured on 2026-09-02: 46 migrations and `env.py` outside the perimeter). The schema witness
is elsewhere: `SELECT version_num FROM alembic_version`.

## Measured pitfalls

**Alembic reads `DATABASE_URL_SYNC`, not `DATABASE_URL`** (`backend/app/config.py`). Overriding
the wrong variable runs the migration against the default database **without saying anything**:
measured on 2026-08-20, complete silence, revision unchanged. Re-read
`SELECT version_num FROM alembic_version` rather than conclude from the absence of an error.

**A migration rehearsal runs on a throwaway database restored from a dump**, never on an
environment whose data matters.

**A successful `docker compose up` does not prove the new code is served**: the container may
restart on its previous image. Compare fingerprints rather than stop at compose's exit code.

**A compose file change does not show in the fingerprint**, which covers only `.py` files. If
the transport sends only code, copy the compose file separately, keeping the old one, before
rebuilding; otherwise everything is green (rebuild succeeded, identical fingerprint) and the
container runs with the old configuration (lived on 2026-09-21 with `init: true`).

**Wait for health, never sleep a fixed time.** Startup chains migrations and then uvicorn; a
loop without a delay burns its attempts in a second while the container starts (measured on
2026-09-02: 60 attempts consumed before the port was listening).

## The APK is minified: a crash trace is not readable as is

R8 is active in `release` since 2026-09-03 (`android/app/build.gradle.kts`). The APK shrinks
from 37.41 to 25.13 MB, and **every class is renamed**: a trace coming from a phone is
unreadable without the mapping file of the EXACT build that produced that APK.

That file does not survive on its own: R8 writes it under `build/`, which a clean removes, and
it weighs ~72 MB. The Gradle task `archiveR8Mapping` therefore copies it after each release
build:

    android/mapping-archive/<variant>-v<versionCode>-<versionName>-mapping.txt

To deobfuscate a trace: `retrace` is not in the Android SDK (neither `tools/proguard/` nor
`cmdline-tools/`); it ships with Android Studio. Verified on 2026-09-03 (Retrace 9.2.7):

    JBR="<android-studio>/jbr"
    R8="<android-studio>/plugins/android/lib/r8.jar"
    M=android/mapping-archive/<variant>-v<versionCode>-<versionName>-mapping.txt
    "$JBR/bin/java" -cp "$R8" com.android.tools.r8.retrace.Retrace "$M" trace.txt

The mapping is that of the INSTALLED build: read its `versionCode` with
`adb shell dumpsys package <applicationId> | grep versionCode`. If no `java` is on the PATH,
use Android Studio's JDK; a Gradle build launched from the shell likewise needs
`JAVA_HOME="$JBR"`, otherwise it exits with 49.

Two limits to know before you need it. The folder is **gitignored** and lives on the build
machine only: a reinstall loses it, and with it the readability of crashes from APKs already
distributed. And **rebuilding the same `versionCode` overwrites the previous archive**: if one
version is rebuilt several times, only the last mapping survives, while a phone may still carry
an earlier build.
