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
| `backend/app/api/notifications.py` | binds the FCM token to the device, from the JWT |

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

## The seven decisions, and what settled them

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

**7. Revoking also cuts push notifications** *(added 2026-09-03)*. Without it, a phone whose
access had been cut kept announcing household tasks — the API was closed, that channel stayed
open. `notification_tokens` now carries a `device_id`, filled **from the JWT** rather than
from the request body: a client cannot lie about it, and above all already-installed APKs are
covered without waiting for an app update. Rows created before that column have a null
`device_id` and re-attach themselves on the next FCM registration.

**8. Leaving the household or deleting the account revokes too** *(added 2026-10-05)*.
`get_current_user` rechecks on every call that the user exists and is an ACTIVE member of the
token's household (`user_foyers.left_at` null). Before, a token with no `exp` outlived a
departure or a `DELETE /me`: read and write access kept, with no limit. The refusal is
`DEVICE_REVOKED`, not an ordinary 401, because it is the only one the app handles by wiping
itself: already-installed APKs fall back to the welcome screen instead of staying stuck on
errors. The phone that leaves is not affected, `/leave` hands it a token on its other
household; ANOTHER phone of the same user, left on the departed household, is revoked, and
"Find my household" brings it back on the remaining one. Its offline queue is lost: a
deliberate choice, the case is rare in a two-person household. Decided 2026-10-12: no
"remove a member" for now, leaving voluntarily is the only way out.

## How it plays out

**Changing phone.** The new one restores everything except the identity. At startup,
`DeviceRegistrar` declares a fresh identity; the server sees a valid token carrying an unknown
identity, registers the new device and returns a token of its own. The user sees nothing. Both
handsets appear separately in the list.

**Handing your old phone over.** Settings → Household → My devices, "Cut off access" on the
old one. It is signed out, and its local data is erased on its next connection.

**Recovery code leaked.** Regenerate it: the new one is shown once, and every other device is
cut in the same gesture.

**Leaving one's last household, or deleting one's account: 30 days to come back** (decided on
2026-11-02, the same rule for both).
- **On leaving**: the member is removed from shared tasks. Their solo tasks are deactivated but
  stay theirs.
- **For 30 days**: "Recover my household" with their code brings them back into the household
  they left, with their tasks. The others see their name struck through.
- **After that**: the nightly cleanup anonymizes the account instead of deleting it. The name
  becomes "Ancien membre" and the code is erased, so no return is possible any more. Their
  personal data and solo tasks are deleted. Their completions stay in the household history.

Details are in `backend/app/services/member_departure.py` and `task_departure.py`.
Anonymization replaces a purge that failed on any account that had acted: six foreign keys to
`users` had no `ON DELETE` rule.

## Troubleshooting: a member lost access to their account, with no code

⚠️ **This procedure should never be needed.** It writes to the production database outside a
migration, which the rule forbids (the prod database changes only through a deployed migration):
every use is an exception, granted by the maintainer for that
one case. If it is needed, the product left a member with no way back — the two leads that
would avoid it are on the roadmap (`backend/app/content/roadmap.md`, "Plus tard"), not on
this page.

The case, lived on 2026-10-18: an update refused by Android, the app uninstalled then
reinstalled without its session, no recovery code written down. The member joined the
household through a "Join" QR code and landed on a **new**, empty account; their original
account, with its whole history, was intact in the database. So the old account is given a
new code instead of merging the two: a merge would rewrite some twenty tables pointing at
`users.id`, several of them keyed on (user, task).

1. **Read** (read-only) the household members and each one's activity, to identify the old
   account and check that the duplicate produced nothing.
2. **Dump**: prod database, before any write (the maintainer's own backup script, not in the repo).
3. **Regenerate** the old account's code with a script run once inside `ts_api_prod`, doing
   the same writes as `/recovery/regenerate` (`generate_code`, `hash_code`, `lookup_key`,
   `revoke_other_devices`), checking the account's identity before writing and re-reading the
   hash after. The code is shown in the terminal, never in a file, a commit or an Actions log.
   Delete the script from the container and the host afterwards.
4. **On the phone**: Settings → Household → "Leave household" on the duplicate account, then
   "Recover my household" with the code. The confirmation screen must show the old account's
   completion count, not zero.
5. The member **immediately regenerates** their code from the app and writes it down: the one
   from step 3 went through a terminal.

## What this does NOT protect — stated, not hidden

**A device that never reconnects keeps its cache.** No app can wipe an offline phone. The
screen says so at confirmation time rather than implying a remote wipe.

**Tokens older than v0.7.0 are not revocable.** They carry no `device_id` claim, so no row
matches them. Wiring them would create one row per request, since the old code minted a fresh
UUID on every call. The remedy, should it ever matter, remains rotating `jwt_secret`.

**Rotating `jwt_secret` signs everyone out, on purpose.** Since `8ce7850`, a token whose
signature does not verify returns `DEVICE_REVOKED` (`auth.py::decode_token`). Every phone wipes
itself and returns to the welcome screen, instead of sitting on ordinary 401s.

- **Done once, on 2026-11-02 in prod.** The old secret was readable in commit `5d45e42c`, which
  GitHub still serves, and it was shared with dev.
- **Before rotating**: every member must have noted their recovery code. They are all signed
  out at once, so none of them can bring another back by QR.
- **How it is done**:
  - The value is generated on the server and never leaves it. Only fingerprints are compared.
  - The `api` container is recreated with `--force-recreate`. Beware: recreated this way, it
    loses `git_sha` in `/api/health` until the next `deploy.yml`.
- **Proof that the rotation took**: a token signed with the old secret and carrying a `scope`
  claim goes from "ordinary 401" (valid signature, then refused by `get_current_user`) to
  `DEVICE_REVOKED`.
- **What the rotation costs**: offline actions not yet sent are lost. The screens do not follow
  at once; see the next paragraph.

These two paths are enumerated in the header of `backend/tests/test_devices.py`, together
with the covered ones — the inventory lives next to the tests, not only here.

**The screen does not follow the wipe (seen on 2026-11-02, a "Later" item of the roadmap).**
When prod's `JWT_SECRET` was rotated, Quentin's phone did not go back to the welcome screen. The
open app showed "server unreachable" errors and stayed there. Only after killing and relaunching
it did the phone land on the welcome screen, then "Recover my household".

- **Cause, read in the code**: `DeviceRevocationMonitor.revoked` is a `StateFlow` whose KDoc
  says it sends the UI back to onboarding, but no screen collects it. Only
  `versionGateMonitor.blocked` is collected, in `MainActivity.setContent`. The wipe does run
  (Room tables, token, identity), but the UI keeps its state. At the next launch there is no
  token any more, so the app opens onboarding.
- **Not checked**: why the message talks about an unreachable server rather than a cut access.
  One hypothesis is that calls go out with no token once it is wiped, and their error falls into
  the generic message. Confirm before fixing.
- **Planned remedy**: collect `revoked` in `MainActivity`, the way the version gate does. The
  app would then show a terminal "This device no longer has access to the household" screen, with
  a "Recover my household" button.
  - The wording must cover both causes, since the server returns the same `DEVICE_REVOKED` for
    either: another member cut the device off, or the secret was rotated.
  - The screen could also say that actions made offline and not yet sent are lost, since the
    pending-operations table is wiped with the rest.
- **Check**: on the emulator (`ts-test-runner`), cut the device off from another member while
  the app is open. The app must return to the welcome screen without being relaunched.

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
```

The negative control was played: disabling the refusal of revoked devices turns **4 tests red
— exactly the 4 that test that rule**, while the other 5 stay green.
