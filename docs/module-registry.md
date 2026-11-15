# Module registry and progressive disclosure

Status: decisions settled 2026-08-17. **All five phases (P1 to P5) have shipped**: registry,
Settings, module enforcement, contextual proposals, site cards. This document is the spec for
the piece of work and records what was decided since. French version:
[`module-registry.fr.md`](module-registry.fr.md).

## The problem

The app is complete and hosted (prod v0.36.1 as of 2026-08-17). The weak spot
is the first-use journey: a brand-new foyer used to start with nearly
everything on — gamification, the affinity rating popup, notifications,
bonus/penalty and its 13 thresholds — facing 895 interface strings (measured
2026-08-17).

The work is a **reorganisation**, not a build. The machinery already exists:

- `user_prompts`: a unified prioritised queue, event-driven + cron, with
  `shown_at`, `resolved_at`, `expires_at`, `grace_until`, `DISMISSED`/`ANSWERED`
  resolution, and a frozen JSONB `context`. 5 types in service.
- `presence_day` + `get_absent_dates`: absence detection.
- `objective_propositions`: propose / accept / refuse / expire, with FCM and
  celebration.
- `backend/app/content/*.md` + `scripts/gen_site_content.py`: the "single
  source → app + site" pattern, already in production for the changelog and
  the roadmap.

## The registry

Two levels. Level 1 is what the user sees; level 2 only appears when its
module is active.

### Core — never disableable

Recurrence, green/orange/red urgency, contexts (which replaced rooms and categories, see
[`contexts.md`](contexts.md)), completion, **one-off tasks**, import catalogue, offline mode,
per-task history.

One-off tasks are core and not a module: the only possible signal would be "the
user creates a task without recurrence", impossible if the module is off. And a
one-off task adds no surface — it is the absence of a recurrence rule in an
existing form.

### The 8 modules

| # | Module | Sub-options | Scope |
|---|--------|-------------|-------|
| 1 | Distribution | assignment, affinities, effort, fair-share | collective |
| 2 | Motivation | points, badges, streaks, leaderboard | collective |
| 3 | Bonus / penalty | advance thresholds, lateness thresholds | collective |
| 4 | Objectives & catching up | personal goal, cockpit, simulation, proposals | collective |
| 5 | Absences & presence | home freeze, affected contexts, home WiFi | collective |
| 6 | Automatic adjustment | effort bump, frequency vote | collective |
| 7 | Statistics & history | curves, enriched history | personal |
| 8 | Reminders | the 6 notifications | personal |

**Scope rule**: collective if the module changes the shared model, personal if
it only changes what I see or receive. It applies to the module, not to the
sub-option: a module is collective as soon as *one* of its sub-options is.
Modules 2 and 4 are mixed (leaderboard and cockpit collective, badges and
personal goal personal) — so their flag is collective, and their personal
sub-options stay personal.

Corollary: the `AND(foyer, user)` on `gamification_enabled` was a model defect,
not a confusing setting — it let one member unilaterally hide a collective
decision. **Model fixed in P1**: the module state is collective, and
`user_settings.gamification_enabled` is requalified as a personal display
preference (`motivation.hide_for_me`) rather than deleted — otherwise
gamification would have been switched back on for those who had turned it off.
**Front end aligned in P2**: `gamificationVisibleFlow` now reads the
`motivation` module, and only falls back to the legacy flag as long as
`/api/modules` has never answered.

## Settled decisions

**1. OFF semantics — mixed, by nature.**
What accumulates keeps running in the background (points, badges, streaks,
stats, lateness/pace/maintenance-rate); what interrupts is not generated at all
(notifications, popups, prompts). Background computation costs nothing:
`/api/retard` already feeds the home, `points_earned` is already written on
every completion.

**2. Wake-up — full reveal.**
On activation, the whole history appears. No `enabled_since` column, no extra
condition: the data exists. The proposal becomes "you already have 187 points,
shall we show them?" rather than "want to try it?".

Fair-share does not raise the feared retroactive-debt problem: it already runs
on a sliding 30-day window (`STATUS_WINDOW_DAYS`, `backend/app/services/fair_share.py`),
hence bounded and self-cleaning.

**3. Who decides — any member.**
Consistent with observed use: contexts, tasks and `bonus-config` are already
open to everyone without incident. Every collective toggle sends an info
notification to the other members and a line in `change_log`.

`PUT /bonus-config` stays open to any member, while `PUT /foyers/{id}` is
admin-only: settled on 2026-10-05, the inconsistency is resolved downwards.

**4. Trigger — contextual signal first, usage threshold as fallback.**

Strong signal, data already computed (4 modules):

| Module | Signal |
|--------|--------|
| Distribution | a 2nd member joins the foyer |
| Absences & presence | absence ≥ 3 days detected |
| Automatic adjustment | task systematically late |
| Objectives & catching up | `/api/retard` exceeds a threshold |

No signal, usage threshold only (4 modules): Motivation, Statistics,
Bonus/penalty (requires Motivation active first), Reminders (active from day 0,
it is the retention module).

The signal alone only covers half the registry: the fallback is not a comfort,
it is what keeps 4 modules from staying invisible for good.

**5. Throughput — separate budget, held by the server.**
One queue, two counters.

- Work prompts (the 5 existing types): 2 h tolerance, unchanged.
- Discovery prompts: at most 1 every 5 days (hard floor; one week until
  2026-11-03), never before day 7, never two in the same session, 30 days of
  silence after a refusal.

The budget lives in the database (`user_prompts` already carries `resolved_at`
and `grace_until`) and not in `HomeViewModel`, otherwise a reinstall resets the
counter, two devices diverge, and both members of a foyer each receive the same
proposal for what is a collective choice.

**6. Refused module** — re-proposed once after 30 days on a new signal, dropped
for good at the 2nd refusal. Free of cost, because the module stays reachable
in Settings.

**7. Surface in the app — Settings becomes the module tree.**
No 10th screen. Eight of the future sub-options are already there (6
notifications, affinity popup, home WiFi), arranged by historical accident.
Three blocks: Features, My foyer, My account.

Consequence: the pace of one module every 5 days becomes a default, not a
ceiling. The impatient opens the screen, the passive lets themselves be guided.

**8. State of a new foyer — core + Reminders.**
All 8 modules off. On day 0 the foyer is solo — the wizard creates a single
member, the QR invitation is a separate screen: Distribution, leaderboard and
fair-share are not "heavy", they are meaningless. And leaving Distribution off
preserves the best signal of the whole mechanism.

**9. Existing foyers — everything stays on.**
The new regime only applies to foyers created after the switchover.

**10. Content — `modules.yaml`, created in P1.**
In the backend content folder, next to `catalog.yaml`. Single source,
multi-locale like it, feeding the three consumers: site cards, the Features
screen, proposal text. Each module carries a `pitch` (commercial register) and
`details` (technical register). A test checks every `state_field` against the
SQLAlchemy metadata: a renamed column breaks the suite instead of producing a
switch that writes nowhere.

## The site (P5, shipped)

At most ten cards (`MAX_CARDS` in `scripts/gen_site_content.py`). Cards stay at
**benefit** granularity and not module: the `site_cards` section of
`modules.yaml` holds an explicit card → module(s) mapping. Aligning 1:1 would
trade three concrete cards ("One prefers vacuuming, the other dishes") for a
category name, and put "Bonus / penalty" and its 13 thresholds in the shop
window.

| # | Card | Module |
|---|------|--------|
| 1 | Recurring tasks | core |
| 2 | Urgency at a glance | core |
| 3 | Fair share | Distribution |
| 4 | Everyone's preferences | Distribution |
| 5 | Arduousness | Distribution |
| 6 | Absence handling | Absences |
| 7 | Catching up | Objectives — new |
| 8 | Motivation, if you want it | Motivation — new |
| 9 | The app grows with you | principle — new |
| 10 | Offline | property |

The "No account, no tracking" card is removed: it repeats the hero word for
word. Card 9 is imposed by decision 8 — without it the site promises ten things
the installation does not show.

Modules without a card (Bonus/penalty, Automatic adjustment, Statistics,
Reminders): visible in the click-through detail. This is an editorial choice to
be re-owned with every new module, not an automatism.

Click-through detail: native `<details>` / `<dialog>`. The site currently ships
a single inline script (the copyright year); a JS modal would be its first
dependency.

## The enforcement sweep (P3)

Every module that is off must stop showing — and above all stop interrupting.
The two halves are not worth the same: hiding a screen is cosmetic, no longer
asking questions is not.

### Server side — the interruption channel

`user_prompts` is the only place from which the app speaks without being asked.
Two guards, not one:

- **at generation**: `generate_bump_prompts` (auto_adjust),
  `generate_vote_prompts_for_task` and `generate_expired_vote_prompts`
  (distribution) return immediately if their module is off;
- **at read**: `GET /prompts/next` filters out the types whose module is off.
  This second guard covers what the first cannot see — the queue ALREADY built
  when the module is switched off. Without it, you would switch a feature off
  and keep being questioned by it for days.

`PROMPT_TYPE_MODULE` holds the mapping, and a test checks that **every**
`PromptType` appears in it: an unmapped type would never be filtered, silently
and for that type alone.

### Client side

| Surface | Module | Note |
|---------|--------|------|
| Stats tab (nav) | statistics **or** motivation | the screen carries both |
| Badges tab | motivation | |
| History tab | statistics | |
| "More than your share" message | repartition | was on gamification, wrongly |
| Catch-up cockpit (home) | objectives | |
| Objectives card (stats) | objectives | |
| Presence block (foyer) | presence | |
| Bonus/penalty thresholds (foyer) | bonus_malus | |
| Notifications block | reminders | P2 |
| Affinity popup | repartition | P2 |
| All gamification UI | motivation | P2, central gate |

The Stats tabs are now dispatched on an **identity** and not on their position:
hiding a tab would otherwise shift the content of all the following ones.

`moduleEnabled(key)` (`ui/common/ModuleGate.kt`, Android side) reads DataStore from any
composable, on the same pattern as the original gamification gate. Its fallback is `true`:
as long as `/api/modules` has not answered we show, because making a screen
vanish during a round trip is worse than showing it one second too long.

## Contextual proposals (P4)

A sixth type in `user_prompts`: `DISCOVER_MODULE`. The first that does not ask
for information but proposes widening the scope — and the only one whose
delivery rule is **inverted**: served if the targeted module is OFF. This
inversion lives in the code, not in the schema, and `INVERTED_PROMPT_TYPES`
declares it so that the anti-drift guard can tell "unclassified type" (a bug)
from "deliberately outside the normal filter".

The text is frozen in the `context` at creation, from modules.yaml — same
convention as the five other types. The proposal therefore says exactly what
the module said when the signal occurred.

### The budget

`backend/app/services/module_discovery.py`, held in the database and not in the
client. The values are those of decision 5 above; they are not copied here.
This table says where they LIVE, so that they are tuned in one place:

| Rule | Constant |
|------|----------|
| Minimum foyer age | `MIN_FOYER_AGE_DAYS` |
| Between two proposals | `MIN_DAYS_BETWEEN_PROPOSALS` |
| Silence after a refusal | `REFUSAL_COOLDOWN_DAYS` |
| Definitive drop | `MAX_REFUSALS` |
| Absence detection threshold | `ABSENCE_PROPOSAL_THRESHOLD_DAYS` (api/presence.py) |

The counter applies to **creation** and not resolution: an ignored proposal
still used up its slot. Counting resolutions would let an unanswered proposal
reopen the door at every cron pass.

And a proposal expires after `MIN_DAYS_BETWEEN_PROPOSALS` days, otherwise it
would block the next slot by itself — since the budget counts creations.

### Wired signals

| Signal | Module | Where |
|--------|--------|-------|
| a 2nd member joins | repartition | `api/pairing.py`, on joining |
| return from absence ≥ 3 d | presence | `api/presence.py`, on WiFi check-in |
| task systematically late | auto_adjust | `services/prompts.py` |

Two choices to keep in mind.

**Absence is proposed on RETURN, not during.** Proposing absence handling to
someone who is precisely away is the on-holiday interruption the whole piece of
work fights. The WiFi check-in is the "we are back" signal. Presence days keep
being recorded with the module off — it is data that accumulates, not an
interruption (decision 1), and it is what makes detection possible.

**The P3 guard on the bump became a switch.** The "systematically late"
criterion is exactly the signal that justifies automatic adjustment.
Computing it and then throwing it away because the module is off would have
made the feature impossible to propose to the foyers that need it most. We
compute, then route: work prompt if the module is on, proposal otherwise.

### Usage thresholds (decided 2026-11-03)

The four modules without a wired signal are proposed on a threshold, evaluated
every night at 03:45 UTC (`services/module_usage_triggers.py`, after the 03:15
lateness snapshot), in registry order, at most one proposal per foyer per pass:

| Module | Threshold (provisional, to review at launch) |
|--------|----------------------------------------------|
| motivation | 20 live completions in the foyer |
| bonus_malus | Motivation active for 14 days |
| objectives | 3 late tasks on each of the last 3 snapshots |
| statistics | the oldest live completion is 30 days old |

A threshold stays true once reached: without a guard, the first module in the
list would take the slot again at every expiry. A module proposed less than
`REFUSAL_COOLDOWN_DAYS` days ago is therefore skipped, which lets the next one
through. `statistics` is PERSONAL: the proposal only goes to members who do not
have it, state read member by member (`_recipients` in `module_discovery.py`).

### Explanation pages (decided 2026-11-03)

Each module carries in modules.yaml a `guide` (`why`, `how`), shown with
`details` and `example`: what it is for, why, how to use it well. Shown **once
per member** (table `module_guide_views`, per user and not per foyer): in the
proposal, on manual activation in Settings, or, for a collective module
activated by another member, at the next app opening (`guide_due` in
`GET /api/modules`, one page per opening). Afterwards an ⓘ next to the switch
reopens it. Migration `b8c4d2e6f1a3` marks the pages of already-active modules
as seen: existing foyers receive no page on update.

## The five phases

| Phase | Content | Status |
|-------|---------|--------|
| P1 | modules.yaml, columns, API, AND fix | shipped |
| P2 | Settings → catalogue | shipped |
| P3 | Module enforcement | shipped |
| P4 | Contextual proposals, usage thresholds, explanation pages | shipped |
| P5 | Site cards (`site_cards`) | shipped |

The site came last because its texts and those of the proposals come out of the
same file: writing them after seeing what convinces beats writing them blind.

## Notification permission

On the very first launch, before any welcome screen, Android showed the
notification permission request — someone who did not yet know what the app
does was asked to rule on a reminder channel. `MainActivity.onCreate` launched
it on every cold start. It now lives in Settings, triggered by enabling a
notification (`SettingsScreen.onNotifToggle`). The business setting stays
independent of the system permission: a refusal does not erase the preference.

## Two switchover traps, found by replaying the migration

The migration was replayed on a real copy of the database before the
switchover. It found **two defects that reasoning had missed**:

**An implicit refusal burned the budget.** An APK shipped before
`DISCOVER_MODULE` does not know it: its `else -> onDismiss()` resolves it as
`DISMISSED` without showing anything. Two occurrences and the module was dropped
for good, with nobody having seen anything. A refusal now only counts if it
carries `payload.declined` — the marker only an up-to-date client sends.

**A new member of an old foyer fell back on the day-0 defaults.** A re-paired
device creates a NEW user, hence without personal rows: in a grandfathered foyer
they would have had `statistics` off while their partner kept everything. A foyer
carrying collective rows predates the switchover; its personal modules without a
row are therefore on.

### Switchover order

The backend BEFORE any APK, the sync-ordering rule of
[`architecture.md`](architecture.md) § Sync ordering: the server serves
`foyers.gamification_enabled` in duplicate as long as the shipped APK reads it.
The reverse would break running installations.
