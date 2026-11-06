# digipaws self-build

A self-built, self-signed copy of [digipaws](https://github.com/WAYYYAW/digipaws),
an open-source Android blocker for distracting apps, used for scheduled app locks
on my phone. A "soft" lock on purpose: it is easy to get around if I really want
to, but no third party gets to see what I do on the phone.

Why this one, and what was checked: [AUDIT.md](AUDIT.md). The short version:
the APK has **no INTERNET permission**, so even with Accessibility access it has
no way of its own to send anything anywhere — and `scripts/build.sh` refuses to
produce an APK that asks for it.

## Repository

This is a private copy of upstream with its full history (GitHub does not allow
private forks of public repos): `origin` is `Jonas-Marty/digipaws`, `upstream` is
`WAYYYAW/digipaws`. Everything outside `selfbuild/` is upstream at the audited
commit plus our own patches in `selfbuild/patches/` (see [Patches](#patches)); the
build refuses to run if the code is anything else.

## Layout

| Path | What |
|---|---|
| `selfbuild/UPSTREAM` | upstream repo, audited commit and flavor that get built |
| `selfbuild/docker/Dockerfile` | the Android build environment (JDK 17, SDK 34) |
| `selfbuild/patches/` | our changes to the app, as reviewed diffs against `COMMIT` |
| `selfbuild/scripts/build.sh` | check source == commit + patches → build → sign → verify → `selfbuild/out/` |
| `selfbuild/scripts/publish.sh` | build, publish to `apk.wi-wo.ch`, send Gotify link |
| `selfbuild/out/` | signed APK, its sha256, permissions, signer cert |

Nothing is installed on the server: everything Android runs in the
`digipaws-android` image, removed after each run (`--rm`). The Gradle cache is the
named volume `digipaws-gradle`, so only the first build is slow (~3 min).

## Build

```bash
selfbuild/scripts/build.sh keygen   # once: release key in ~/.keystores/digipaws.jks
selfbuild/scripts/build.sh          # build selfbuild/out/digipaws-<version>-<code>-<upstream>-<fork>.apk
selfbuild/scripts/build.sh test     # unit tests (incl. the weekday schedule tests)
selfbuild/scripts/build.sh bash     # shell in the build container
```

The build:

1. checks that every file outside `selfbuild/` (tracked or untracked) equals
   `COMMIT` with `selfbuild/patches/*.patch` applied in order; any difference is
   printed and the build refused;
2. runs `./gradlew assembleLiteRelease` in the container — upstream leaves
   release unsigned, so no upstream file needs touching;
3. signs with `apksigner` using the local key (v1–v3, no v4 `.idsig`);
4. dumps the permissions with `aapt2` into `out/permissions.txt` and **fails
   if `android.permission.INTERNET` is among them**;
5. names the APK after the version read from the APK itself.

The `lite` and `play-store` flavors are identical in this commit (the
`FDROID_VARIANT` flag they set is never read); `lite` is the one F-Droid builds.

### Signing key

`~/.keystores/digipaws.jks`, password in `~/.keystores/digipaws.pass` (both
`600`, outside this folder). **Back both up.** An APK signed with a different
key cannot upgrade the installed one — the phone would have to uninstall first,
losing the app's settings. `keygen` refuses to overwrite an existing key.

Current signer SHA-256: `1e5e20be3e3997c1f7233bece5cf7c24520a00aa7ef1d3a396bc5ec5d3435776`
(also in `out/cert.txt`).

## Publish to the phone

```bash
selfbuild/scripts/publish.sh             # build, publish, notify
selfbuild/scripts/publish.sh --no-build  # publish what is already in out/
selfbuild/scripts/publish.sh --quiet     # no Gotify notification
```

Same path as FinReader: the APK is copied into `~/finreader-apk`, which the
`finreader-apk` nginx compose serves at <https://apk.wi-wo.ch> behind Authentik.
`digipaws-latest.apk` points at the newest; the last 5 builds are kept. The
index page is written by `/work/fin-reader-app/scripts/apk-index.sh`, shared
with FinReader, and shows one section per app (<https://apk.wi-wo.ch/#digipaws>).
A Gotify message (FinReader's app token) carries the download link.

Overrides: `DIGIPAWS_PUBLISH_DIR`, `DIGIPAWS_APK_URL`, `DIGIPAWS_INDEX_SCRIPT`,
`DIGIPAWS_GOTIFY_URL`, `DIGIPAWS_GOTIFY_TOKEN_FILE`, `DIGIPAWS_KEEP_BUILDS`.

Upstream's `versionCode` is fixed (23) and does not change per build. Android
installs the same versionCode over itself, so rebuilds still install; the
filename carries the commit to tell them apart.

## Phone setup (least privilege)

1. Install from the Gotify link (allow "install unknown apps" for the browser once).
2. Grant **Usage access** and **Display over other apps** — needed for the block
   screen and stats.
3. In Accessibility, enable **only "App Blocker"**. It listens for app switches
   only. Leave Keyword Blocker, View Blocker (reels/shorts) and Usage Tracker
   **off** — those read screen content.
4. Skip Shizuku (only used for grayscale) and device admin (anti-uninstall).
5. Schedules: **Setup Auto-Focus**, one entry per time window, each with its own
   days (patch 0001). Example, a split workday plus a short Friday:

   | Entry | Time | Days |
   |---|---|---|
   | Work morning | 07:30–11:30 | Mon–Thu |
   | Work afternoon | 12:30–16:30 | Mon–Thu |
   | Friday | 08:00–12:00 | Fri |

   App Blocker cheat hours take the same day selection.
6. Optional check: Settings → Apps → digipaws should show no mobile data /
   Wi-Fi usage entry, since it has no network permission.

Tailscale and NordVPN are unaffected: digipaws uses no VPN service.

## Patches

Our own changes to the app live in `selfbuild/patches/`, numbered, one feature
each. The code changes are committed in this repo as well, and the build checks
that the code matches `COMMIT` + the patches exactly, so the patch files are a
complete, reviewable list of how this build differs from upstream.

| Patch | What |
|---|---|
| `0001-weekday-schedules.patch` | Auto-Focus and cheat-hour entries get their own days (Mon–Sun chips in the add dialog; the list shows e.g. "Mon, Tue, Fri"). Entries saved before the patch keep applying every day. A range that crosses midnight belongs to the day it starts on. Also fixes upstream's negative "blocked until" time for such ranges. Logic in `utils/DaySchedule.kt`, tests in `DayScheduleTest.kt`. |

Changing app code: edit it, run `selfbuild/scripts/build.sh test`, then
regenerate the patch so it matches the code again (one patch file for now):

```bash
source selfbuild/UPSTREAM; t=$(mktemp -d)
GIT_INDEX_FILE=$t/i git read-tree $COMMIT
GIT_INDEX_FILE=$t/i git add -A -- . ':(exclude)selfbuild'
git diff --binary $COMMIT "$(GIT_INDEX_FILE=$t/i git write-tree)" > selfbuild/patches/0001-weekday-schedules.patch
rm -rf $t
```

## Upgrading upstream

1. `git fetch upstream && git log --oneline <COMMIT>..upstream/main`
2. Re-run the checklist in [AUDIT.md](AUDIT.md) on `git diff <COMMIT> upstream/main`.
3. `git merge upstream/main`. The build refuses from now until step 5.
4. Resolve any conflicts in the patched files. Then check that the patches
   still apply to the new commit:
   `git apply --check --cached` against a temporary index of it (`build.sh`
   says which patch fails), or regenerate them as described under
   [Patches](#patches).
5. Set `COMMIT` in `selfbuild/UPSTREAM` to the audited upstream commit, note
   the audit in AUDIT.md, run `build.sh test`, commit,
   `selfbuild/scripts/publish.sh`, push.
