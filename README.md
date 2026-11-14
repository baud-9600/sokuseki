# 足跡 sokuseki — Location

A small, deliberately boring OwnTracks client for the **Mudita Kompakt** (Android 12,
E Ink). It posts OwnTracks-compatible location messages to a **Home Assistant OwnTracks
HTTP webhook** and is built to keep working when the phone is asleep.

*Sokuseki* (足跡) means footprints or tracks — the trail the phone leaves behind.

The regular OwnTracks app is powerful, but on the Kompakt it is easy to lose location
updates when the screen is off. sokuseki reduces the problem to the parts that matter:

- a real foreground service with `foregroundServiceType="location"`,
- a short partial wake lock around every fix and HTTP request,
- an `AlarmManager` watchdog that also fires in Doze,
- restart after boot and after the service is killed,
- a persistent FIFO for fixes that could not be delivered,
- no Google Play Services, no FusedLocationProvider, no MQTT library.

The UI uses [Mudita MMD](https://github.com/mudita/MMD); locally it is resolved from
`~/.m2`, in CI from Maven Central.

## Download

The latest CI-built APK is attached to the GitHub release:

<https://github.com/baud-9600/sokuseki/releases/latest/download/sokuseki.apk>

The CI APK is intentionally built without the private webhook defaults. Enter the
webhook URL in the app on first start. A local build with `local.properties` can ship
with the defaults already filled in.

## Home Assistant setup

1. In Home Assistant go to **Settings → Devices & services → Add integration →
   OwnTracks**.
2. Home Assistant shows a **webhook URL** (and an optional encryption key). sokuseki
   uses the unencrypted HTTP mode, so only the URL is needed. Keep the URL private: the
   long webhook ID is the only secret in HTTP mode.
3. Enter the URL, a username (sent as `X-Limit-U`), and a device name (sent as
   `X-Limit-D`) in the app.
4. The tracker appears in Home Assistant as `device_tracker.<username>_<device>`.

The app sends the same JSON fields as the Android OwnTracks app (`_type`, `lat`, `lon`,
`acc`, `tst`, `batt`, `bs`, `tid`, `conn`, `topic`, ...). Home Assistant requires a
non-zero accuracy and the `X-Limit-U` header; both are always included.

## Build

Requirements:

- JDK 17 or newer (the build box uses Temurin 21),
- Android SDK with platform 36 and build-tools 34+,
- MMD `1.0.2` in `~/.m2` for the local build (`mavenLocal()`); CI uses Maven Central.

```bash
./gradlew :app:assembleDebug --offline
./gradlew :app:assembleRelease --offline
./gradlew :app:testDebugUnitTest --offline
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the shared Kompakt key from `signing/signing.keystore`
when that gitignored file is present. Without it the release APK is unsigned and cannot
be installed.

## Configuration

The in-app settings screen is the normal way to configure the app. For a device that is
prepared from a build machine, `local.properties` can provide first-start defaults. The
file is gitignored, so the private webhook never lands in the repository:

```properties
sdk.dir=/path/to/Android/sdk
owntracks.url=https://home.example/api/webhook/…
owntracks.user=mk
owntracks.device=kompakt
owntracks.tid=kt
```

Those values become `BuildConfig` fields. Without `local.properties` the app starts with
empty fields and asks for the URL in the UI. The CI release is built without
`local.properties`, so its APK contains no private defaults.

## Permissions

The app asks for:

- precise location and background location,
- notifications (Android 13+ only; the Kompakt runs Android 12),
- exemption from battery optimization,
- exact alarms.

Granting battery-optimization exemption and background location is important for reliable
updates in deep sleep. The status section shows which permissions are still missing and
offers shortcuts to the relevant system screens.

## How a cycle works

1. The foreground service and the alarm wake the device.
2. A partial wake lock is held for at most three minutes.
3. The best recent fix is chosen from GPS, network, passive, and cached providers; a fresh
   fix is requested if the cached one is stale.
4. The JSON payload is posted to the webhook with `X-Limit-U` / `X-Limit-D`.
5. On failure the payload is appended to `files/pending.jsonl` (the newest 20 fixes are
   kept) and retried on the next cycle. On recovery the oldest queued fixes are sent
   first and the current fix last, so Home Assistant never jumps back to an old position.
6. The next alarm is scheduled.

The default interval is 5 minutes. Values from 1 to 120 minutes are accepted. With exact
alarms granted, the watchdog can fire at the configured interval even in Doze; without
the permission Android may defer the alarm.

## Release and signing

- `versionName = "0.1.0"`, tag `v0.1.0`, `versionCode = 10_000` (the same
  `major * 10_000_000 + minor * 10_000 + patch` scheme as the other Kompakt apps).
- The shared signing key is in the gitignored `signing/` directory. GitHub Actions
  restores it from the repository secrets `SIGNING_KEYSTORE_BASE64`,
  `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD`.
- Pushing a `v*` tag runs `.github/workflows/release.yml`, verifies the signing
  certificate, checks that `versionName` matches the tag, and attaches
  `sokuseki-v0.1.0.apk`, `sokuseki.apk`, and their SHA-256 files to the GitHub release.
- The expected certificate SHA-256 digest is
  `311b5cbb24e690121d1d2df664cb879e7a88b74ffee5be87c897c04a59aa6665`, the same key
  used by `aikotoba`, `ie`, `norikae`, and `etsuran`.

## Verified on the Kompakt

Tested on a Mudita Kompakt running MuditaOS K 1.6.1 (Android 12):

- a manual send reached the real Home Assistant webhook with HTTP 200,
- one-minute cycles continued with the screen off and with the device forced into
  `IDLE`/deep Doze,
- after a simulated reboot the foreground service was running again and sending,
- after an app update (`MY_PACKAGE_REPLACED`) the service restarted on its own,
- with Wi-Fi and mobile data disabled, two fixes were queued; after restoring the
  network the queue drained and the next request returned HTTP 200.

## Scope and limitations

- HTTP webhook only. MQTT and the OwnTracks Recorder `/pub` endpoint are not implemented.
- No OwnTracks region/waypoint or beacon messages.
- Payload encryption (Home Assistant's optional libsodium key) is not implemented; use
  HTTPS or a trusted LAN.
- The queue keeps the newest 20 fixes. Older points are intentionally dropped during a
  long outage; the current position matters more than a complete history.
- The webhook URL is stored in the app's private preferences. It is not committed to git.

## License

MIT. See [LICENSE](LICENSE).
