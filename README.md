# BK Traffic Control

Reliability-focused rebuild of the Brahma Kumaris "Traffic Control" app: plays
a fixed track at 9 set times every day (3:30, 4:00, 5:45, 7:00, 10:30am,
12:00pm, 5:30, 7:30, 9:30pm). Rebuilt because the original app was missing or
mistiming alarms on Samsung, Xiaomi/MI, and Motorola phones.

## Why this exists / what changed

Standard Android `AlarmManager.setExactAndAllowWhileIdle()` alarms get
deferred by Doze, and on Android 12+ need a permission most sideloaded apps
never get granted. On top of that, most OEMs (Xiaomi, Samsung, Oppo, Vivo,
OnePlus, Huawei...) ship a *second*, undocumented battery manager that can
kill a whitelisted app outright.

This app addresses both:

- Every alarm uses `AlarmManager.setAlarmClock()` — the one AlarmManager API
  that's never deferred by Doze on any OEM and never needs the Android 12+
  exact-alarm permission (see [`AlarmScheduler.kt`](app/src/main/java/org/brahmakumaris/trafficcontrol/alarm/AlarmScheduler.kt)
  for the full rationale).
- Boot / app-update / clock-change / timezone-change receivers re-arm every
  alarm (`BootReceiver.kt`).
- A 15-minute WorkManager job re-arms anything that silently disappears
  (`AlarmRefreshWorker.kt`) — defense in depth, not the primary mechanism.
- A first-run **Setup** screen walks the user through notifications, the
  standard Android battery-optimization exemption, and a deep link straight
  into their phone brand's own autostart/background manager
  (`OemSettings.kt`), plus a "test alarm in 1 minute" button so reliability
  can be checked immediately instead of waiting until 3:30 AM.

## Project layout

```
app/                      the Android app (Kotlin, View-based UI, no Compose)
reference/original-app/   the OLD app's APK + screenshots, kept for provenance
                           (where the bundled audio tracks and schedule times
                           were extracted from) - not part of the build
```

## Building

### Option A: Android Studio (recommended for ongoing development)

Open this folder in Android Studio. It will offer to generate a Gradle
wrapper and sync automatically. Any reasonably recent Android Studio works -
no special JDK setup needed, Studio bundles its own.

### Option B: command line (no Android Studio)

This repo was built and is tracked against **Gradle 7.6.4 + Android Gradle
Plugin 7.4.2 + JDK 11** on purpose, not the latest versions. A newer AGP
requires JDK 17 to run, and JDK 17+'s `java.nio` Unix-domain-socket pipe
implementation does not work in some sandboxed/virtualized Windows setups
(symptom: Gradle fails with `java.io.IOException: Unable to establish
loopback connection`). JDK 11 doesn't hit that code path at all, so it's the
safe choice for building headlessly here. If you're on a normal machine /
Android Studio, this doesn't apply - feel free to upgrade AGP/Gradle/JDK/
compileSdk later via Option A.

A self-contained JDK 11 + Gradle 7.6.4 + Android SDK toolchain lives in
`../android-build-tools` (sibling folder, intentionally outside this repo -
it's a multi-GB toolchain, not project source). To build:

```bash
./build.sh
```

This produces `app/build/outputs/apk/debug/app-debug.apk`.

## Installing on a phone

The debug APK is unsigned by a release key (debug-signed only), which is
fine for sideloading: enable "Install unknown apps" for your file manager /
browser on the phone, transfer the APK, and tap to install. After install,
**open the app and go through the Setup screen once per phone** - that's the
step that actually fixes the OEM battery-killer problem; installing alone
does not.
