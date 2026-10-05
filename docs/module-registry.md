# Module registry and progressive disclosure

Status: decisions settled 2026-08-17. **P1 to P4 shipped and verified**
(44/44 tests); P5 remains. This document is the spec for the whole
piece of work. French version: `module-registry.fr.md`.

## The problem

The app is complete and hosted (prod v0.36.1 as of 2026-08-17). The weak spot
is the first-use journey: a brand-new foyer used to start with nearly
everything on — gamification, the affinity rating popup, notifications,
bonus/penalty and its 13 thresholds — facing 895 interface strings (measured
2026-08-17).

This is a **reorganisation**, not a build. The machinery already exists:

- `user_prompts`: unified prioritised queue, event-driven plus cron, with
  `shown_at`, `resolved_at`, `expires_at`, `grace_until`, `DISMISSED` /
  `ANSWERED` resolution, frozen JSONB `context`. 5 types in service.
- `presence_day` + `get_absent_dates`: absence detection.
- `objective_propositions`: propose / accept / decline / expire, with FCM
  and a celebration.
- `backend/app/content/*.md` + `scripts/gen_site_content.py`: the
  "single source to app and site" pattern, already in production for the
  changelog and the roadmap.

## The registry

Two levels. Level 1 is what the user sees; level 2 appears only once its
module is enabled.

### Core — never disableable

Recurrence, green/amber/red urgency, rooms, categories, completion,
**one-shot tasks**, import catalogue, offline, per-task history.

One-shot tasks are core rather than a module: the only possible signal
would be "the user creates a task with no recurrence", which is
impossible while the module is off. And a one-shot adds no surface — it
is the absence of a recurrence rule in a form that already exists.

### The 8 modules

| # | Module | Sub-options | Scope |
|---|--------|-------------|-------|
| 1 | Sharing | assignment, affinities, effort, fair share | collective |
| 2 | Motivation | points, badges, streaks, leaderboard | collective |
| 3 | Bonus / penalty | early thresholds, late thresholds | collective |
| 4 | Goals & catching up | personal goal, cockpit, simulation | collective |
| 5 | Absences & presence | home freeze, category freeze, home WiFi | collective |
| 6 | Automatic adjustment | bump effort, frequency vote | collective |
| 7 | Statistics & history | charts, enriched history | personal |
| 8 | Reminders | the 6 notifications | personal |

**Scope rule**: collective if the module changes the shared model,
personal if it only changes what I see or receive. It applies to the
module, not the sub-option: a module is collective as soon as *one* of
its sub-options is. Modules 2 and 4 are mixed (leaderboard and cockpit
collective, badges and personal goal personal) — so their flag is
collective, and their personal sub-options stay personal.

Corollary: the `AND(foyer, user)` on `gamification_enabled` was a model
defect, not a confusing setting — it let one member unilaterally hide a
collective decision. **Model fixed in P1**: module state is collective,
and `user_settings.gamification_enabled` is requalified as a personal
display preference (`motivation.hide_for_me`) rather than dropped —
dropping it would have switched gamification back on for people who had
deliberately turned it off. **Client aligned in P2**: `gamificationVisibleFlow`
now reads the `motivation` module, falling back to the legacy foyer flag only
until `/api/modules` has answered once.

## Settled decisions

**1. What OFF means — mixed, by nature.**
Whatever accumulates keeps running in the background (points, badges,
streaks, stats, backlog/pace/maintenance-rate); whatever interrupts is
not generated at all (notifications, popups, prompts). Background
computation costs nothing: `/api/retard` already feeds the home screen,
and `points_earned` is already written on every completion.

**2. Waking a module up — full reveal.**
On enabling, the entire history appears. No `enabled_since` column, no
extra condition: the data is already there. The proposal becomes "you
already have 187 points, shall we show them?" rather than "want to try?".

Fair share does not carry the retroactive-debt risk that was feared: it
already runs on a rolling 30-day window (`fair_share_status`, 30-day
cutoff), so it is bounded and self-clearing.

**3. Who decides — any member.**
Consistent with observed practice: rooms, categories, tasks and
`bonus-config` are already open to everyone with no incident. Every
collective toggle emits an info notification to the other members and a
`change_log` entry.

Side effect to handle: `PUT /bonus-config` has no guard while
`PUT /foyers/{id}` is admin-only. The inconsistency is resolved downward.

**4. Trigger — contextual signal first, usage threshold as fallback.**

Strong signal, data already computed (4 modules):

| Module | Signal |
|--------|--------|
| Sharing | a 2nd member joins the foyer |
| Absences & presence | absence of 3 days or more detected |
| Automatic adjustment | task chronically late |
| Goals & catching up | `/api/retard` crosses a threshold |

No signal, usage threshold only (4 modules): Motivation, Statistics,
Bonus/penalty (requires Motivation first), Reminders (on from day one —
it is the retention module).

Signals alone cover only half the registry: the fallback is not a
convenience, it is what keeps 4 modules from staying invisible forever.

**5. Rate — separate budget, held server-side.**
One queue, two counters.

- Work prompts (the 5 existing types): 2-hour tolerance, unchanged.
- Discovery prompts: at most 1 per week, never before day 7, never two
  in the same session, 30 days of silence after a refusal.

The budget lives in the database (`user_prompts` already carries
`resolved_at` and `grace_until`) rather than in `HomeViewModel` —
otherwise a reinstall resets the counter, two devices diverge, and both
members of a foyer each receive the same proposal for what is a
collective choice.

**6. Refused module** — offered once more after 30 days on a fresh
signal, then dropped for good on the 2nd refusal. Costless, because the
module stays reachable in Settings.

**7. Surface in the app — Settings becomes the module tree.**
No 10th screen. Eight of the future sub-options already live there (6
notifications, affinity popup, home WiFi), filed by historical accident.
Three blocks: Features, My foyer, My account.

Consequence: one module per week becomes a default, not a ceiling. The
impatient open the screen; the passive are guided.

**8. State of a new foyer — core plus Reminders.**
All 8 modules off. On day zero the foyer is solo — the wizard creates a
single member, the QR invite is a separate screen: Sharing, leaderboard
and fair share are not "heavy", they are meaningless. And leaving Sharing
off preserves the best signal the system has.

**9. Existing foyers — everything stays on.**
The new regime applies only to foyers created after the switchover.

**10. Content — `modules.yaml`, created in P1.**
In the backend content folder, next to `catalog.yaml`. Single source,
multi-locale like it, feeding all three consumers: site cards, the
Features screen, and proposal copy. Each module carries a `pitch`
(selling register) and `details` (technical register). A test checks
every `state_field` against SQLAlchemy metadata: a renamed column breaks
the suite instead of producing a toggle that writes nowhere.

## The site

Ten cards maximum. Cards stay at **benefit** granularity rather than
module: `modules.yaml` carries an explicit card-to-module mapping.
Aligning them one to one would trade three concrete cards ("One prefers
vacuuming, the other the dishes") for a category name, and would put
"Bonus / penalty" and its 13 thresholds in the shop window.

| # | Card | Module |
|---|------|--------|
| 1 | Recurring tasks | core |
| 2 | Urgency at a glance | core |
| 3 | Fair share | Sharing |
| 4 | Everyone's preferences | Sharing |
| 5 | Effort | Sharing |
| 6 | Handling absences | Absences |
| 7 | Catching up | Goals — new |
| 8 | Motivation, if you want it | Motivation — new |
| 9 | The app grows with you | principle — new |
| 10 | Offline | property |

The "No account, no tracking" card is removed: it repeats the hero
verbatim. Card 9 is forced by decision 8 — without it the site promises
ten things the install does not show.

Modules with no card (Bonus/penalty, Automatic adjustment, Statistics,
Reminders): visible in the click-through detail. That is an editorial
choice to re-make for every new module, not an automatism.

Click-through detail: native `<details>` / `<dialog>`. The site currently
carries a single inline script (the copyright year); a JS modal would be
its first dependency.

## The enforcement sweep (P3)

Every module that is off must stop showing — and above all stop interrupting.
The two halves are not worth the same: hiding a screen is cosmetic, no longer
asking a question is not.

### Server side — the interruption channel

`user_prompts` is the only place the app speaks unprompted. Two guards, not
one:

- **at generation**: `generate_bump_prompts` (auto_adjust),
  `generate_vote_prompts_for_task` and `generate_expired_vote_prompts`
  (repartition) return immediately when their module is off;
- **at read**: `GET /prompts/next` filters types whose module is off. This
  second guard covers what the first cannot see — the queue ALREADY built at
  the moment of switching off. Without it: turn a feature off and keep being
  questioned by it for days.

`PROMPT_TYPE_MODULE` holds the mapping, and a test asserts that **every**
`PromptType` appears in it: an unmapped type would never be filtered, silently
and on that one type only.

### Client side

| Surface | Module | Note |
|---------|--------|------|
| Stats tab (nav) | statistics **or** motivation | the screen carries both |
| Badges tab | motivation | |
| History tab | statistics | |
| "more than your share" message | repartition | was on gamification, wrongly |
| Catch-up cockpit (home) | objectives | |
| Goals card (stats) | objectives | |
| Presence block (foyer) | presence | |
| Bonus/penalty thresholds | bonus_malus | |
| Notifications block | reminders | P2 |
| Affinity popup | repartition | P2 |
| The whole gamification UI | motivation | P2, central gate |

Stats tabs now dispatch on an **identity** rather than a position: hiding one
tab used to shift the content of every tab after it.

`moduleEnabled(key)` (`android/…/ui/common/ModuleGate.kt`) reads DataStore from any
composable, on the same pattern as the original gamification gate. Its fallback
is `true`: until `/api/modules` has answered we show, because making a screen
vanish for the length of a round trip is worse than showing it one second too
long.

## Contextual proposals (P4)

Sixth type in `user_prompts`: `DISCOVER_MODULE`. The first that does not ask
for an information but offers to widen the perimeter — and the only one whose
serving rule is **inverted**: served when the target module is OFF. That
inversion lives in the code, not the schema, and `INVERTED_PROMPT_TYPES`
declares it so the drift guard can tell "unclassified type" (a bug) from
"deliberately outside the normal filter".

The copy is frozen into `context` at creation, from modules.yaml — the same
convention as the other five types. A proposal therefore says exactly what the
module said at the moment the signal happened.

### The budget

`backend/app/services/module_discovery.py`, held in the database rather than the client:

The values are the ones settled in decision 5 above; they are not copied
here. This table says where they LIVE, so they are tuned in one place only:

| Rule | Constant |
|------|----------|
| Minimum foyer age | `MIN_FOYER_AGE_DAYS` |
| Between two proposals | `MIN_DAYS_BETWEEN_PROPOSALS` |
| Silence after a refusal | `REFUSAL_COOLDOWN_DAYS` |
| Dropped for good | `MAX_REFUSALS` |
| Absence detection threshold | `ABSENCE_PROPOSAL_THRESHOLD_DAYS` (api/presence.py) |

The counter is on **creation**, not resolution: an ignored proposal still
consumed its slot. Counting resolutions would let an unanswered proposal reopen
the door on every cron pass.

And a proposal expires after a week, otherwise it would block the next slot on
its own — since the budget counts creations.

### Wired signals

| Signal | Module | Where |
|--------|--------|-------|
| a 2nd member joins | repartition | `api/pairing.py`, on joining |
| return from 3+ days away | presence | `api/presence.py`, on WiFi check-in |
| task chronically late | auto_adjust | `services/prompts.py` |

Two choices worth remembering.

**Absence is proposed on RETURN, not during.** Offering absence handling to
someone who is precisely absent is the on-holiday interruption this whole
piece of work fights. The WiFi check-in is the "we are back" signal. Presence
days keep being recorded while the module is off — that is accumulating data,
not an interruption (decision 1), and it is what makes the detection possible.

**P3's guard on the bump became a router.** The "chronically late" criterion is
exactly the signal that justifies automatic adjustment. Computing it then
throwing it away because the module is off would have made the feature
unproposable to the foyers that need it most. So we compute, then route: work
prompt if the module is on, proposal if it is off.

### Not wired yet

- `backlog_threshold_reached` (objectives): the backlog is computed, the
  threshold remains to be set.
- Usage thresholds for the signal-less modules (motivation, statistics,
  bonus_malus). Unchanged since the start: no data allows fixing them. To
  instrument before deciding.

## The five phases

| Phase | Content | Dogfood |
|-------|---------|---------|
| P1 done | modules.yaml, columns, API, AND fix | none, invisible |
| P2 done | Settings becomes the catalogue | emulator, fresh foyer |
| P3 done | Module enforcement | Pixel 7 `ts_test`, fresh foyer |
| P4 done | Contextual proposals | `ts_test`, full loop |
| P5 | Site | last, once the copy has been tested |

The site comes last because its copy and the proposal copy come out of
the same file: writing them after seeing what convinces beats writing
them blind.

## The three backends (as of 2026-08-17)

| Channel | Where | Address | Purpose |
|---------|-------|---------|---------|
| prod | Scaleway <prod-host> | `api.torchonsetserviettes.app` | public app |
| beta | <beta-host>, `~/deploiements/ts-beta` | `<beta-host-ip>:8002` | P3/P4 dogfood |
| dev | <beta-host>, `~/deploiements/ts-dev` | `<beta-host-ip>:5438` | alembic + pytest |

Before 2026-08-17, `ts.api.base.url.beta` pointed at the **same**
Scaleway URL as prod: the beta channel existed in the Gradle build
(`.beta` applicationId suffix, `TAILSCALE_AUTOLOGIN`, build marker) but
had no server of its own, so any real-world test wrote into real data.
The beta stack closes that gap.

No Caddy and no TLS on beta: it is reachable only over the tailnet, which
already encrypts. A certificate would require public DNS, hence public
exposure — the opposite of what a test server should have.
There is no IP-to-user auto-login (routes removed on 2026-10-05): every
device goes through the wizard, which is precisely what this work is
about.

Code reaches it via `tar` over `ssh` from the dev machine rather than
`git pull`: the repository is private, and a dogfood server has to serve
work that is not committed yet.

## Dogfood — the two environments

Multi-foyer is fully supported (`allFoyers`, switching via
`TokenManager`, server-side scoping per foyer). So a second real foyer
can be created on real phones and the journey lived through, main foyer
untouched.

But the wizard's `addFoyerMode` skips the "You" and "Recovery" steps
(3 steps instead of 5): a second foyer **never** tests installing from
zero. Hence the `ts_test` AVD (Pixel 7, android-35,
`google_apis_playstore_ps16k` x86_64, 2 GB RAM, 6 GB data), on the same
recipe as the maintainer's other test AVDs. The two are complementary.

Chain verified 2026-08-17: the emulator reaches beta through the host's
NAT and its Tailscale (`GET /api/auth/auto` answered 200 server-side). No
Tailscale client to install inside the AVD.

First dogfood finding, **fixed on the spot**: on the very first launch,
before any welcome screen, Android showed the notification permission
prompt — asking someone to settle a reminder channel while they still had
no idea what the app does. `MainActivity.onCreate` fired it on every cold
start. It now lives in Settings, triggered by enabling a notification
(`SettingsScreen.onNotifToggle`). The business toggle stays independent
of the OS permission: a refusal does not erase the preference.

## Pending dogfood findings

- A foyer that skips the Rooms and Tasks steps of the wizard lands on an empty
  home showing "All fresh / nothing urgent". There is nothing to be fresh
  about: emptiness is presented as success. To handle in the first-use journey.

## Rehearsal on production data (2026-08-18)

The migration was replayed against a **fresh production dump** (18 foyers, 16
users, 101 tasks, 454 completions, 66 prompts) restored into a throwaway
database. Result:

- 108 collective rows (18 × 6) and 38 personal (19 active memberships × 2), no
  incomplete foyer, **no module off**;
- the 2 pending `BUMP_EFFORT` prompts are **still served** — checked by asking
  the real backend, not by re-reading the table;
- completion, presence check-in, `/sync` and the 3 nightly jobs all pass;
- the `motivation` → `foyers.gamification_enabled` mirror works (which is what
  the shipped APK reads).

The rehearsal found **two defects reasoning had missed**:

**An implicit refusal burned the budget.** The shipped APK does not know
`DISCOVER_MODULE`: its `else -> onDismiss()` resolves it as `DISMISSED` without
showing anything. Two of those and the module was retired for good, with nobody
having seen a thing. A refusal now counts only when it carries
`payload.declined` — the marker only an up-to-date client sends.

**A new member of an old foyer fell back to the day-zero defaults.** A
re-paired device creates a NEW user, hence no personal rows: in a grandfathered
foyer they would have had `statistics` off while their partner kept everything.
A foyer carrying collective rows predates the switchover, so its personal
modules without a row count as on.

### Deployment order for production

Backend BEFORE any APK, per the sync ordering rule in `CLAUDE.md`: the server
keeps mirroring `foyers.gamification_enabled` while the shipped APK reads it.
The other order would break live installs.

## Still to decide in P4

The exact usage thresholds for the 4 modules with no signal (Motivation
at how many completions, Statistics at what volume of history). No data
allows fixing them today: `suggestion_dismissals` only traces home-screen
suggestions, not prompts. To instrument before deciding.
