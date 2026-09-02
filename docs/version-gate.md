# Version gate — taking an APK version out of circulation

French version: [`version-gate.fr.md`](version-gate.fr.md).

The server can refuse to serve data routes to an APK that is too old. The app
then shows a terminal "Update required" screen and does nothing else.

This document says **what the mechanism protects**, **what it does not**, and the
**activation procedure** — whose step order is the only thing separating a clean
withdrawal from a household locked out with no way back.

## What it protects, and what it does not

It takes **a buggy version out of circulation among well-meaning users**. That is
the real risk for this app, and the mechanism answers it well.

It does **not** protect against someone who controls the APK. The client declares
its own version: it can lie, or strip the check. Blocking on the app side is
ergonomics — it says "update"; the barrier is the server refusal, and that too
rests on a client declaration.

Against **leaked secrets** (stolen tokens), the useful move is revoking tokens,
not raising a version threshold. Keep the two mechanisms distinct: they answer
two different threats.

## How it works

| Piece | Role |
|---|---|
| `MIN_APP_VERSION_CODE` (`.env`) | the threshold. `0` = disabled, the default |
| `MIN_APP_DOWNLOAD_URL` (`.env`) | where to get the APK, shown on the block screen |
| `backend/app/utils/version_gate.py` | middleware: reads `X-App-Version-Code`, answers `426` |
| `AppVersionInterceptor.kt` | sends the header, reads the `426` |
| `VersionGateMonitor.kt` | holds the verdict, persists it, clears it on update |
| `AppUpdateRequiredScreen.kt` | the terminal screen |

`versionCode` is compared, never `versionName`: it is a monotonic integer,
whereas `"0.9.0"` sorts above `"0.10.0"` as a string.

Each channel already has its own backend (prod on Scaleway, beta on <beta-host>), so
the threshold is per-deployment and no channel needs naming in the header.

### Three decisions that are not details

**A request WITHOUT the header passes.** Every APK built before this mechanism
sends nothing. Refusing them would lock every phone in the household out at the
very moment of deployment, before a fixed APK exists. The hole closes on its own:
once every version in circulation sends the header, the threshold suffices to
fence them out by number.

**The threshold is never derived from `APP_VERSION`.** That value is the first
`## vX.Y.Z` line of a changelog copied by hand between machines, and it has
already lied in both directions (see `scripts/deployed_versions.sh`). A threshold
resting on it would inherit the lie — except here the lie locks phones out.

**Offline: we do not block on the absence of an answer, we block on a refusal
already received.** As long as no `426` has arrived, the app works, network or
not — blocking on silence would kill an offline-first app in the underground.
Once the `426` is received the verdict is persisted and applies even offline,
otherwise airplane mode would be enough to walk around the gate. The verdict
clears itself when the installed `versionCode` catches up with the remembered
threshold.

## Turning a threshold on — the order matters

1. **Build and distribute the fixed APK first.** A threshold is raised on a
   version that already exists and that the household can install.
2. **Set `MIN_APP_DOWNLOAD_URL`** to the real address. A threshold raised without
   one leaves the screen saying "ask whoever administers the home", which is
   honest but useless at 10pm.
3. **Set `MIN_APP_VERSION_CODE`** to the `versionCode` of the oldest build still
   accepted — it is an inclusive threshold: that value passes, the one below does
   not.
4. **Restart the backend**, the value being read at startup.
5. **Verify before believing it**:

```bash
# must return the threshold just set
curl -s https://api.torchonsetserviettes.app/api/health

# a client below the threshold must get 426
curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'X-App-Version-Code: 1' https://api.torchonsetserviettes.app/api/tasks

# and /api/health must STAY reachable for that same client, otherwise a badly
# chosen threshold becomes a silent outage nobody can diagnose
curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'X-App-Version-Code: 1' https://api.torchonsetserviettes.app/api/health
```

Measured on beta 2026-09-02 with the threshold at 999: client v95 on `/api/tasks`
→ `426` with the full body; client v999 → `401` (passes the gate, refused by
auth); no header → `401` (let through); client v95 on `/api/health` → `200`.

## Getting unblocked

The threshold is the only thing to fix, and it lives on the server: set
`MIN_APP_VERSION_CODE=0` back in `.env`, restart. Apps unblock on their next
successful call… **except those that already persisted the verdict** — their
screen only clears by installing an APK whose `versionCode` reaches the
remembered threshold. That is deliberate (otherwise airplane mode would bypass
the gate), and it is exactly why step 1 above is not negotiable: **a threshold is
only ever raised on a version that can already be distributed.**

## Proven end to end — 2026-09-02

The Android half was exercised on a Pixel 7, beta `debug` APK v95, with the beta
threshold set to 96 and then removed. All four client pieces ran for the first
time since they shipped: the header goes out (`X-App-Version-Code: 95` in
`logcat`), the `426` comes back (`<-- 426 Upgrade Required` on
`/api/auth/auto`), the screen renders and names both versions, and the verdict
survives a restart with no network.

**A blocked app stops issuing ANY request.** That is stronger than "its screen
does not clear": it no longer talks to the server at all, so nothing changed
there can reach it. Verified by setting the threshold back to `0` — the server
answered `401` again to a v95 client, and the app stayed on its terminal screen
with zero requests emitted. What this measurement adds to the paragraph above:
no server-side fix, whatever it is, can serve as a rescue.

Automatic clearing does work: with a `versionCode` 96 APK installed on top, the
app starts normally again and announces `X-App-Version-Code: 96`.

Two details for whoever replays this:

- `/api/auth/auto` is not exempt — the gate fires on the very first call at
  startup, with no need to navigate inside the app.
- The first frame an already-blocked app renders is the gate screen, never the
  normal UI: the system splash stays up for ~1.1 s, which comfortably covers
  `VersionGateMonitor.restore()` re-reading the verdict. Measured with a burst
  of screenshots, ~420 ms resolution — a briefer flash is not ruled out.

This was run on a `debug` build, and `isMinifyEnabled` is `false` in `release`
too, so R8 cannot account for any difference. Once R8 is enabled, replay this
check — obfuscation touches the classes behind JSON body parsing and
persistence.
