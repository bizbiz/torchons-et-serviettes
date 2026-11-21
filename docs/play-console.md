# Play Console: the settings we chose not to enable

Version française : [play-console.fr.md](play-console.fr.md).

Every Play Console setting we read and decided to leave as is gets written here, with its
reason, so the discussion is not replayed at each release. A setting we change is written here
too, with its date.

## Test and release → Advanced settings

Read on 2026-11-21 (v0.47.0 on Internal testing), decided with Quentin the same day.

| setting | state | decision |
|---|---|---|
| Inline installs | off | leave it alone |
| App optimisation | off | leave it alone until the official launch |

**Inline installs.** This setting lets other apps be installed from inside ours: it requires
integrating the Google Play inline install API, meeting eligibility criteria and accepting
terms of service. T&S has no other app to get installed: nothing to gain.

**App optimisation.** When on, Play may modify the APKs it generates from our app bundles "to
improve performance, size and security"; the Play App Signing terms grant it that license for
the whole life of the app. When off, Play generates standard APKs. The gain is vague for an app
of a few MB, and the cost is precise: the APK installed from the store would no longer be the
one we built and tested. A defect seen only on the store version could no longer be told apart
between our code and Play's transformation. The `beta` APK installed by hand is not affected.
To revisit at the official launch.
