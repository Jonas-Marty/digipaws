# digipaws audit

Audit of `WAYYYAW/digipaws` @ `15b75e9` (2025-06-26, "Add Turkish"), done
2026-11-06 before the first self-build. Goal: a scheduled app blocker that can
be trusted with Accessibility access, unlike Opal.

## Why the INTERNET permission is the thing to check

Every Android app blocker needs Accessibility or Usage Access, because that is
the only way to learn which app is in the foreground. That access cannot be
avoided, so trust comes down to whether the app can **send** what it sees. An APK
without `android.permission.INTERNET` cannot open a socket. What is left is
handing data to another app through an intent, which the code below does not do.

`selfbuild/scripts/build.sh` checks the permission on the **built APK** (aapt2), not on
the source, so a dependency that merges INTERNET in would also be caught.

## Findings

**Permissions in the built APK** (`out/permissions.txt`):
`PACKAGE_USAGE_STATS`, `POST_NOTIFICATIONS`, `QUERY_ALL_PACKAGES`,
`RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
`SYSTEM_ALERT_WINDOW`, Shizuku `API_V23`, plus AndroidX's internal
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. **No INTERNET, no
ACCESS_NETWORK_STATE.**

**Network code:** none. The only network-looking hit is `KeywordBlocker`, which
reads the *title* of an on-screen `android.webkit.WebView` node through
Accessibility. It does not load anything.

**Shell (Shizuku):** `ShizukuRunner` runs `sh -c` through Shizuku, and is only
called from `MonochromeTools`, with fixed `settings put/get secure
accessibility_display_daltonizer*` commands (grayscale). It is optional and only
works if Shizuku is installed and granted.

**Accessibility services** (each one is enabled separately on the phone):

| Service | Events | Needed for scheduled app blocking |
|---|---|---|
| App Blocker | `typeWindowStateChanged` (app switches) | **yes**, the only one |
| Keyword Blocker | window content changes | no |
| View Blocker (reels/shorts) | content, scroll, focus | no |
| Usage Tracker | content, scroll | no |
| General Features (anti-uninstall, grayscale) | content and state | no |

All of them declare `canRetrieveWindowContent`, but only the services you
enable get any events.

**Storage:** `SharedPreferences` only, private mode. `BackupTools` exports and
imports those prefs as JSON to a file you pick yourself.

**Other:** a `DeviceAdmin` receiver (anti-uninstall, optional), two home-screen
widgets, and a `FileProvider` for the backup file. `WarningActivity` sends an
in-app broadcast to the app's own services.

**Dependencies:** AndroidX core/appcompat/activity/constraintlayout, Material,
Gson, MPAndroidChart (from JitPack), `nl.joery.timerangepicker`, Shizuku
api/provider. There is no analytics, crash reporting or HTTP client. JitPack builds
from a GitHub tag, which is the weakest link in the supply chain. Because the APK
has no INTERNET permission, even a bad dependency could not reach the network.

**Size:** about 8.4k lines of Kotlin in a single module, small enough to read
in full.

**Maintenance:** last commit June 2025, no release tags. That's fine for a
pinned build, but don't expect fixes for new Android versions.

## Alternatives considered (2026-11-06)

- **Mindful** (`akaMrNagar/Mindful`, Flutter): richer schedules (bedtime,
  groups, active periods). It declares INTERNET, though only for its
  local blackhole VPN. It also wants notification-listener access and has a
  broader Accessibility config. Its internet-blocker VPN would knock out
  Tailscale/NordVPN. Rated 7/10.
- **flint-app**: 44k lines in 20+ modules, plus Firebase in debug builds. v1 is
  days old with no device verification. Rated 5/10.
- **Android Focus mode** (Digital Wellbeing): built in, no extra trust needed.
  Use it as the fallback.

## Checklist when moving `COMMIT`

Run these at the repo root, with OLD = current `COMMIT` and NEW = `upstream/main`:

```bash
git diff OLD NEW --stat
git diff OLD NEW -- app/src/main/AndroidManifest.xml app/build.gradle.kts gradle/libs.versions.toml settings.gradle.kts
git diff OLD NEW -- app/src/main/res/xml/
git grep -nE 'HttpURLConnection|URL\(|OkHttp|Retrofit|Socket|DexClassLoader|loadClass|Runtime.getRuntime|newProcess|ACTION_SEND|EXTRA_TEXT|Base64' NEW -- app/src
```

- New permissions, services, receivers or providers in the manifest?
- New dependencies or repositories (any new `maven {}` is a red flag)?
- New Shizuku commands beyond grayscale?
- Read any new Kotlin file in `services/` and `blockers/` in full.

The build still refuses INTERNET on its own. The checklist covers the parts the
build can't check.
