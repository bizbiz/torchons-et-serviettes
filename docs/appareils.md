# Devices: see your sessions, cut one off

French version: [`appareils.fr.md`](appareils.fr.md).

The T&S session token **never expires**, and that is a choice rather than an oversight: a
household chores app that asks for a code every week costs everyone a daily annoyance against
a benefit that almost never materialises. This document describes what makes that token
**revocable** anyway, one device at a time.

## The problem, measured

Two facts, established on 2026-09-02, that reading the code from a distance does not show.

**The token is eternal and irrevocable.** `backend/app/auth.py` mints it with
`{"sub": user_id, "device_id": device_id}` — no `exp` claim — and the server holds no
revocation list, no `token_version`, no device registry. The only available gesture was
rotating `jwt_secret`, which signs **the whole household out at once**, children and partner
included. The day a phone is lost or sold, that was not a choice, it was a bad surprise.

**Android's backup copies the identity.** `allowBackup` is on (a deliberate decision, see the
comment above `<application>` in `AndroidManifest.xml`). Measured: `bmgr backupnow` ships
812 KB, and after a full uninstall/reinstall **the session comes back with no sign-in**. The
`device_id` lived in `torchons_prefs` back then, so it travelled along — two handsets from
one backup carried the same identity, and telling them apart was impossible.

The code comment read "UUID stable per installation". True before Auto Backup, false since,
with nothing to report it.

## What was built

### Server side

| Piece | Role |
|---|---|
| `backend/app/models/device.py` | one row per (user, device), with `revoked_at` |
| `backend/app/utils/devices.py` | resolution on every call: refuse if revoked, adopt otherwise |
| `backend/app/api/devices.py` | list, register, revoke, sign out |
| `backend/app/api/recovery.py` | regenerating the code also cuts the other devices |
| `backend/app/utils/errors.py` | machine `code` in the error body, next to `detail` |

### App side

| Piece | Role |
|---|---|
| `res/xml/data_extraction_rules.xml` | excludes the identity from backup (API 31+) |
| `res/xml/full_backup_content.xml` | the same exclusion for API 26-30 |
| `data/local/DeviceIdentityStore.kt` | the identity, in its own excluded DataStore |
| `core/network/DeviceRegistrar.kt` | declares it at startup, handles the transition |
| `core/network/DeviceRevocationInterceptor.kt` | spots `DEVICE_REVOKED` inside a 401 |
| `core/network/DeviceRevocationMonitor.kt` | wipes Room, preferences and identity |
| `ui/devices/DevicesScreen.kt` | the screen, reached from Household → My devices |

## The six decisions, and what settled them

**1. We revoke a DEVICE, and its identity leaves the backup.** That is the only exclusion:
token, preferences, home WiFi and the Room database keep being restored. Without it, two
handsets share one identity and cutting either cuts both.

**2. Everyone sees only THEIR devices.** `UserFoyer.role` (`admin`/`member`) exists in the
schema but is consulted nowhere in the product. Letting one member sign another one out would
be the app's first asymmetric power; device revocation is not the feature that should
introduce it.

**3. Revoking also erases local data.** The app is offline-first: cutting server access
leaves **seven Room tables** readable — tasks, completions, history, contexts, household
members, snapshots, pending queue. `clearAuthData()` alone does not touch them. The wipe
triggers on the machine code `DEVICE_REVOKED`, **never** on an ordinary 401: an expired token
or an auth outage must erase nothing.

**4. The name does not discriminate, the dates do.** Measured on a Pixel 7: `Build.MODEL`,
`settings global device_name` and `bluetooth_name` all three return **"Pixel 7"**. The screen
therefore leans on the server-provided "this device" marker, then on the **added** date — at
the exact moment you revoke an old handset both were just seen, but only one was just added.

**5. Regenerating the code cuts the other devices.** A regenerated code means "the old one
may have leaked". Closing the code's door without closing the ones already open protects
nothing, since an existing session no longer depends on the code. The device that triggers it
stays in: otherwise you lock yourself out by clicking the button meant to protect you.

**6. The post-restore transition is SILENT.** This is the decision that got corrected along
the way. It seemed natural for a restored handset to ask for the code again — it is not: it
holds the restored token, which is valid and proves identity on its own. The server therefore
records the new identity and rebinds the session to it, asking nothing. Telling devices apart
costs **no user action at all**.

## How it plays out

**Changing phone.** The new one restores everything except the identity. At startup,
`DeviceRegistrar` declares a fresh identity; the server sees a valid token carrying an unknown
identity, registers the new device and returns a token of its own. The user sees nothing. Both
handsets appear separately in the list.

**Handing your old phone over.** Settings → Household → My devices, "Cut off access" on the
old one. It is signed out, and its local data is erased on its next connection.

**Recovery code leaked.** Regenerate it: the new one is shown once, and every other device is
cut in the same gesture.

## What this does NOT protect — stated, not hidden

**A device that never reconnects keeps its cache.** No app can wipe an offline phone. The
screen says so at confirmation time rather than implying a remote wipe.

**Push notifications survive revocation.** `notification_tokens` carries no `device_id` —
nothing links an FCM token to a device. A revoked phone therefore keeps receiving household
notifications. Closing that path needs a column on `notification_tokens` and a delete on
revocation; it is in `todo.md`.

**Tokens older than v0.7.0 are not revocable.** They carry no `device_id` claim, so no row
matches them. Wiring them would create one row per request, since the old code minted a fresh
UUID on every call. The remedy, should it ever matter, remains rotating `jwt_secret`.

These three paths are enumerated in the header of `backend/tests/test_devices.py`, together
with the covered ones — the inventory lives next to the tests, not only here.

## Traps for whoever touches this next

**Never move `device_id` back into `torchons_prefs`.** It would be backed up again, and the
failure is **silent**: the app works perfectly, the list simply shows one device where there
are two.

**The two rule files are edited together.** `minSdk` is 26: declaring only one leaves half the
fleet on the default behaviour, with no signal whatsoever.

**Adoption applies to an UNKNOWN identity, never a REVOKED one.** This is the load-bearing
point. If `/devices/register` adopted a fresh identity presented with a revoked token, a cut
phone would re-issue itself access and revocation would be decorative. The test
`test_revoked_device_cannot_adopt_new_identity` guards exactly that.

**`resolve_device` must commit.** It runs inside an authentication dependency, before the
route body: without `commit` the row dies with the transaction and the device is re-adopted on
every call, hence never revocable. Measured: the three revocation tests failed with `404`
before that line existed.

**A test calling `/recovery/regenerate` revokes the whole account.** The suite shares a single
`TEST_USER_ID`: without the cleanup in `test_devices.py`, one test puts 17 `test_sync` tests
into 401. Measured: 60 rows revoked, 17 reds elsewhere.

## Checking

```bash
cd backend && ./.venv/Scripts/python.exe -m pytest tests/test_devices.py -q   # 9 cases
python ~/.tools/skills/prove-the-test/cn.py --sujet devices --cible devices  # baseline
```

The negative control was played: disabling the refusal of revoked devices turns **4 tests red
— exactly the 4 that test that rule**, while the other 5 stay green.
