# Contexts — the model replacing categories and rooms

French version: [`contexts.fr.md`](contexts.fr.md).

A **context** is what explains why a task exists: a room, a pet, an outdoor
area, a life situation, or a kind of work. It replaces both `Category` and
`Room`, and a task can carry **several** of them.

## Why a single table

The two former tables were already nearly identical — same `foyer_id`, `name`,
`color`, `icon`, `template_key`; they differed by exactly one field each
(`requires_home` for one, `emoji` for the other). What really separated them
was a **cardinality constraint**: a task carried exactly one room and exactly
one category.

That constraint was wrong. "Clean the litter box" belongs to the Living room
(where it is), the Cat (why it exists) and Cleaning (what it is). You had to
pick one, and any pick lost two thirds of the information.

## Two flags, and why not one

`requires_home` answered **two distinct questions with one value**, so it was
necessarily wrong about one of them:

| Flag | Question | What it drives |
|------|----------|----------------|
| `requires_presence` | do you have to be there? | masking off home WiFi |
| `accrues_when_away` | does the need grow without us? | freezing during absences |

The garden and the cat prove it **in opposite directions**:

- Garden: you must be there to mow (masking right) but the grass grows anyway
  (freezing wrong).
- Cat: you must be there for the litter box (masking wrong, it was disabled)
  but nothing piles up if the cat is boarded (freezing right).

No single value satisfied both.

**OR resolution.** A task carrying several contexts takes the logical OR of
their flags: as soon as one context requires it, the rule applies. A garden
task also attached to Cleaning therefore keeps running during an absence,
because Garden carries `accrues_when_away`.

## Implicit contexts: what we don't ask about

A context is `selectable: false` when every household has it — `menage`,
`linge`, `entretien`. Asking "do you do housework?" makes no sense; those
contexts exist for **filtering** ("show me all the cleaning"), never for
choosing.

**The visibility rule, the only one that matters.** A task shows up if one of
its **selectable** contexts is ticked. Implicit ones reveal nothing on their
own. Without that restriction, every task carrying `menage` would show up no
matter what — precisely the defect being fixed: 41 % of the catalogue was
displayed unconditionally, including cat chores for people with no cat.

The reference implementation is `Catalog.tasks_for()`
(`backend/app/services/catalog.py`), mirrored verbatim on Android in
`CreateFoyerWizardViewModel.visibleContextTasks()`.

## The display context is the first SELECTED one

`position` records the catalogue order, but display takes the first context
**the user ticked**, not the first declared. A task attached to `[cat, dog]`
must appear under Dog for someone who only has a dog.

## What an import creates, and what it does not

A task often declares more contexts than the household owns: "Settle shared
accounts" carries `[colocation, administratif]`. A single-person household
ticking Admin must not inherit a Flatshare context.

`kept_context_keys()` (`backend/app/api/catalog.py`) therefore keeps only **the
chosen contexts plus the implicit ones**, falling back to the first declared
one so a task is never left orphaned — with no context it would drop out of
every filter.

⚠️ This rule lives at **two points of the same flow**: `resolve` proposes the
rooms to create, `apply` creates them. Fixing `apply` alone is not enough —
measured: the discarded context came back through the `room_decisions` the
client builds from `resolve`'s answer, looking as if it had been requested.

## Both legacy tables are gone

`rooms` and `categories` lived in dual write until a switched-over APK
shipped. They no longer exist: `e5f6a1b2c3d4_drop_rooms_categories.py` drops
them along with `tasks.room_id`, `tasks.category_id`, and folds
`objectives.scope_room_id`/`scope_category_id` into a single
`scope_context_id`.

The migration **fails rather than dropping blindly**: if a live task has a room
or a category but no context, it stops and reports the count. That is the only
case where dropping would lose information, and a destructive migration that
silently repairs what it does not understand turns a visible problem into
corrupted data.

⚠️ **Deployment order is reversed.** The sync-ordering rule puts the server
before the client for an *addition*; for a *removal* it is the opposite — the
client must already have stopped using what disappears. This migration
therefore ships AFTER a switched-over APK, never before.

Two field names survive on purpose: `room_keys` on import input and
`category_id` in statistics responses. They now carry contexts. Renaming them
would change nothing in the model and would break any client in flight during
the deployment; the new names (`context_keys`, `contexts`) exist alongside.

## Migration

`c3d4e5f6a9b0_contexts.py`. Rooms go first so they win deduplication on
`lower(trim(name))` — they carry the emoji and the richer attachment. No accent
folding: `unaccent` would require superuser rights unavailable on Scaleway, and
the real case ("Cuisine" room vs "Cuisine" category) carries the same accents.

`accrues_when_away` cannot be derived from the existing data: it is set to
`true` by `template_key`, then for hand-made contexts by a **short list of
unambiguous names**. "Perso", "Maison" or "Hygiène" stay `false` — the false
negative is the right side of the error here, since `false` = we freeze = the
current behaviour, so nobody sees their app change without asking.

## Suggesting tasks: `GET /api/catalog/suggestions`

The catalogue holds 181 tasks. Showing them all is a list people close. The
endpoint returns only those touching a context the household **already has**:
a home with a cat gets offered the litter box, a home without one never does.

Three rules shape the result:

- **Same visibility rule as everywhere else** — an implicit context
  (`menage`, `linge`, `entretien`) reveals nothing on its own, otherwise half
  the catalogue would surface for everyone.
- **Matching by `template_key`, then by name** — a hand-made context called
  "Chat" must bring up cat chores, or the feature only serves households that
  went through the wizard.
- **Exclusion on `template_key`** — what the household already has is not
  offered again. A task recreated by hand under the same title will come back:
  better to re-offer than to hide.

Sorting puts the lightest tasks first. A suggestion has to be inviting, and a
45-minute chore at the top of the list closes the screen.

`context_ids` is **resolved server-side** to the household's contexts: the
client has no mapping from catalogue keys to local contexts, and making it
guess would duplicate the matching.

In the app these live **inside the task creation screen**, under the title
field, which filters them as you type. Picking one fills the title, contexts,
frequency, effort and tolerance at once — all still editable. There is no
separate "catalogue" screen any more.

## Local cache migration

`AppDatabase` goes from version 10 to 12: 10→11 creates the `contexts` table
and adds `tasks.contexts`, 11→12 drops `categories` and `rooms` and recreates
`tasks` without its two legacy columns (SQLite cannot remove a column).

No `fallbackToDestructiveMigration`: the migrations are declared and played.
Proven on 2026-08-23 on a real device whose database dated back to April,
taken from Room 10 to 12 in one go, with no crash and no loss (the
authentication token survived, the app resynchronised on its own). Since then,
12→13 adds `tasks.firstCompletedAt` (see Anniversary).

## Calendar recurrence

The `calendar` type anchors the due date on the **calendar** rather than on the
last completion, which removes drift: filing taxes on 10 June one year does not
shift the following year's due date.

```json
{"type": "calendar", "months": [5], "day": 15, "grace_days": 0}
```

- `months`: 1..12, sorted and deduplicated; `day`: capped at 28 so no month
  skips an occurrence.
- `grace_days`: delay after the due date before **red**. This is what expresses
  "May–August": one occurrence on 1 May tolerated until end of August. Orange
  is not pushed back: the task is flagged before its due date, only red waits
  for the end of the tolerance.
- **Which due date is honoured**: the last past occurrence stays due as long as
  no completion honours it. A completion honours an occurrence as soon as its
  orange window is open, as for the anniversary: done on 10 May for the 15th,
  the task aims at the following 15 May and does not turn red on the 16th
  (defect fixed on 2026-10-01). Done before the window, it does not count.

**Orange window: 10 % of the gap between two occurrences, capped at 7 days**
(decided on 2026-09-30). The gap is 365 / number of months; without a cap, a
yearly vaccine turned orange **36 days** before its due date, too early to be
useful. A monthly calendar rule keeps its 3 days: the cap bounds, it does not
widen. Only the window is capped; the nominal interval, which feeds the
overdue ratio, stays whole. Implemented twice, to keep aligned:
`orange_window_cap_of` (`recurrence.py`) and `orangeWindowCapOf`
(`TaskUrgencyCalculator.kt`).

**No frequency vote.** Consensus always writes an `interval` or a
`monthly_variable`: a vote would replace the fixed date and bring drift back.
`PUT /tasks/{id}/frequency` and the revote request return 409
`GAMIF_RULE_NOT_VOTABLE` outside `VOTABLE_RULE_TYPES`. It is an **allow** list:
the anniversary form is excluded without anyone having had to think of it.

### Anniversary: one year after the first time

```json
{"type": "anniversary", "every_n_months": 12}
```

For vaccines and servicing: the due date starts from the **first** completion,
not the last. Occurrences are `first + k × every_n_months` (k ≥ 1), each
computed from the anchor (`plusMonths` / `add_months` clamp to the last day of
the month: a monthly 31 January gives 29 February, then 31 March, without
wearing down to the 28th). The period is in **months** so that "every 2 years"
lands on the same calendar day; the editor takes it in years or months.

- **Due date**: the oldest occurrence that the LAST completion has not
  honoured. A completion honours an occurrence as soon as its orange window is
  open: done in orange or red, it counts; done two months early, it does not.
  Done late, it does not move the next one: no drift.
- **Never done**: due date = `created_at`, like every other type. This is what
  prompts recording the first time; for a vaccine already received, enter the
  completion at its real date (the date picker already exists).
- **"First"** = the oldest **live** completion (`deleted_at IS NULL`). Deleting
  it moves the anchor to the next one; backdating a completion before the
  anchor moves it. Nothing is stored in the rule: the anchor is derived on
  every read, so there is no hidden write and no stale anchor.
- **Where it comes from**: `TaskResponse.first_completed_at` (server,
  authoritative online), persisted in Room (`tasks.firstCompletedAt`, Room
  version 13), reconciled by `earliestCompletedAt` = min(server, local cache)
  offline. Server side, `first_completions_by_task`
  (`services/completion_anchor.py`) queries only the anniversary tasks. A
  caller that does not supply it falls back to the last completion (one period
  after the last time), never to "no due date".
- **Orange window**: 10 %, capped at 7 days, like `calendar`.
- **No vote**: outside `VOTABLE_RULE_TYPES`.

Implemented twice, to keep aligned: `next_anniversary_due` (`recurrence.py`)
and `nextAnniversaryDue` (`TaskUrgencyCalculator.kt`), held by the same cases
(`test_anniversary_recurrence.py`, `AnniversaryDueTest.kt`).

**The type lists.** `LIVING_RULE_TYPES` is the reference: the REST schema reads
it (it rejected `calendar` with a 422 until 2026-10-01 while `/sync` accepted
it), `tests/test_recurrence_type_lists.py` proves that each type is accepted
over REST and compares `LIVING` / `VOTABLE` with the Kotlin source
(`RecurrenceTypes.kt`), and `RecurrenceTypesTest.kt` proves that the calculator
gives a due date to every living type.

The three legacy types are converted at normalisation: `weekdays` and `custom`
into an equivalent `interval` (`_legacy_interval`), `monthly` into a `calendar`
over the twelve months, same day. A fixed date converted into a 30-day interval
would drift, and drift is what `calendar` removes (decided on 2026-09-30).

The conversion only acted on write: a task stored before it kept its dead
type, and no read path knows it, so it had no due date and stayed green
forever. One was left in production on 30/09 (a `monthly` on the 1st, silent
since 29/07). Migration `d4ec241658ca` converts those rows and logs them in
`change_log`, so that phones receive them through `/sync`.

`/sync`, which bypasses Pydantic, now rejects any type outside
`LIVING_RULE_TYPES` (`checked_recurrence_rule`, `recurrence.py`). The rejected
op becomes an `op_error` conflict: it no longer blocks the phone's queue and no
longer cancels the earlier ops of the batch (one SAVEPOINT per op).

**Explanatory sentences.** `RecurrenceSentence.kt` renders each rule in French
or English as the user configures it. The example deliberately uses a **late**
day (`n+3`) rather than an on-time one: that is the case where interval and
calendar diverge, hence the only one that shows what is being chosen.
