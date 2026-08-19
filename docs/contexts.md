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

## Dual write, and until when

The server writes **in parallel** to `rooms`, `categories`, `tasks.room_id`,
`tasks.category_id`, because the shipped APK still reads them. Removing them
now would break every installation in the field (sync-ordering rule, see
`CLAUDE.md`).

The compatibility shims are named and grouped: `Catalog.rooms`,
`Catalog.categories`, `CatalogContext.requires_home`, `legacy_room_key()`,
`legacy_category_key()`, and the `default_room_key`/`default_category_key`
fields projected onto tasks at load time. Removal condition and decision
marker: `todo.md § Retirer la double écriture`.

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

Rehearsed on a production dump (18 households, 101 tasks): 28 contexts, 0
duplicates, 136 attachments, 54 multi-context tasks.

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
  still lands on the due date — the task is flagged on time, only the alarm is
  pushed back.

The three legacy types `weekdays`, `monthly` and `custom` are converted to an
equivalent `interval` at normalisation time (`_legacy_interval`), so no task is
silently inert any more. Rejecting them on input is still to do (`todo.md`).

**Explanatory sentences.** `RecurrenceSentence.kt` renders each rule in French
or English as the user configures it. The example deliberately uses a **late**
day (`n+3`) rather than an on-time one: that is the case where interval and
calendar diverge, hence the only one that shows what is being chosen.
