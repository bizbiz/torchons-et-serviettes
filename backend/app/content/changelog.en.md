# Release notes

## v0.48.1 — 2026-11-21

### Fixes
- A task's page no longer crashes when opened again.
- The "To do today" stack shows who handed the task right after signing in, instead of
  "Handed by ?".
- A task's page no longer shows points when gamification is off.
- With TalkBack, the "!" of a received task now reads "See who handed it", no longer "Cancel".
- The contexts help now says a single "It keeps going without us" is enough.

## v0.48.0 — 2026-11-21

### Marking a task done
- A task marked done counts even if you leave the screen during the confetti, and several quick
  taps count it only once.
- « Done together » finishes even if you switch screens.

### Offline
- A task created or edited offline no longer disappears or reverts when the app refreshes.
- Settings and effort and affinity ratings work offline and are sent when the network is back.
- Buttons that need the network are greyed out offline, with « Network needed », instead of
  doing nothing.
- Screens reload by themselves when the network is back, and say when they could not load
  instead of showing « No… ».

### Clearer
- Editing a task saves only what changed; the history says which fields, with the title the
  task had at that time.
- A refused rating or vote says so instead of showing « saved ».
- Signing out warns you if changes are still waiting to be sent.
- Switching households shows its progress and no longer mixes the two households.
- Fixed: two people acting at the same time no longer get an error.

## v0.47.0 — 2026-11-20

### During an absence
- A new sheet shows which tasks pause when nobody is home, and which keep going. Tapping a
  task explains what to change to move it to the other side. Open it under the freeze
  setting, or at the top of the Contexts screen.

### Often-late task
- The question now offers to re-vote the task's frequency, then its effort, one after the
  other. "Later" asks again the next time it is late, "Ignore" leaves it alone.
- Fixed: answering this question no longer opens the vote of another task by mistake.

### Frequency
- The frequency slider goes from 1 to 15 in days, weeks or months, instead of an approximate
  number of days.

### Task ratings
- A rating is only asked again after 24 months, and the "Renewal" slider in household
  settings finally works.
- Effort and affinity ratings no longer depend on the Sharing feature.

### Home and welcome
- Pull down on the home screen to refresh it.
- The welcome screen shows the way in pictures instead of describing it.

## v0.46.0 — 2026-11-19

### To do today
- A task's "!" hands it to the other members who can see it: it shows in red at the top of
  their home screen until someone does it, with reminders at 12:00, 16:00 and 20:00.
- The reminders can be turned off in Settings; the feature itself is always there.

### Help on every screen
- A "?" at the top right explains the screen you are looking at, covering only what is turned
  on in your household.
- A welcome note on first launch. At the bottom of Settings, "A word from Quentin" to send me a
  bug or an idea, or buy me a coffee.
- On the home screen, "Rate a task" moves to the bubble icon.

### Tasks tab
- Context filters are simple ticks, and up-to-date tasks show on demand at the bottom of the
  list.

### Errors
- Error messages are readable: "Can't reach the server", or a code to send me. A "Send to
  Quentin" button prepares the email.
- The error message stays above the keyboard, and the keyboard closes when you submit a text.

### Leaner
- Settings and charts of a feature that is turned off no longer show.
- Scanning the invitation QR code goes through Google: the app no longer needs the camera.

## v0.45.3 — 2026-11-17

### Fixed
- Home and Stats: with nothing overdue, the household overdue chart says “Nothing overdue over
  this period” instead of “No data yet”.
- Stats: tasks with no context are labelled in the phone's language.
- Presence calendar: months and days now follow the phone's language, and “Uncadenced” reads
  “As needed”.

## v0.45.2 — 2026-11-16

### Maintenance
- The app is ready for Android 16 and for its release on Google Play.

## v0.45.1 — 2026-11-16

### Fixed
- Gamification off: the recent history no longer shows points.
- Gamification off: a member's profile keeps a link to the presence calendar.
- A greyed-out option in Settings only updated after restarting the app.

## v0.45.0 — 2026-11-16

### Settings: the household on one side, you on the other
- The Household screen gathers the household features (Distribution, Motivation,
  Objectives…): a change applies to every member.
- Settings keeps only what is yours. Gamification there becomes "Hide points and streaks for
  me", with no effect on the others.
- An option of yours whose feature the household doesn't use stays visible, greyed out, with
  a link to the household settings.
- When another member turns off a household feature, a card tells you when you open the app.

### Fixed
- Turning gamification off from the Household screen no longer had any effect: points and
  streaks stayed visible.

## v0.44.6 — 2026-11-16

### Maintenance
- Internal server cleanup: old versions of the app, already refused, are no longer supported.
  Nothing changes in use.

## v0.44.5 — 2026-11-16

### Creating a chore
- Contexts come first and filter the suggestions.
- A household without rooms sees the whole catalog, filtered by the title.

### Creating a household
- Skipping a step no longer adds anything: no room, no chore.
- The household name comes pre-filled and can no longer be skipped.
- "Add chores" goes straight to creating a chore.

## v0.44.4 — 2026-11-16

### Privacy
- A link to the privacy policy when creating or joining a
  household, and in Settings.
- When a member leaves the household, their personal data is
  erased more thoroughly after 30 days.
- The home Wi-Fi name is erased as soon as you leave the
  household.

## v0.44.3 — 2026-11-15

### Completions
- A chore that was already completed is refused before the confetti, with
  the time of the first completion, even offline.
- A ✓ made offline keeps the time you tapped it, not the time it synced.
- A solo chore no longer shows "together".
- An "outside the household" refusal names the member by their first name.

### Re-vote
- Whoever asks for a re-vote sees that the notification went out, and is
  told when the other member answers, with the old and the new vote.

### Gamification off
- No more points, streaks or rankings shown: calendar, profile, stats,
  history and chore page.

### Screens
- A household with no chores is invited to add some, and the widget says
  "No tasks yet".
- A chore's history shows its creation.
- Long durations round to the month ("2 months").
- Stats: "Split by context".
- Labels are accented everywhere, and translated into English.

### Account
- Settings › Delete my account.
- A copied recovery code is hidden from the clipboard preview.

## v0.44.2 — 2026-11-11

### Re-vote
- A re-vote request arrives right away as a notification, one per request.
- The voting window and the notification show the chore's contexts
  ("Living room · Cleaning"): you know which vacuuming is meant.

### Widget
- The widget now shows only the chores to do. A chore you complete leaves it
  at once, so it can't be completed twice.
- "Already done" from the widget always shows the chore's name.

### Completions
- The same chore completed twice in a row by the same person now counts only
  once (within the hour, or within the day for a weekly chore).

## v0.44.1 — 2026-11-11

### Re-vote
- When a member asks the household for its opinion on a chore again, you get a
  notification, even with the app closed.
- Re-vote requests are no longer held back for two hours: they follow one
  another as soon as you answer the previous one, or close it.

### Widget
- Tapping the widget no longer launches the app a second time when it is
  already open.
- The "Already done" window opened from the widget always shows the chore's
  name.

## v0.44.0 — 2026-11-11

### Notifications
- A morning notification lists the chores that became overdue since the day
  before. It has its own switch and time in Settings (8 am by default).
- The daily reminder gives your share of the overdue chores: their number
  multiplied by your share in the split. It is no longer capped at 5, stays
  quiet when there is nothing, and works offline.
- When a member ticks a chore in your name, you are notified. The "Done
  together with me" setting becomes "When a member includes me in a task".
- A chore ticked several days after the fact still notifies the other members,
  with its date.
- Several chores ticked in a row produce a single notification, sent 5 minutes
  after the last one.

## v0.43.1 — 2026-11-04

### Split
- In Settings, you can set each household member's share (equal shares by
  default). An ⓘ explains the calculation, and every change stays in the
  history.
- The request to rate a chore spaces out when you close it without rating, and
  comes back more often when you rate (between 1 and 10 days).

### Household proposals
- A proposal made to the household shows up for the other members within a few
  seconds, and the waiting window closes as soon as they answer, even without
  a notification.

### Modules
- On sign-in, the app loads the household's modules right away: a switched-off
  module no longer appears for a moment while passing through Settings.
- In a new household, enabling a module for the whole household no longer
  switches on everyone's personal modules, such as Stats.
- "Ask to vote" appears only when Automatic adjustment is on, and no longer on
  its own line.
- The module guides have been reread and each has an example. "Contexts"
  replaces "rooms" and "categories" there.
- An ⓘ explains contexts, on their screen and on a chore's Contexts field.

### Fixes
- "Features" no longer loads forever when Settings was opened offline.

## v0.43.0 — 2026-11-03

### Modules
- Each module has its own explanation page: what it is for, why, and how to
  use it well. In Settings, an ⓘ next to its switch reopens it at any time.
- The page is shown once, when the app suggests the module or when you enable
  it yourself. When someone enables a module for the whole household, the
  other members see it the next time they open the app.
- Motivation, Bonuses and penalties, Goals and Stats are now suggested when
  the household is a good fit, instead of staying hidden in Settings.
- Two suggestions are spaced at least 5 days apart.

### Fixes
- Offline, the Contexts screen shows a message instead of closing the app.

## v0.42.1 — 2026-11-03

### Leaving the household
- A member who comes back to the household also gets back the chores they
  shared when they left, except those deleted in the meantime.

### Widget
- The widget no longer hides the on-site chores when you are at home.

## v0.42.0 — 2026-11-02

### Leaving the household
- A member who leaves the household is removed from the chores they shared;
  chores that were theirs alone are paused and come back if they return.
- For 30 days, a member who left can get their household back with their
  recovery code; after that, their account is anonymised.
- Their completions stay: their name appears struck through, then "Former
  member".
- A chore's history says "X left the household".

### Fixes
- Offline, "Edit" now opens the chore instead of an empty form.
- The frequency votes line shows "you haven't voted" instead of a garbled
  sentence, with the missing space.

## v0.41.5 — 2026-10-18

### Notifications
- Completing a chore notifies the other household members who enabled the
  notification again: until now, only "done together" notified them.
- Several chores completed offline arrive as a single notification on
  reconnection.
- A completion recorded with a date more than a day old notifies nobody.

### Privacy
- Chores assigned to a single person also no longer appear in the other
  members' calendar, and the history no longer stops too early when it
  contained some.

## v0.41.4 — 2026-10-18

### Privacy
- A chore assigned to a single person is visible only to them, no matter who
  created it: it no longer appears for the other household members, nor in the
  history, the week summary or the exports.
- Completing it no longer sends a notification to the other members.

## v0.41.3 — 2026-10-18

### Widget
- A chore completed from the widget turns green right away, without waiting.
- The widget shows only your chores: those assigned to you and the
  household's.
- Away from your home WiFi, it hides the on-site chores, like the Chores tab.

## v0.41.2 — 2026-10-18

### Widget
- The widget shows all the chores, in the same order as the Chores tab, and
  scrolls when they don't all fit.
- Each chore has the list's three buttons: "Done", "Already done" and "Done
  together". The last two open the app on the matching window.
- The widget works offline, with the phone's latest data, and a chore marked
  "Done" is sent as soon as the network returns.

## v0.41.1 — 2026-10-17

### Widget
- The widget no longer says "All fresh!" when it could not load the chores: it
  shows "Could not load the tasks", and tapping it opens the app.
- The widget updates as soon as you leave the app.

### Sync
- A change the server refuses for good, for example a chore assigned to
  someone who left the household, leaves the queue by itself. The sync window
  shows it under "Refused by the server".

### Fixes
- The rhythm of an "uncertain" chore counts a chore done together once, not
  once per participant.

## v0.41.0 — 2026-10-16

### Inviting remotely
- The "Invite someone" screen offers "Share" and "Copy code": no need to be
  side by side to scan the QR code.
- "Join a household" accepts a pasted code, on its own or with the whole
  message.

### Sync
- A chore marked done offline, for example for the other member, no longer
  disappears from the history while waiting to be sent.
- After an outage, changes resume at the next edit, instead of sometimes
  waiting several hours.
- When sending fails, the sync icon says so instead of spinning as if nothing
  had happened.
- The sync window lists what is waiting to be sent, since when and why, and
  lets you give up on a change the server refuses.
- When all is well, it shows the last exchange with the server, the last
  upload, and the app and server versions.

## v0.40.0 — 2026-10-01

### A year after the first time
- New "anniversary" recurrence, for vaccines, servicing or the car inspection:
  the due date starts from the first time the chore was done, and comes back
  every year (or every N months) on the same date, even if you do it late.
- For a chore done before it was created in the app, just record the first
  time at its real date.

### Fixes
- When the other household member edited a chore, it could appear empty on
  your phone (no title, nobody assigned) until the next refresh.
- A fixed-date chore done early, in the week before its due date, no longer
  turns red the next day: it counts for that due date, and the next one is
  targeted.
- The chore catalogue offered when creating a household is translated into
  English for phones set to English.

## v0.39.0 — 2026-09-30

### Fixed dates keep their word
- A fixed-date chore (taxes, boiler, vaccine) finally shows its due date and
  its colour in the list and on its page: it used to stay green whatever
  happened.
- It warns one week before the due date, no longer one month: an annual
  vaccine turned orange 36 days ahead.
- The frequency of a fixed date can no longer be voted on: the vote replaced
  it with an interval, and it started drifting again.
- A monthly chore created with an old format had not shown up since July: it
  comes back every month, on its date.

### My devices
- Settings → My devices: see the phones connected to your account, and cut
  off the access of one you no longer use. It also stops receiving
  notifications.

### Fixes
- Offline sync could stay stuck, or even lose a change, when the server
  refused a single operation in a batch.
- Questions that have become pointless (deleted chore, days already
  confirmed) close by themselves.
- The overdue curve in the stats stopped loading when absence freezing was
  active.

## v0.38.0 — 2026-08-20

### Contexts everywhere
- Rooms and categories are gone: there is a single list now, contexts, and a
  chore can carry several. Settings, the Chores tab filters, editing and the
  home screen all speak the same language.
- Absence freezing finally tells two things apart: *do you need to be there*
  and *does it keep going without us*. The garden is no longer paused during
  holidays — the grass certainly isn't.

### Adding a chore, in three seconds
- The **+** button suggests chores that fit your home: the litter box if you
  have a cat, watering if you have a vegetable patch. What you already have is
  no longer offered.
- Start typing and the list narrows. Picking one fills in the title, contexts,
  frequency and effort at once — all still editable.
- The "Inspirations" button is gone: it opened a screen nothing led to.

### Fixes
- The home screen showed "Data unavailable" in some setups.
- Three-month statistics could fail to load.

## v0.37.0 — 2026-08-19

### Contexts replace rooms and categories
- One list instead of two: a context is what explains why a chore exists in
  your home — a room, a pet, an outdoor area, a situation.
- **A chore can carry several.** "Clean the litter box" belongs to the Living
  room, the Cat and Cleaning all at once: no more picking one.
- Two separate settings per context where there used to be one:
  *you must be there* (hidden off home WiFi) and *it keeps going without us*
  (not paused while you are away). A garden needs the first without the
  second — grass grows during holidays too.
- The Chores tab filter becomes a single bar and accepts several contexts at
  once.

### Household creation reworked
- Four steps instead of five: first name and household name on one screen,
  with the name pre-filled ("Alice's place") and editable.
- You now pick from 34 contexts — pets, garden, vegetable patch, admin,
  flatshare… — rather than rooms only.
- Suggested chores are grouped by context, with a few useful ones pre-ticked,
  instead of one long flat list.

### Fixed-date chores
- New *calendar* rhythm: "in May", "on 15 May". The due date is anchored to
  the calendar and no longer drifts — filing your taxes late one year does not
  shift the next one.
- Every frequency is spelled out in a plain sentence as you set it, in French
  and in English.

### The app grows with you
- Features are switched on from Settings: sharing, motivation, bonuses and
  penalties, goals, absences… Nothing is forced at first launch, and the app
  offers the rest as you go.

### Fixes
- Missing accents restored on seventeen catalogue chores.
- Deleting a room then creating another one with the same name failed with no
  message.

## v0.36.1 — 2026-07-05

### Overdue — consistent counts
- The "overdue" task count on Home now matches what you see in the Tasks
  tab: same definition (whole-day, absence freeze), and a never-completed
  task is no longer counted as overdue the moment it is created.
- The overdue curve below that number now uses the exact same computation
  (all tasks, personal ones included) and its last point matches the
  counter, even mid-day.

## v0.36.0 — 2026-07-05

### Home
- The completion buttons under each task are replaced by a per-day
  navigation: pick the relevant day before validating.
- A sync status indicator now shows up, and the displayed points
  follow the selected day.

### Post-completion celebration
- The celebration shown after completing a task now displays the
  points earned and who they go to — making it clear when they are
  not for you. This detail used to appear only from Home; it is now
  also there when you validate from the task list or from a task's
  detail screen.
- Its duration was tuned to stay visible without dragging.

## v0.35.18 — 2026-05-22

### i18n — Stats, history, badges
- **"This week" card** (top of Stats): title, date range "from X to
  Y", per-member stats, Top tasks, empty message — now bilingual,
  with proper French accents.
- **"Split by room" card** (long-term Stats): title, Month / Quarter
  / Year chips, bar labels, empty message — bilingual.
- **Activity history** (profile modal + Stats history tab): the
  server-generated descriptions ("X completed: Y (+Z pts)",
  "X rated affinity of Y at S/10", "X edited task Y (recurrence)",
  etc.) are now translated based on `Accept-Language`. No more
  forced FR.
- **Badges**: the 12 catalog badges (First task, Steady, King of
  toil, etc.) are now translated server-side based on the phone's
  language.

## v0.35.17 — 2026-05-22

### i18n — countdown + app name fix
- The countdown labels on home cards ("3d late", "in 5d", "Done 2d
  ago"...) now follow the phone language — they were FR-only by
  oversight in Lot 1.
- The Android **app name** on the launcher ("Torchons & Serviettes"
  prod, "T&S Beta" beta) no longer contains the "&amp;" artefact
  introduced in 0.35.16 (double-escape of the ampersand in
  `build.gradle.kts`).

## v0.35.16 — 2026-05-21

### Unified absence popup with the Presence & Streaks modal
- The popup that appears automatically on launch when the app detects
  **days of absence to confirm** now opens the **same editable modal**
  used everywhere else (Household, Stats, profile) — no more two
  separate surfaces doing the same thing. You can now edit any day
  (not just those flagged as uncertain), see your streaks / records,
  switch between household members, change month — all in one place.
- Edits are saved immediately on each tap. When you close the modal,
  the reminder is marked as resolved and the "unconfirmed days" banner
  disappears from the home screen.

## v0.35.15 — 2026-05-21

### EN translation — Backend error messages
- The most visible error messages (33 cases) are now bilingual,
  translated server-side based on your phone's language. Covers all
  critical flows: **joining a household** (expired QR code, already
  used, etc.), **recovering my household** (invalid code, etc.),
  **leaving / switching household**, **validating a task**
  (anti-duplicate, participants outside household), **task completion**
  not found, etc.
- If your phone is in English: "Invalid invitation code" instead of
  "Code d'invitation invalide", "Task already completed 5 min ago.
  Next slot at…" instead of the FR version, etc.
- The ~60 other backend messages remain in French for now (progressive
  migration — no regression: older clients still see FR).

## Website v0.35.14b — 2026-05-21

### Bilingual website (partial)
- The torchonsetserviettes.app website is now available in English on
  the main pages: **home**, **About**, **Contact** and **404 page**.
  Language switcher in the menu.
- **FAQ**, **Privacy policy**, **What's new** and **Roadmap** pages
  remain in French for now (the switcher marks them "FR" with a small
  "EN coming"). Translation planned in a dedicated upcoming ship —
  sensitive legal content that deserves careful review.

## v0.35.14 — 2026-05-21

### EN translation — Template catalog
- The **room names** (Cuisine→Kitchen, Salle de bain→Bathroom,
  Buanderie→Laundry room, …), the **categories** (Ménage→Cleaning,
  Linge→Laundry, Administratif→Admin, Soin personnel→Personal care,
  …) and the **41 catalog tasks** (Faire la vaisselle→Do the dishes,
  Passer l'aspirateur→Vacuum, Détartrer la cafetière→Descale the
  coffee maker, …) are now translated to English.
- The app automatically sends your phone's language to the server, so
  importing from the "Catalog" screen will create tasks in English if
  your phone is in English, in French otherwise.

## v0.35.13 — 2026-05-21

### EN translation — Complete onboarding
- The onboarding screens are now bilingual: **Welcome** (3 buttons
  Create / Join / Recover), **Login** (Tailscale fallback, user
  selection), full **Create household wizard** (5 steps: first name,
  household name, rooms, tasks, recovery code), **Join a household**
  (QR scan + first name + add device + recovery code screen), **Recover
  my household** (code entry + identity confirmation with plurals),
  and **Invite someone** (QR code + variants for add-device vs guest).
- The roles in the restore screen ("admin / former member / member")
  are localised.
- **The Android app is now fully bilingual FR + EN.** Switch your
  phone's language and you'll see the whole app change. Still to
  translate: backend error messages, task template catalog, and
  website.

## v0.35.12 — 2026-05-21

### EN translation — Task detail & edit
- The **Task detail** screen is now bilingual: urgency state, "For…"
  banner, "you haven't voted" banner, Effort & Affinity card with
  "+ info" links, frequency, tolerance, completion history (individual
  and collective cards) and modification history (votes + edits) with
  field names translated.
- The **Edit / Create task** screen is bilingual: title, "Duplicated
  from…" chip, suggestions, category / room / frequency dropdowns,
  "every how often" field, smart breakdown, consensus with plurals,
  12-month table (localised month names), tolerance and effort.
- The app is now translated to about 95%. Still to translate:
  onboarding screens (Welcome / Login / household creation / QR scan),
  template catalog and website.

## v0.35.11 — 2026-05-21

### EN translation — Home & Tasks
- The **Home** and **Tasks** tabs are now bilingual: title, greeting,
  "Me"/"Household" cards, suggestions, buttons (Done / Already done /
  Together), tri-state filters, search, and all proposition dialogs
  between members display in English if your phone is in English.
  Plurals (1 day vs N days, 1 pt vs N pts) are handled correctly in
  both languages.
- The **Task detail** and **Edit** screens remain in FR for now —
  their translation lands in the next version.

## v0.35.10 — 2026-05-21

### EN translation — Catalog
- The **Catalog** screen (pre-built template import) is now bilingual:
  tab titles, attachment dialog and import summary display in English
  if your phone is in English. Plurals ("1 task created" vs "3 tasks
  created") are handled correctly in both languages.

## v0.35.9 — 2026-05-20

### Bilingual app preparation (FR + EN)
- Starting the EN translation of the app: the **Categories** and
  **Rooms** screens (from the Household screen) are the first
  translated. Switch your phone to English: these 2 screens display in
  English. Other screens remain in French during the progressive
  migration.

## v0.35.8 — 2026-05-20

### Home & Tasks auto-refresh
- The **Home** tab refreshes on each open (projections, prompts,
  fair-share) — no more pull-to-refresh after a change elsewhere.
- The **Tasks** tab: same, urgencies/lateness refresh on open if
  you've changed presence or anything else in another surface.

## v0.35.7 — 2026-05-20

### Stats auto-refresh
- The Stats tab refreshes automatically every time you open it: if
  you've completed a task, modified a presence or changed anything
  elsewhere in the app, your streak, leaderboard and badges reflect
  these changes without pull-to-refresh.

## v0.35.6 — 2026-05-20

### Up-to-date display & labels
- The "Connected via Tailscale" subtitle in the profile (Settings) is
  removed: misleading on beta which uses Scaleway. The effective
  channel remains visible in Settings > About > Build.
- After editing presence in the "Presence & streaks" modal, the
  Streak block of the Summary (Stats tab) refreshes automatically on
  modal close — no more pull-to-refresh.

## v0.35.5 — 2026-05-19

### Enriched suggestion catalog
- Two new suggested categories: "Personal care" (face mask,
  hairdresser/dentist/doctor appointment) and "Pet" (water, litter
  box, bowls, basket).
- "Maintenance" category finally supplied: washing machine /
  dishwasher filters, descale the coffee maker, sharpen knives,
  defrost the freezer, clean the vacuum, light switches…
- More Gardening suggestions (prune, repot, season) and Admin (taxes,
  declare my hours, subscriptions, paperwork).
- The "Groceries" category and its suggestion are removed (other apps
  do this better).

## v0.35.4 — 2026-05-19

### Household creation: actually fixed (database constraint)
- The real blocker for "empty household" was a too-strict database
  constraint: a room or category name had to be unique across the
  ENTIRE app, instead of being unique per household. Creating a
  household with rooms/tasks already present in another of your
  households failed. Now the same room/category name can exist in
  multiple households — the "Create a household" import works.

## v0.35.3 — 2026-05-19

### Household creation: rooms/tasks finally created (server fix)
- Fix for the bug where creating a household (while already a member
  of another) left it **empty**: the import failed server-side when
  two catalog elements had the same name. Rooms, categories and tasks
  chosen during the wizard are now properly created.

## v0.35.2 — 2026-05-19

### Household creation: visible errors
- If room/task import fails during "Create a household", an explicit
  error message now appears (before: the household could remain empty
  with no explanation).

## v0.35.1 — 2026-05-19

### Household creation fix
- Rooms and tasks chosen during the "Create a household" wizard now
  appear immediately in the new household (before: you had to switch
  households and come back for them to display).

## v0.35.0 — 2026-05-19

### Create a household: real guided wizard
- "Create a new household" now opens the same wizard as first creation
  (household name, then rooms, then tasks) instead of just a "name"
  field. The household is ready to use and the app switches to it
  automatically. Your first name and recovery code are not re-asked
  (existing account).

## v0.34.4 — 2026-05-19

### Household name up to date in Settings
- In Settings, the "🏠 <household name>" line now updates when you
  switch household or leave one from the Household screen (before: it
  could display an already-left household until the Household screen
  was reopened).

## v0.34.3 — 2026-05-19

### Left households hidden from "Switch household"
- Households you've left no longer appear in the "Switch household"
  list (before: they remained displayed as "Former member").

## v0.34.2 — 2026-05-19

### Households: up-to-date display without refresh
- After creating a household, the "Switch household" option appears
  immediately in the Household screen (no need to come back or reload).
- When you leave a household, it disappears immediately from the
  "Switch household" list. Trying to leave an already-left household
  no longer shows an error (404).

## v0.34.1 — 2026-05-19

### Streaks: fixed calculation + clear display
- The streak was no longer calculated correctly when it spanned two
  months (e.g., April 19 to May 6) and the record was wrong. Fixed:
  streak and record are now calculated over the full history (no more
  truncation to the displayed month).
- New visual cue in the calendar: a **gold** border surrounds the days
  of your **best streak (record)**, an **indigo** border those of the
  **current streak**. Cell background remains the presence (green /
  soft red / grey) and the star marks days when a task was done.
  Legend updated.

## v0.34.0 — 2026-05-18

### Presence & streaks: a single calendar
- The presence calendar and the streaks calendar are now one: a single
  "Presence & streaks" screen where you set present / absent days,
  see stars for days when a task was done and the connecting streak.
  Openable from the Household screen ("Open presence & streaks
  calendar" button), from Stats (streak block in the summary) and from
  a member's profile.
- The Household screen no longer contains a duplicate calendar: it
  keeps the presence settings (pause when absent, home Wi-Fi) and a
  button to the calendar.
- Editing a presence day immediately recalculates household task
  lateness and the streak.

## v0.33.1 — 2026-05-18

### Fixes (Household screen / presence / streaks)
- The household name displays immediately when opening the Household
  screen (no more brief "My household" while loading).
- Streaks calendar: stars and streak line now stay inside the
  calendar (no more marks overflowing outside the grid).
- Editing presence (present / absent) immediately recalculates
  household task lateness and the streak: the display is up to date
  without having to reload.

## v0.33.0 — 2026-05-18

### Dedicated Household screen
- Household-related settings are now grouped in a dedicated
  "Household" screen (title = household name), accessible from the
  household line in Settings and from the "Household" card on the
  home screen. Two clear groups: "This household" (shared between
  members) and "My account" (applies to all your households), with an
  (i) explaining the scope.
- The old "My households" screen disappears: "Create a new household"
  is a button in Settings (automatic switch to the created household),
  and "Switch household" is a selection sheet directly in the
  Household screen — shown only if you have at least two households.
- Rooms and Categories are now stored under the Household screen. A
  "Simulate catch-up" button opens the same simulator as the
  "Household" card.

### Editable presence calendar
- A calendar in the Household screen lets you set or clear present /
  absent days, in the past as in the future (plan an absence ahead).
  Three states: green = present, soft red = absent (home tasks frozen
  that day), grey = unset (counts as absent by default). An icon
  indicates the source: Wi-Fi (detected) or hand (set manually).
- A day set by hand is never overwritten by Wi-Fi auto-detection
  (manual priority). Legend and detailed (i) under the grid. Same
  grid as the "Days to confirm" reminder, with a "View full calendar"
  link from that reminder.

### Streaks calendar
- From the Stats tab (streak block in summary) and a member's profile,
  a read-only streaks calendar: a star on each day when the member did
  at least one task, and a line connecting active days. The streak
  passes through confirmed absent days (a legitimate absence doesn't
  break the streak) and breaks on an uncertain day or a present day
  without a task. Integrated member selector; "Edit presence" link to
  the Household screen.

## v0.32.0 — 2026-05-12

### Gamification — can be disabled
- New switch "Enable gamification for my account" at the top of
  Settings. If disabled, you no longer see points, bonuses, stats,
  leaderboard, badges, streaks and fair-share in any of your
  households. The Stats tab disappears from the navigation bar for a
  cleaner app.
- New switch "Gamification in this household" in Settings > Advanced.
  Any member can toggle it — a confirmation dialog reminds you that
  the other member will also be affected before applying.
- When gamification is disabled, the celebration after a validated
  task remains (confetti + emoji + encouragement message) but without
  "+X pts".
- New link "Why am I not earning points for some tasks?" in Settings —
  visible only if you're in a state without gamification (yourself OR
  at least one of your households). It explains the precise cause
  (you, one household, multiple stacked causes, multi-household mixed)
  and offers a button to re-enable directly.
- The text of the task effort info popup adapts its wording: if
  gamification is disabled, points and fair-share are no longer
  mentioned, only time estimate is discussed.

## v0.31.3 — 2026-05-12

### Gamification — points readability
- Display of predicted points on each home screen card ("💰 N pts"),
  with mention of the late bonus when applicable ("🔥 +M pts late
  bonus")
- Clickable (i) icon next to solo tasks (0 points) that opens an
  explanatory popup: why a single participant = no points, and why
  validating the task is still useful
- Same popup available in the "Done!" dialog when the task won't earn
  points

## v0.31.2 — 2026-05-12

### Absences (home freeze)
- The "Days to confirm" dialog now pre-checks all days as absent by
  default. No need to toggle everything after a long absence — just
  mark the few days when you were actually here.
- "At home" tasks no longer display as late during confirmed absence
  days. Lateness is frozen and resumes when you return, without
  unfair "debt" accumulation.
- Stats (weekly, monthly, total, leaderboard, long-term, household
  totals, weekly summary) now ignore completions falling on an absent
  day — consistent with the freeze applied to tasks.
- After resolving an absence prompt, tasks are automatically
  recomputed: the display is always correct, no more visual
  inconsistency.

## v0.31.1 — 2026-05-01

### Under the hood
- Server-side CSV history export (completions + tasks) set up.
  Accessible only via curl with JWT for now; a "My data" screen in
  Settings will follow in a future iteration.
- This release also includes v0.31.0 (weekly stats + household totals
  + long-term + goals + add-device) which had never been installed on
  the device.

## v0.31.0 — 2026-04-30

### Weekly statistics
- A new "This week" card at the top of the Stats tab summarises your
  household week: number of tasks, accumulated points, ranking by
  member with 🏆 emoji on the top, and top 3 most-done tasks.
  Left/right arrows to navigate past weeks.

### Household-wide statistics
- The user profile modal (tap on a name in Stats) now displays a
  "Household total" card with 3 columns (this week / this month /
  all-time). If the household chains several days in a row with >=1
  task done by anyone, a "🔥 N-day streak" counter appears. The
  "Split" section (bars per member) is kept as a complement.

### Long-term stats by room
- New "Split by room" card on Stats: horizontal bars to visualise
  which room concentrates the most tasks over the period (Month /
  Quarter / Year). Also displays estimated cumulated time (deduced
  from the effort consensus of each task, 1pt = 5min).

### Personal and shared goals
- New "My goals" card on Stats. Tap + to create a goal (e.g., 3 tasks
  per week), with period choice (per day/week/month) and target (1 to
  30). Progress is calculated automatically and displayed with a
  progress bar per goal. Long-press to delete.

### Add one of my devices
- A new "Add one of my devices" card in Settings lets you generate a
  QR to connect a tablet or a second phone to your account without
  creating a new household member.

## v0.30.1 — 2026-04-30

### Household security (Settings)
- New "Household security" section in Settings with two actions:
  - "Regenerate my recovery code": replaces your old code with a new
    one, displayed only once with Copy and Share buttons. Useful if
    you didn't have a code yet, or if you suspect the old one leaked.
  - "Leave household": you exit the household while preserving your
    server-side history. If you're a member of another household, you
    switch to it automatically; otherwise you return to the home
    screen.

### Support / Chat with the dev
- New card at the bottom of Settings with a word from the dev (solo
  author). Two buttons: "Buy a coffee" (opens the Buy Me a Coffee
  page in your browser) and "Write to me" (opens the mail app with a
  draft to the dev). Feedback, ideas and bugs are welcome.

## v0.30.0 — 2026-04-29

### Invite someone to the household
- A new "Invite someone" card in Settings lets you generate a QR
  invitation code valid for 15 minutes. The invited person installs
  the app, chooses "Join a household" and scans the QR to become a
  household member.

### Onboarding without an account
- On first app open (or after uninstall), a welcome screen offers
  three choices: "Create a household", "Join a household" (QR scan),
  or "Recover my household" (recovery code). No more need to create
  an account with a username.
- The "Create a household" flow guides in 5 steps: your first name,
  household name, your home's rooms, the tasks you want to track
  (drawn from the catalog), then your recovery code.
- For now the home Tailscale network continues to log you in
  automatically as before — you'll only see the new welcome if you
  reinstall the app.

### Recovery code
- On household creation or join, a unique 8-character code
  (XXXX-XXXX) is displayed. Note it down: it lets you recover your
  household + admin rights if you lose your phone. Shown only once,
  shareable via mail/messaging through a dedicated button.

## v0.29.0 — 2026-04-29

### "+ info" modal on effort
- On a task's detail, in the "Effort & Affinity" section, a new
  "Effort & points · + info" link opens a modal explaining what the
  effort vote is for: calculation of points earned when the task is
  validated, fair-share between members, and overall household
  backlog.
- The modal lists each member's effort vote with the vote date.
  "Edit my vote" buttons on your row, "Ask to re-vote" on others, and
  "Ask to vote" for assigned members who haven't voted yet.
- Mirror of the "+ info" modal already existing for frequency.

## v0.28.2 — 2026-04-29

### Recognisable notification icon
- Notifications now display the wizard's hat silhouette in the status
  bar, instead of a generic white square. The background pill uses
  the app's indigo colour on Android 8+.

## v0.28.1 — 2026-04-28

### Push notifications finally reliable
- The notification token is now re-synced on app start, no longer only
  after login. Before this fix, a user logged in for a long time via
  Tailscale never re-sent their token to the server — result: no
  notifications arrived. Opening the app is now enough to fix this.

## v0.28.0 — 2026-04-28

### "You haven't voted" banner on task detail
- On task detail, a blue banner appears when you haven't yet
  expressed your opinion (frequency, affinity, or effort). Tapping
  opens the vote form, pre-filled with already-entered values. It's
  purely an invitation: feel free to ignore.
- The subtext under frequency changes based on vote state: "Average
  of your and the other member's votes", or "Other member's vote ·
  you haven't voted", or "Nobody has voted yet".

### "+ info" modals on frequency and affinity
- A "+ info" link under frequency opens a modal explaining the voting
  mechanism, shows each person's value with date, and lets you edit
  your vote or ask another member to re-vote.
- An equivalent modal on affinity explains why it's a personal
  preference (only influences your own suggestions), and lets you
  edit your opinion. No "ask someone else" button for affinity: it's
  subjective and personal.

### Ask someone to re-vote
- From the "+ info" frequency modal, an "Ask to re-vote" button on a
  user's side sends a request to their queue. When the other opens
  the app, the vote form pops with a "X asked you to re-vote on this
  task" message. Same for effort.
- An already-sent request is visible (greyed "Request sent" button)
  to avoid sending the same one 10 times.

## v0.27.0 — 2026-04-28

### Task frequency: a vote, simple and clear
- On task creation, we now explicitly speak of "vote": your input is
  your vote on the frequency, not an imposed rule. If you're alone
  voting, your vote becomes the displayed frequency.
- Four task type choices, in plain words: "Regular", "By month",
  "Irregular", "Once only". Three old technical types (fixed weekdays,
  monthly on fixed day, every N weeks) disappear — they weren't used
  and cluttered the interface.
- The "Edit my vote" mini-dialog offers two modes: "Regular" (a
  single slider) or "By month" (12 monthly sliders). Useful for
  seasonal tasks: mow the lawn every 7 days in summer and every 30 in
  winter, for example.
- The creation slider asks the right question: *"How often, in your
  opinion, should this task be done?"*

## v0.26.0 — 2026-04-27

### Task list filters in plain words
- The six filters at the top of the list (to do / loved / long /
  recurring / home / solo) are no longer icons but text chips. No
  need to guess what the flame or heart mean.
- Three states instead of two: one click activates the positive
  filter (red background, "To do"), a second click switches to the
  inverse filter (green background, "Not to do"), a third click
  removes it (just text with an outline).
- The chips scroll horizontally when there are too many for the
  screen width.

## v0.25.1 — 2026-04-27

### Tweaks to the Home retard mini-chart
- The household backlog card now removes the initial backlog creation
  phase (same first filtered points as on the Stats screen), for a
  clearer reading of the last 30 useful days.
- The card is repositioned at the very bottom of Home, under urgent
  tasks: it's a contextual reading, not an action to take.

## v0.25.0 — 2026-04-27

### Household backlog on Home
- Home now displays a mini-chart of the household backlog over the
  last 30 rolling days, under the "Pace" and "Backlog" cards. Lets
  you see the recent trend at a glance without going to Stats.
- No cheating on the creation phase for this view: we show the real
  last 30 days, unlike the "Since start" view in stats which begins
  at the peak.

### Period selector on the Backlog chart
- In Stats, the "Backlog" chart gains two chips at the top: "Since
  start" (default, trims the creation phase) and "Last 30 days"
  (recent rolling view).

## v0.24.0 — 2026-04-27

### "This week" recap at the top of stats
- A new card at the top of the Summary tab shows your points for the
  week, your "+/- X% vs last week", the day when the household did
  the most tasks, and the most active category.
- Card automatically hidden if nothing to show (brand new household
  or start of week).

### Household stats in the user profile
- When you tap someone's name (in a history card, ranking, etc.),
  the profile modal now also displays the split between members for
  the week and all-time, with a coloured bar.
- The opened user's name is bold in the legend for instant
  identification.

## v0.23.0 — 2026-04-27

### Enriched statistics
- On the 7-day and 30-day points races, a coloured "+/- X% vs before"
  (green if progressing, red if regressing) compares to the previous
  period.
- A new "Split by category" chart arrives at the last position of the
  swiper: top 8 categories you do the most, with a per-user
  breakdown. Integrated period selector (Week / Month / 3 months /
  All).
- The "By weekday" chart gains the same period selector to zoom on
  recent evolution vs total history.
- The "All-time" points race automatically switches to monthly
  buckets beyond a year of history to stay readable.

### Tap on a chart point
- Touching a point on the points races opens the history filtered on
  the corresponding day directly. Useful to investigate a peak or a
  dip.

## v0.22.0 — 2026-04-27

### Refined notifications for completions
- Three independent settings replace the old "Completed tasks": "When
  someone does a task alone", "When we do a task together (with me)",
  and "When a task is done together without me" (visible only with 3+
  members in the household).
- Collective notifs are personalised: "Alex did the dishes with you",
  "Alex, Sam & you did…", or "Alex & Sam did…" depending on whether
  you participate or not.

### Task history redesigned for collectives
- A "done together" event appears as a single card with all
  participants ("A, B & C") instead of one line per person.
- Group icon distinct from solo completions, and each name is
  clickable to the participant's profile.
- Long-press on a collective deletes the whole event at once.

### Statistics tab redesigned
- Streaks moved up first in Summary.
- New "Statistics" card with five swipeable charts: household backlog
  over time, cumulative points race over 7 days, 30 days, all-time,
  and task frequency by weekday.
- The "Backlog" chart (in 1st position) shows the household backlog
  evolution since the start, on shared tasks only (tasks assigned to
  a single person are excluded). Minutes / Tasks toggle to switch the
  unit. The delta vs start is shown in green when backlog has
  decreased.
- Ranking (Week / Month / Total) merged into Summary — the "Ranking"
  tab disappears.

## v0.21.0 — 2026-04-26

### Recurrence: choose the unit (days, weeks, months)
- On the task edit form, the frequency input unit toggles between
  Days, Weeks and Months via three small buttons. Useful to create a
  monthly task without having to compute "30 days".
- The cadence vote mini-dialog and the rating popup (post-completion)
  also have this unit switch: voting "every 3 months" no longer
  requires mental math.
- When you change unit, the value adapts intelligently (92 d becomes
  "3 months", i.e., 90 d) — the conversion is explicit and the
  breakdown is displayed clearly.

### Human-readable duration display
- Long durations are displayed in the most readable form: "3 months
  2 d" rather than "92 days", "1 wk 3 d" rather than "10 days".
- Visible everywhere cadence appears: task detail screen, edit form,
  vote popup, rating popup, other members' votes.

## v0.20.0 — 2026-04-25

### Auto-archive of one-shot tasks
- One-shot tasks (no recurrence) completed more than 7 days ago are
  automatically hidden from the main list.
- History is preserved: completions remain visible in stats,
  leaderboard and pace.
- No restore button: if an archived task becomes relevant again, just
  recreate it (the duplication autocomplete still suggests it).

### Duplicate an existing task
- New "Duplicate" button at the top of a task's detail screen:
  creates a new task pre-filled with the same parameters, which you
  just need to adjust (e.g., change the room) before validating.
- In the new task creation form, suggestions appear as soon as 2
  characters are typed in the title: selecting a suggestion fills the
  whole form at once.
- A "Duplicated from [title]" chip indicates you're cloning; tap it
  to cancel duplication and start from a blank form.
- The clone inherits the cadence consensus and effort split from the
  original task, but starts with empty history (individual votes are
  not copied, everyone will re-vote on the next prompt).

## v0.19.0 — 2026-04-24

### History: filters and search
- A search bar lets you filter history by task title. Type "sink" and
  you'll only see what touches the "Clean the sink" task.
- A "Filters" button opens a foldable panel with:
  - Event type (3 multi-select chips: Completions, Votes,
    Modifications) to see only one family at a time.
  - Actor (All, by household member) to filter by person.
  - Period (This week, This month, 3 last months, All) to scope the
    time window.
- Filters are combinable and a counter on the "Filters" button
  indicates how many are active. A "Clear" button resets everything.

### History: better readability
- Cadence modifications now display the full transition: "modified
  the recurrence of 'Vacuum' (recurrence): every 7d → every 10d",
  instead of a simple "(recurrence)" without context.
- Permanently deleted tasks keep their title followed by "(deleted)"
  instead of appearing as '?' in history.
- Frequency votes no longer appear in duplicate: before, each vote
  was visible as both a "vote" and a "recurrence modification". Now
  one event per vote.

## v0.18.1 — 2026-04-24

### Tweak
- The slider in the "Edit via vote" mini-dialog now offers a range
  adapted to the task's cadence (from 30% to 300% of the current
  consensus) instead of always showing 1 to 365 days. On a 5-day
  task, you now see a slider from 2 to 15 days, and your current vote
  is visible in the middle of the bar instead of glued to the left.
  If you'd already voted very differently from the current
  consensus, the range is extended to include your value — no silent
  truncation.

## v0.18.0 — 2026-04-24

### Task cadence: finally fully vote-driven
- You can no longer overwrite a task's cadence ("every X days" field)
  by editing directly in the task: the field is greyed and shows
  "Consensus: 7 d (2 votes)". To modify the cadence, an "Edit via
  vote" button opens a dedicated mini-dialog where you can place your
  vote, which enters the consensus calculation with the other
  household members'.
- The mini-dialog shows the current consensus and other members'
  votes ("Alex: 7 d") so you see why the cadence is what it is before
  voting.
- On creation of a "every X days" recurring task, the value you enter
  automatically becomes your initial vote — no more "ghost" cadence
  that matches no vote.
- If you change the recurrence type (e.g., switch from "every 7 days"
  to "variable by month"), your votes are kept dormant. If you come
  back to "every X days" later, they come back to life automatically
  and the cadence is recalculated.

## v0.17.0 — 2026-04-23

### Vacation mode
- Streaks (consecutive days) are no longer broken by absence days:
  if you're on vacation and your days are marked absent (via the
  presence resolution dialog), your streak continues as if nothing
  happened. The counter stays honest: it always shows the real number
  of active days, but the absence no longer breaks the streak.
- Same rule for "Regular" / "Perfect week" / "Two weeks" / "Perfect
  month" badges: they remain unlockable after a vacation period,
  without having to restart from zero.
- Only **explicitly** marked absent days freeze the streak. "To
  confirm" days (uncertain) still break it — think about resolving
  via the dialog on next app launch so your vacation is properly
  counted.

## v0.16.2 — 2026-04-23

### Fix
- When you create a new task, you're no longer prompted instantly to
  rate it (affinity + effort). The app now waits for the task to have
  been done at least 2 times and the last completion to be at least 1
  day ago before offering rating. Avoids the double work "I fill the
  creation form then I fill the rating form" and gives time to
  experience the task to rate it honestly.
- One-shot tasks (no recurrence) are never proposed for rating:
  rating a one-shot task has no long-term value.

## v0.16.1 — 2026-04-23

### Fix
- The daily summary time chosen in Settings is now actually respected
  as soon as you move the slider: move, release, the next notif fires
  at the new time. No more need to kill and relaunch the app.

## v0.16.0 — 2026-04-22

### New
- Button to dismiss a suggestion on the home screen: each card now has
  a small cross at the top right that hides the task and displays the
  next one in the same tier. Dismisses are purely local and reset on
  each app restart.
- When all suggestions of a tier (urgent / affinity / no cadence)
  have been dismissed, a message appears and dismissed tasks become
  visible again — nothing is ever lost.

## v0.15.5 — 2026-04-20

### Fixes
- The daily notification fired at an arbitrary time instead of the
  time set in parameters. Cause: the old schedule created by a
  previous version persisted even after update. Fixed: each version
  bump reschedules the reminder with the time chosen in Settings.

## v0.15.4 — 2026-04-19

### Tweaks
- The frequency vote slider in the rating popup no longer exceeds
  365 days, even when the task had a very long cadence. Avoids
  proposing votes the server would reject.

## v0.15.3 — 2026-04-19

### Fixes
- The "Sync in progress" banner that stayed permanently visible is
  fixed. Some frequency votes entered via the slider (e.g., switching
  to every 120 days) were rejected by the server and the app replayed
  them in a loop without ever giving up, leaving the banner displayed
  indefinitely.
- The cap on frequency votes goes from 60 to 365 days. You can now
  vote for tasks that come back yearly (car inspection, Christmas
  decorations, etc.).

## v0.15.2 — 2026-04-19

### Notifications
- The daily reminder of tasks to do works again: the worker that
  sends the notification wasn't authenticated and systematically
  received a server error, so no notification was produced.
- New: the daily notification time is now respected. Choose the time
  in Settings -> Notifications (slider 6h-22h), the notif will arrive
  around this time the next day. Before, WorkManager picked an
  arbitrary window in the day.
- The "Daily notification" toggle in Settings correctly disables the
  reminder (no more need to restart the app).

## v0.15.1 — 2026-04-19

### Fixes
- Fix for a bug that made some recurring tasks appear as "late" in
  the list when they had been done recently (example: a task every
  45 days done 10 days ago could display as 11 days late). The detail
  screen displayed the right info — only the list was misled.

## v0.15.0 — 2026-04-19

### Task list
- New Solo / Group filter (👤 icon) to separate tasks attributed to me
  alone from shared tasks.

### Task edit
- You can now change the recurrence type while editing an existing
  task (e.g., switch from a regular recurrence to "When necessary",
  or vice versa).
- Tolerance for lateness is no longer displayed for "When necessary"
  tasks — it didn't apply to this type and was confusing.

### Fixes
- Notifications finally work on Android 13+: the app requests
  permission on launch (it was declared but never requested, Android
  silently rejected them).

## v0.14.0 — 2026-04-19

### "No cadence" recurrence
- New choice in the recurrence picker: "When necessary" — for tasks
  done irregularly without fixed rhythm (e.g., laundry, shopping
  list).
- "No cadence" tasks appear in a dedicated 3rd section on home,
  always green, with a factual label ("Not yet done" or "Done X d
  ago") instead of a countdown.
- The app automatically learns how often you do this task and ranks
  it at the top when it hasn't been suggested to you for a while.
- Vacation freeze still applies (absence days don't count).

### Task detail: respect for assignments
- On a task assigned to a single person, the detail screen now
  displays a "For one member only" banner (with their first name) at
  the top.
- The other person no longer sees their own "Effort & Affinity"
  insert for this task (it was misleading: rating a task you don't do
  doesn't make sense).
- The "Edit" button for affinity/effort rating is hidden for anyone
  not assigned.

## v0.13.1 — 2026-04-18

### Search fix
- The search field cursor went backwards when typing fast: fixed.
  Typed text display is now synchronous, independent of the time
  filtering takes in the background.

## v0.13.0 — 2026-04-18

### Search in tasks
- New magnifying glass in the Tasks screen, top right of the filter
  row. Clicking the magnifying glass replaces the filter chips with
  a search field that takes the full width.
- By default, search ignores all active filters (including "To do")
  and searches in all tasks.
- A small funnel button next to the field lets you reapply current
  filters if needed.
- Search matches title, description, category name and room name.
  Accent-insensitive (typing "ecole" finds "École") and
  case-insensitive.
- Multi-word search: "kitchen laundry" finds tasks containing both.

### Presence detection overhaul
- The automatic background detection (which required the app to run
  permanently) is removed: Android throttled it too much for it to
  be reliable.
- New behaviour: the app detects your presence on launch when it
  sees the home Wi-Fi. Days when nobody launched the app are marked
  "to confirm".
- New resolution dialog: when days are uncertain, the app offers on
  launch a calendar grid where each day can be marked present (green)
  or absent (grey). For long absences (>30 days), a simplified mode
  "I was absent since X" appears instead.
- Background location permissions are therefore no longer necessary
  — you can remove them in Android settings if you want.

## v0.12.0 — 2026-04-17

### Points bonuses and penalties
- Late tasks now earn a points bonus (+50% by default) when they
  exceed a configurable threshold. A "🔥 +50%" pill appears on
  affected suggestions.
- Conversely, completing a task very early applies a progressive
  penalty (up to -50% of points). The earlier you do it in the
  cycle, the stronger the penalty.
- The first completion of a task always gives full points (no bonus
  or penalty).
- The "Already done" / "Done together" dialog now displays a points
  preview line before validation: you see how much you'll earn and
  why (late bonus, early penalty, first time).
- The celebration after validation displays the earned points (+X
  pts) with the reason for the bonus or penalty.
- A task's detail sheet shows the current bonus or penalty next to
  the urgency state.

### Detection of tedious tasks
- When a task is systematically late (more than 70% of completions in
  the bonus zone over a rolling window), the app automatically offers
  each household member to bump the task's effort by +1.
- The bump modal displays the list of recent completions with their
  status, and offers either a quick bump or to edit the ratings
  (affinity/effort).
- A single prompt is displayed on app open (no spam). After a prompt,
  2h minimum of silence.

### Advanced settings
- New "Points bonuses / penalties" panel in Settings Advanced, with
  all thresholds configurable per household (14 sliders with
  explanations).

## v0.11.1 — 2026-04-15

### End of the "flash" after a completion on home
- Before: after validating a task on home, the celebration animation
  played, then the same task briefly reappeared for 1-2 seconds
  before being replaced by another. This was due to the server not
  yet having seen the validation (sync in progress) and returning the
  same suggestion.
- Now: home loads a buffer of multiple suggestions ahead in the
  background (5 urgent + 5 affinities). When you validate a task, the
  next one appears instantly after the animation, without waiting for
  the server. The task you just validated is automatically hidden
  client-side until sync catches up.
- In case of a completion burst (5 in a row without sync keeping up),
  a small "Next suggestion on the way..." card displays while the
  backend catches up, rather than showing emptiness or a fake task.

## v0.11.0 — 2026-04-15

### "Done together" — share tasks and points
- New **Done together** button (group icon) on each task, present on
  home, the task list and the detail view. It lets you say "we didn't
  do it alone": points are shared equally between participants and
  each one progresses their counter.
- In a 2-person household: clicking opens a date selector directly,
  both members are automatically included (no need to tick).
- In a 3+ person household: a popup appears with the assigned members
  pre-ticked, an "Add a member" button to include someone else from
  the household, and a date selector. Minimum 1 ticked participant to
  validate.
- If someone has already validated the task in the anti-duplicate
  window, the collective validation is refused as a whole (no row
  created) and a message explains.

### "Already done" on the task detail view
- The **Already done** button (calendar icon) is now also present on
  the task detail screen, in addition to home and the list. Until
  now it was missing from this location.
- In the "Already done" popup, a new "With others..." button lets you
  switch to the "Done together" flow without starting over.

### Unified completion popup
- The two old separate popups (choose who then choose when) are
  replaced by a single clear popup with the date and participant list
  in the same place. Faster to validate.

### Home cleanup
- The "In progress" section of home (which displayed started but
  not-yet-validated tasks) is removed. It was almost never used and
  cluttered the screen.

## v0.10.2 — 2026-04-15

### End of crashes after update
- The app now automatically purges its local home screen cache on
  each new version install. This avoids crashes like the one in
  v0.10.0: when the data format changes from one version to another,
  the old cached content could crash the app on first launch. Now,
  the first open after an update restarts from an empty cache and
  refills on the first network refresh.

## v0.10.1 — 2026-04-15

### Startup crash fix
- Fix for an app startup crash after the v0.10.0 update (screen
  closing immediately on app open). Cause: old suggestions still in
  local cache didn't have the new state field; the app tried to
  display them before refresh and crashed. Now rendering is tolerant
  to this case and displays neutral while waiting for the refresh.

## v0.10.0 — 2026-04-15

### Freshness model rework — no more half-life, hello due dates
- Before: each task had a "freshness" score that decayed
  exponentially (half-life). Problem: not intuitive. A "litter every
  2 days" task done 4 days ago was still only orange, while mentally
  it's "2 days late".
- Now: each task has a **due date** (`due_date = last_completion +
  interval`) and the state is calculated in days:
  - **Green**: not yet due (far from the due date)
  - **Orange**: due today or soon (window of `floor(interval * 0.10)`
    days before due date — for 7d it's 0 days, for 30d it's 3 days,
    for 60d it's 6 days)
  - **Red**: late (after the due date)
- On cards: colour + countdown text ("in 3d", "to do today", "late by
  2 d") shown systematically.
- Along the way, the original scenario is corrected: litter 2d done
  on April 11 -> red "late by 2 d" on April 15 (before: still orange,
  which made no sense).

### Tolerance repurposed — only for the "most urgent" selector
- Tolerance (STRICT / NORMAL / LOOSE) no longer changes anything in
  the displayed state of a task nor when it becomes late.
- Its only role: prioritise the "most urgent task" selector on the
  home page:
  - STRICT late goes before NORMAL late, which goes before LOOSE
    late. Within a tier, we pick the one with the largest ratio
    (days late / interval).
  - If no task is late, fallback on Oranges (all tolerances mixed,
    closest to due date first).
- An ℹ bubble next to the tolerance selector (in detail/edit/create)
  explains all this.

### Cleanup
- Removed the "Suggestion threshold" knob in advanced settings (the
  model is now deterministic, no need to configure it).

## v0.9.0 — 2026-04-14

### Template catalog
- New "Catalog" screen accessible via a small magic button next to
  the "+" in the tasks screen. It offers a library of ready-to-use
  tasks, rooms and categories (dishes, vacuum, clean the bathroom,
  kitchen, living room, cleaning, laundry, ...) in three tabs.
- Multi-selection (you tick what you want) and "Import N" button at
  the bottom. When the tasks you import reference rooms or
  categories, an attachment popup offers either to create them or to
  map them to existing elements of your household (with automatic
  detection of similar names — "Kitchen" vs "kitchen" vs "The
  kitchen" are detected as the same).
- Useful to populate an empty household in a few clicks, or to
  inspire you and add tasks you wouldn't have thought of.

## v0.8.1 — 2026-04-14

### Household goal proposal
- The simulation popup (tap on the "Me" or "Household" cards) now
  contains a slider per household member: you can simulate a full
  plan by adjusting each person's rhythms and see live how many days
  the backlog would be caught up. The "Just for me" button is still
  available to save only your goal.
- New "Propose to household" button (active as soon as you move at
  least one other member's slider): it sends your proposal live to
  the rest of the household. The others receive a blocking modal with
  your proposal, they have 3 minutes to accept or refuse. Meanwhile,
  you see a waiting screen listing live who has approved, with a
  "Cancel proposal" button if you change your mind.
- When everyone accepts, the goals are saved for each one and a
  celebration popup appears on all members' apps ("You're on your
  way to a clean household!") with an easter egg to discover by
  clicking the link at the bottom.
- If someone refuses or the deadline expires, the proposal is
  cancelled, no goal is saved, and everyone is notified.

## v0.8.0 — 2026-04-14

### Backlog catch-up forecast
- Two new cards at the top of home: "Me" (minutes done today,
  progress bar towards the daily goal, estimated number of catch-up
  days) and "Household" (current household backlog, number of days
  to reach zero, maintenance rhythm once cleared).
- Simulation popup: tap on a card opens a slider to set your target
  rhythm in minutes or tasks per day, with live feedback on the
  number of catch-up days. The "Set as goal" button saves your
  personal commitment.
- The display unit (minutes or tasks) is choosable in the popup and
  saved per household.

### More compact Home
- Removed the red border and progress bar on task suggestions: the
  red pill was already enough to signal urgency.
- Removed the "Suggestions" title (obvious from context).
- Moved the "You've done more than your share..." message from home
  to the "Summary" tab of Stats — so that task cards and forecast
  cards fit on a single screen.

## v0.7.4 — 2026-04-14

### Pausing "at home" tasks during absence
- When nobody is detected on the home Wi-Fi for more than 24h, tasks
  in categories marked "at home" stop aging until return. Beyond the
  24h tolerance, the counter is frozen — no more backlog accumulated
  during vacations.
- "Welcome back home!" popup on return, with the number of absence
  days and a reminder that affected tasks were paused.
- Automatic Wi-Fi presence detection in the background every 6h and
  on app launch.

### Household setting
- "Edit" button on each household (admins only) in Settings > My
  households: lets you rename the household and enable/disable the
  "at home" task pause during absence.

### Adaptive "at home" tooltip
- The info tooltip next to "At home only" (category editing) now
  explains the actual freeze state: if the pause is disabled, if you
  haven't configured your home Wi-Fi, if we're waiting for your first
  connection, or if other household members are still blocking
  detection.

## v0.7.3 — 2026-04-14

### Multi-household: users + home wifi isolation
- The user lists in pickers (task assignment, "who did it?",
  fair-share, stats, leaderboard) only display members of the active
  household. Before, all base users appeared even in a household they
  weren't in.
- Home Wi-Fi SSIDs are now stored per household: at your home you
  configure your SSIDs, at a friend's coloc you configure others,
  without mixing. When you switched household, the other household's
  SSIDs appeared — fixed.

## v0.7.2 — 2026-04-14

### Multi-household: per-household isolated stats
- Statistics (points, completed tasks, leaderboard, streaks, badges,
  history) no longer mix data from all your households: each stats
  screen displays only the figures of the active household.
- Earned badges are specific to each household (the same badge can
  be earned in multiple households separately).

## v0.7.0 — 2026-04-14

### Multi-household (invisible but fundamental rework)
- The app is now ready to manage multiple households: you can create
  a new household (for example for a friends' coloc) and switch
  between your households without losing the data of the other.
- New **Settings → My households** screen: you see the list of
  households you belong to, you can create a new one via the + button
  and switch by clicking on it. On switch, the app empties its local
  cache and reloads the new household's data (a few seconds).
- For now existing members share a single "Home" household — all
  existing tasks, categories and rooms were automatically migrated to
  it.
- Inviting friends by QR code to create a shared household will be
  added in a future version (it requires public deployment
  infrastructure).

### Visible one-shot tasks
- A task created without recurrence (one-shot) now appears in the
  "to do" list and in home suggestions as long as it hasn't been done.
  Before, it was invisible until the "to do" filter was disabled.

### Properly saved room
- When you create or edit a task and assign it a room, the room is
  now properly saved. Before, the chosen room could be lost after
  save.

### Less intrusive rating popup
- The popup that asks you to rate the affinity/effort of a task no
  longer reappears every time you return to home. It displays on app
  open and on return from multitasking, then no more until the next
  session.

## v0.6.2 — 2026-04-13

### Congratulations on each validated task
- When you validate a task (anywhere: home, list or detail sheet,
  and whether "done" or "already done"), the card now displays a
  congratulations emoji ("Well done!", "Nice!", etc.) and confetti
  falls on the screen for 2 seconds before the task disappears /
  next one appears. No more invisible validation.

## v0.6.1 — 2026-04-13

### Rooms visible everywhere
- On home, the "Suggestions" and "In progress" cards now display the
  room (with its emoji if you've defined one) and the category as a
  coloured pill under the title.
- Very late suggestion cards have a red border around to attract the
  eye.
- The task detail sheet also displays the room (read-only — to modify
  it, use the pencil at the top right).
- The rating popup displays the room under the task title for visual
  reminder.

## v0.6.0 — 2026-04-13

### Rooms: new management
- House rooms are now first-class entities (Kitchen, Bathroom,
  Bedroom, Living room, etc.) instead of being hidden in task titles.
- New "Manage rooms" screen in Settings: add, edit, delete your
  rooms. Each one has a name, a colour and an optional emoji.
- When you create or edit a task, a "Room" selector appears next to
  "Category".
- Room filters in the list now display the emoji next to the name.
- In the task list, the room name (with its emoji if defined) appears
  under the title.
- Automatic migration: your 29 existing tasks were linked to rooms
  extracted from their titles. Titles have been cleaned of the
  " — Room" suffix. The 13 tasks without suffix remain without room,
  you can edit them to assign one.

## v0.5.8 — 2026-04-13

### Task list
- No more superfluous air at the top of the screen: the 1st filter
  row actually sticks to the Android status bar (double padding
  correction).

## v0.5.7 — 2026-04-13

### Task list
- Even smoother scroll (internal optimisations: Compose can now
  detect unchanged cards and not redraw them unnecessarily when the
  list updates).

## v0.5.6 — 2026-04-13

### Task list
- Top margin of the list reduced: the 1st filter row sticks directly
  below the Android status bar (before: too much air at the top).
- Significantly smoother scrolling thanks to several internal
  optimisations (filtering pipeline offloaded outside the main
  thread, callbacks stabilised to avoid unnecessary recompositions).

## v0.5.5 — 2026-04-13

### Task list: visual rework
- The purple "Torchons & Serviettes" banner at the top was removed —
  it took space without offering anything clickable.
- New thin and discreet sync bar at the very top, appearing during
  loads (instead of a big full-screen spinner that masked the tasks).
- Spacing between filter rows revised: more air at the top, uniform
  spacing between rows.
- Simplified task cards: we now only keep the name, the room and a
  late indicator as a coloured border on the left
  (green/yellow/orange/red). No more category badge, stars, progress
  bar or "Red X%" text. Cards are more compact, we see more of them
  on screen and scrolling is smoother.

## v0.5.4 — 2026-04-13

### Task list
- On app startup, the "🔥 To do" filter is now active by default: you
  see urgent tasks directly, without scrolling through the whole
  list. You can disable the filter by tapping the red chip if you
  want to see all tasks.

## v0.5.3 — 2026-04-13

### Task list
- Swipe to validate a task is removed — it sometimes triggered
  accidental validations.
- The two action buttons on each card (calendar "already done" and
  check "validate") are larger, with a coloured background (green to
  validate, grey for the calendar) and clearly identifiable as
  clickable buttons.
- List scrolling is significantly smoother, especially when there
  are many tasks.

## v0.5.2 — 2026-04-13

### Unified rating
- The "Edit" button on a task's sheet now opens the same rating popup
  as the home one — with affinity, effort AND frequency in a single
  screen.
- Previous values are correctly preloaded (before, the "Edit" button
  always displayed 5/10 and 15min by default).
- Display of last rating date above the settings: "Last rating 3
  days ago" / "Never rated".

### Frequency
- The frequency of an existing task can no longer be modified from
  the task edit screen: you must go through the rating popup
  (everyone votes their frequency and the average is used).
- On task creation, the choice of frequency remains available.
- The effort slider is capped at 1h max (before: 1h30 on the detail
  sheet, 1h on home — inconsistent).

## v0.5.1 — 2026-04-13

### Fixes
- The app no longer crashes on startup after the v0.5.0 update (local
  schema incompatibility on the history table).

## v0.5.0 — 2026-04-13

### Modification history
- New "Modification history" section in a task's detail sheet, below
  the completion history: shows who voted what and how the consensus
  evolved (affinity, effort, frequency).
- Each vote displays with its impact on the average: for example
  "Alex voted 7d frequency → consensus 6d" or "Sam voted 3/10
  affinity → consensus 4/10".
- Direct modifications to the task (title, recurrence, category,
  tolerance, assignment changes) also appear in this timeline.

### Votable frequency
- When the rating popup offers to adjust a task's frequency, it's
  now an individual vote: each participant has their own value and
  the displayed consensus is the average of votes.
- Before, the last person who adjusted the frequency overwrote the
  value for everyone — no longer the case.

## v0.4.1 — 2026-04-12

### Fixes
- Settings (home Wi-Fi, notifications, suggestion threshold, etc.)
  are now correctly saved — before, changes could be lost on app
  reopen.
- The affinity rating popup no longer appears in duplicate on app
  open.
- The "Another one" button in the rating popup works: it properly
  moves to the next task instead of simply closing the popup.
- The "?" button now displays a different task on each use (before,
  it always showed the same one).
- The rating popup displays the number of remaining tasks to rate.
- When all tasks are already rated, a message confirms it instead of
  doing nothing.

## v0.4.0 — 2026-04-12

### Whole app works offline
- The Tasks and Task detail screens now display the last known data
  even when the backend doesn't respond (before, they stayed empty).
- The "Offline" banner displays the date and time of the last data
  ("data from 04/12 14:32").
- When the connection returns, data refreshes automatically without
  having to close and reopen the screen.

### Task creation and validation without network
- You can now create, edit, delete and validate tasks even without
  backend connection.
- Operations apply instantly in the app and synchronise automatically
  when the connection returns.
- An "X operation(s) pending" badge displays in the banner when
  operations haven't yet been sent to the server.
- When sync happens, a "Sync in progress..." indicator briefly
  displays.

## v0.3.0 — 2026-04-11

### Offline mode on home
- Home now displays the last known data even when the backend doesn't
  respond, instead of remaining stuck or lying with "Everything's
  fresh!" when nothing could load.
- When the backend is unreachable, an orange "Offline" banner appears
  at the top of the app with a "Retry" button.
- When the backend comes back (e.g., after server restart), the app
  detects it automatically and the banner disappears on its own.
- If home really has no data to display due to no backend, it
  displays a clear "Data unavailable" message instead of the
  misleading "Everything's fresh!".

### Faster startup
- The app no longer waits 30 seconds blank on startup if the backend
  is slow or unreachable. After a few seconds it switches directly
  to offline mode.

## v0.2.2 — 2026-04-11

### Lateness tolerance on recurring tasks
- You can now choose, for each recurring task, how strict the app is
  about lateness: Strict, Normal or Loose. The choice is in the task
  edit form, just after the recurrence.
- Strict: the task turns orange/red before the due date.
- Normal (default): the task is yellow at the due date — current
  behaviour.
- Loose: the task stays green even a bit after the due date.

### Smarter fair-share congratulations
- The "You've done more than your share" message on home now arrives
  only when you've done *really* more than your share, not at the
  slightest gap. By default you must have done 30% more than your
  target ratio.
- This tolerance threshold is adjustable in advanced settings
  ("Fair-share tolerance", from 0% to 100%).
- The message no longer displays when the app doesn't have enough
  data (less than 10 completed tasks total over the last 30 days).
- The message text now says "these last 30 days" instead of "this
  month", more accurate compared to the actual calculation.

## v0.2.1 — 2026-04-11

### Maintenance
- Internal improvements to sensitive configuration storage.
  Transparent for the user.

## v0.2.0 — 2026-04-11

### Powerful filters on the task list
- Five quick filters at the top of the list: "To do" (red), Affinity
  (loved / hated), Effort (long / short), Recurrence (recurring /
  one-shot), Home (at home / outside home).
- Each filter is tri-state: one click activates "top", two clicks
  activates "bottom", three clicks disables.
- Per-room filter, automatically extracted from task titles (e.g.,
  "— Kitchen", "— Living room").
- Ability to select multiple categories at the same time instead of
  just one.
- Empty categories are no longer displayed in the filter bar.
- A small recap of active filters appears above the list.

### Enriched rating popup
- The affinity and effort popup now pre-fills with your last rating
  (or the household average if you've never rated).
- For a one-shot task, you can transform it into a recurring task
  directly from the popup, and choose the frequency.
- For an already recurring task, you can adjust its frequency (from
  50% to 200% of the current value).

### "Who did this task?"
- When you validate a task in "already done" mode (past date), a new
  selector asks you who (among members) did it, in addition to the
  date.
- The selector is automatically skipped if only one person is
  assigned to the task.

### Clickable user profile everywhere
- You can now click on the name of any member anywhere in the app:
  history feed, ranking, stats summary, streaks, task completions.
- A modal opens with their personal history, their number of
  completions and total points.
- Tasks in this history are themselves clickable to go to their
  detail.

### Cleaned history
- No more duplicates on affinities and efforts: saves without real
  change no longer pollute the flow.
- When a task is modified, the feed now precisely indicates which
  fields changed (title, category, recurrence, etc.) instead of an
  opaque "modified".
- Clicking on a feed card opens the corresponding task directly.

### Anti-double-validation
- Impossible to validate the same task twice in the same hour.
- For weekly or longer tasks, blocking is on the whole day.
- Avoids accidental double taps on the "Done" button.

### What's new & Roadmap in the app
- New "What's new" entry in Settings.
- Displays the current version, release notes and roadmap of planned
  features.

### Cosmetics
- New app icon: a wizard's hat on a mop, on a starry midnight blue
  background.
- The app version displayed at the bottom of home and in Settings is
  now read dynamically (no more hardcoded "v1.0.0").

## v0.1.0 — 2026-04-08

### First version
- CRUD tasks with recurrence (interval, weekdays, monthly, custom).
- Categories, assignment, completions with points.
- Bidirectional offline sync.
- Gamification: freshness score, streaks, leaderboard, badges.
- Fair-share: configurable ratio per couple.
- Firebase push notifications + local reminders.
- Initial import of 39 tasks from the Home Assistant config.
